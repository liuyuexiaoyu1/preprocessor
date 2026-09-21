package com.replaymod.gradle.preprocess

import com.replaymod.gradle.remap.Transformer
import com.replaymod.gradle.remap.legacy.LegacyMapping
import com.replaymod.gradle.remap.legacy.LegacyMappingSetModelFactory
import net.fabricmc.mappingio.MappedElementKind
import net.fabricmc.mappingio.MappingReader
import net.fabricmc.mappingio.MappingVisitor
import net.fabricmc.mappingio.tree.MappingTree
import net.fabricmc.mappingio.tree.MemoryMappingTree
import org.cadixdev.lorenz.MappingSet
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileTree
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileCollection
import org.gradle.api.logging.Logging
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.kotlin.dsl.mapProperty
import org.gradle.kotlin.dsl.newInstance
import org.gradle.kotlin.dsl.property
import org.gradle.kotlin.dsl.submit
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters
import org.gradle.workers.WorkerExecutor
import java.io.File
import java.io.Serializable
import java.lang.ref.SoftReference
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.util.function.Consumer
import java.util.regex.Pattern
import javax.inject.Inject
import kotlin.io.path.bufferedReader
import kotlin.io.path.extension

data class Keywords(
    val disableRemap: String,
    val enableRemap: String,
    val `if`: String,
    val ifdef: String = "//#ifdef",
    val ifndef: String = "//#ifndef",
    val elseif: String,
    val elif: String,
    val `else`: String,
    val endif: String,
    val eval: String,
    val define: String = "//#define",
    val error: String = "//#error",
    val warn: String = "//#warn",
    val replace: String = "//#replace",
    val caseStart: String = "//#case",
    val caseBranch: String = "//?",
    val caseEnd: String = "//#endcase",
    val blockStart: String = "/*$$",
    val blockEnd: String = "$$*/",
    val inlineCase: String = "/*#case*/",
    val inlineBranch: String = "/*?",
    val inlineEnd: String = "*/",
) : Serializable

@CacheableTask
open class PreprocessTask @Inject constructor(
    private val objects: ObjectFactory,
    private val workerExecutor: WorkerExecutor,
) : DefaultTask() {
    companion object {
        @JvmStatic
        val DEFAULT_KEYWORDS = Keywords(
            disableRemap = "//#disable-remap",
            enableRemap = "//#enable-remap",
            `if` = "//#if",
            ifdef = "//#ifdef",
            ifndef = "//#ifndef",
            elseif = "//#elseif",
            elif = "//#elif",
            `else` = "//#else",
            endif = "//#endif",
            eval = "//$$",
            define = "//#define",
            error = "//#error",
            warn = "//#warn",
            replace = "//#replace",
            caseStart = "//#case",
            caseBranch = "//?",
            caseEnd = "//#endcase",
            blockStart = "/*$$",
            blockEnd = "$$*/",
            inlineCase = "/*#case*/",
            inlineBranch = "/*?",
            inlineEnd = "*/",
        )
        @JvmStatic
        val CFG_KEYWORDS = Keywords(
            disableRemap = "##disable-remap",
            enableRemap = "##enable-remap",
            `if` = "##if",
            ifdef = "##ifdef",
            ifndef = "##ifndef",
            elseif = "##elseif",
            elif = "##elif",
            `else` = "##else",
            endif = "##endif",
            eval = "#$$",
            define = "##define",
            error = "##error",
            warn = "##warn",
            replace = "##replace",
            caseStart = "##case",
            caseBranch = "##?",
            caseEnd = "##endcase",
            blockStart = "/*$$",
            blockEnd = "$$*/",
            inlineCase = "/*##case*/",
            inlineBranch = "/*##?",
            inlineEnd = "*/",
        )
    }

    data class InOut(
        val source: FileCollection,
        val generated: File,
        val overwrites: File?,
    )

    @Internal
    var entries: MutableList<InOut> = mutableListOf()

    @InputFiles
    @SkipWhenEmpty
    @PathSensitive(PathSensitivity.RELATIVE)
    fun getSourceFileTrees(): List<ConfigurableFileTree> {
        return entries.flatMap { it.source }.map { objects.fileTree().from(it) }
    }

    @InputFiles
    @Optional
    @PathSensitive(PathSensitivity.RELATIVE)
    fun getOverwritesFileTrees(): List<ConfigurableFileTree> {
        return entries.mapNotNull { it.overwrites }.map { objects.fileTree().from(it) }
    }

    @OutputDirectories
    fun getGeneratedDirectories(): List<File> {
        return entries.map { it.generated }
    }

    @InputFile
    @Optional
    @PathSensitive(PathSensitivity.NONE)
    var sourceMappings: File? = null

    @InputFile
    @Optional
    @PathSensitive(PathSensitivity.NONE)
    var destinationMappings: File? = null

    @Input
    @Optional
    val intermediateMappingsName = objects.property<String>()

    @Input
    val strictExtraMappings = objects.property<Boolean>().convention(false)

    @InputFile
    @Optional
    @PathSensitive(PathSensitivity.NONE)
    var mapping: File? = null

    @Input
    var reverseMapping: Boolean = false

    @InputDirectory
    @Optional
    @PathSensitive(PathSensitivity.RELATIVE)
    val jdkHome = objects.directoryProperty()

    @InputDirectory
    @Optional
    @PathSensitive(PathSensitivity.RELATIVE)
    val remappedjdkHome = objects.directoryProperty()

    @InputFiles
    @Optional
    @CompileClasspath
    var classpath: FileCollection? = null

    @InputFiles
    @Optional
    @CompileClasspath
    var remappedClasspath: FileCollection? = null

    @Input
    val vars = objects.mapProperty<String, Int>()

    @Input
    val keywords = objects.mapProperty<String, Keywords>()

    @Input
    @Optional
    val patternAnnotation = objects.property<String>()

    @Input
    @Optional
    val manageImports = objects.property<Boolean>()

    @Classpath
    val compiler = objects.fileCollection()

    @Input
    @Optional
    val enableRemapMessageCollector = project.objects.property<Boolean>()

    fun entry(source: FileCollection, generated: File, overwrites: File) {
        entries.add(InOut(source, generated, overwrites))
    }

    @TaskAction
    fun preprocess() {
        preprocess(mapping, entries)
    }

    fun preprocess(mappingIn: File?, entriesIn: List<InOut>) {
        val workQueue = if (compiler.isEmpty) {
            workerExecutor.noIsolation()
        } else {
            workerExecutor.noIsolation()
        }

        workQueue.submit(PreprocessAction::class) {
            compiler.set(this@PreprocessTask.compiler)
            entries.set(entriesIn.map { entry ->
                objects.newInstance(PreprocessParameters.InOut::class).apply {
                    source.set(entry.source)
                    generated.set(entry.generated)
                    overwrites.set(entry.overwrites)
                }
            })
            sourceMappings.set(this@PreprocessTask.sourceMappings)
            destinationMappings.set(this@PreprocessTask.destinationMappings)
            intermediateMappingsName.set(this@PreprocessTask.intermediateMappingsName)
            strictExtraMappings.set(this@PreprocessTask.strictExtraMappings)
            mapping.set(mappingIn)
            reverseMapping.set(this@PreprocessTask.reverseMapping)
            jdkHome.set(this@PreprocessTask.jdkHome)
            remappedjdkHome.set(this@PreprocessTask.remappedjdkHome)
            classpath.set(this@PreprocessTask.classpath)
            remappedClasspath.set(this@PreprocessTask.remappedClasspath)
            vars.set(this@PreprocessTask.vars)
            keywords.set(this@PreprocessTask.keywords)
            patternAnnotation.set(this@PreprocessTask.patternAnnotation)
            manageImports.set(this@PreprocessTask.manageImports)
            enableRemapMessageCollector.set(this@PreprocessTask.enableRemapMessageCollector)
        }

        workQueue.await()
    }
}

internal interface PreprocessParameters : WorkParameters {
    val compiler: Property<FileCollection>

    interface InOut {
        val source: Property<FileCollection>
        val generated: Property<File>
        val overwrites: Property<File>
    }
    val entries: ListProperty<InOut>
    val sourceMappings: Property<File>
    val destinationMappings: Property<File>
    val intermediateMappingsName: Property<String>
    val strictExtraMappings: Property<Boolean>
    val mapping: Property<File>
    val reverseMapping: Property<Boolean>
    val jdkHome: DirectoryProperty
    val remappedjdkHome: DirectoryProperty
    val classpath: Property<FileCollection>
    val remappedClasspath: Property<FileCollection>
    val vars: MapProperty<String, Int>
    val keywords: MapProperty<String, Keywords>
    val patternAnnotation: Property<String>
    val manageImports: Property<Boolean>
    val enableRemapMessageCollector: Property<Boolean>
}

private val LOGGER = Logging.getLogger(PreprocessTask::class.java)

internal abstract class PreprocessAction : WorkAction<PreprocessParameters> {
    override fun execute() {
        val compiler = parameters.compiler.get()
        if (compiler.isEmpty) {
            PreprocessActionImpl().accept(parameters)
        } else {
            executeIsolated(compiler)
        }
    }

    private fun executeIsolated(compilerClasspath: FileCollection) {
        val fullClasspath =
            compilerClasspath.files.map { it.toURI().toURL() } + listOf(
                PreprocessActionImpl::class.java,
                Transformer::class.java,
            ).map { it.protectionDomain.codeSource.location }

        LOGGER.debug("Remap IsolatedClassLoader classpath:")
        fullClasspath.forEach { LOGGER.debug(" - {}", it) }

        val cacheKey = fullClasspath.map { it.toString() }
        val classLoader = synchronized(cache) {
            cache.values.removeIf { it.get() == null }
            cache[cacheKey]?.get() ?: IsolatedClassLoader(
                fullClasspath.toTypedArray(),
                javaClass.classLoader,
                exclusions = listOf(
                    "org.gradle.",
                    "net.fabricmc.mappingio.",
                    "org.cadixdev.lorenz.",
                    "org.cadixdev.bombe.",
                    "org.objectweb.asm.",
                    PreprocessParameters::class.java.name,
                    Keywords::class.java.name,
                ),
            ).also { cache[cacheKey] = SoftReference(it) }
        }

        val implClass = classLoader.loadClass(PreprocessActionImpl::class.java.name)
        val implInstance = implClass.constructors.first().apply { isAccessible = true }.newInstance()

        @Suppress("UNCHECKED_CAST")
        (implInstance as Consumer<PreprocessParameters>).accept(parameters)
    }

    companion object {
        private val cache = mutableMapOf<List<String>, SoftReference<IsolatedClassLoader>>()
    }
}

private class PreprocessActionImpl : Consumer<PreprocessParameters> {
    override fun accept(params: PreprocessParameters) {
        val logger = LOGGER
        val entries = params.entries.get().map { PreprocessTask.InOut(it.source.get(), it.generated.get(), it.overwrites.orNull) }
        val sourceMappings = params.sourceMappings.orNull
        val destinationMappings = params.destinationMappings.orNull
        val intermediateMappingsName = params.intermediateMappingsName
        val strictExtraMappings = params.strictExtraMappings
        val mapping = params.mapping.orNull
        val reverseMapping = params.reverseMapping.get()
        val jdkHome = params.jdkHome
        val remappedjdkHome = params.remappedjdkHome
        val classpath = params.classpath.orNull
        val remappedClasspath = params.remappedClasspath.orNull
        val vars = params.vars
        val keywords = params.keywords
        val patternAnnotation = params.patternAnnotation
        val manageImports = params.manageImports

        data class Entry(val relPath: String, val inBase: Path, val outBase: Path, val overwritesBase: Path?)
        val sourceFiles: List<Entry> = entries.flatMap { inOut ->
            val outBasePath = inOut.generated.toPath()
            val overwritesBasePath = inOut.overwrites?.toPath()
            inOut.source.flatMap { inBase ->
                val inBasePath = inBase.toPath()
                inBase.walk().filter { it.isFile }.map { file ->
                    val relPath = inBasePath.relativize(file.toPath())
                    Entry(relPath.toString(), inBasePath, outBasePath, overwritesBasePath)
                }
            }
        }

        System.getenv("PREPROCESS_DUMP_DIR")?.let { dir ->
            runCatching {
                java.io.File(dir, "_sourcefiles.txt").writeText(
                    sourceFiles.filter { it.relPath.endsWith("VaultTask.java") }
                        .joinToString("\n") { "${it.relPath}   <-   ${it.inBase}" }
                )
            }
        }

        var mappedSources: Map<String, Pair<String, List<Pair<Int, String>>>>? = null

        val sourceMappingsFile = sourceMappings
        val destinationMappingsFile = destinationMappings
        val mappings = if (intermediateMappingsName.isPresent && classpath != null && sourceMappingsFile != null && destinationMappingsFile != null) {
            val sharedMappingsNamespace = intermediateMappingsName.get()
            val srcTree = MemoryMappingTree().also { readMappings(sourceMappingsFile.toPath(), it) }
            val dstTree = MemoryMappingTree().also { readMappings(destinationMappingsFile.toPath(), it) }
            if (strictExtraMappings.get()) {
                if (sharedMappingsNamespace == "srg") {
                    inferSharedClassMappings(srcTree, dstTree, sharedMappingsNamespace)
                }
                srcTree.setIndexByDstNames(true)
                dstTree.setIndexByDstNames(true)
                val extTree = mapping?.let { file ->
                    try {
                        val ast = ExtraMapping.read(file.toPath())
                        if (!reverseMapping) {
                            ast.resolve(logger, srcTree, dstTree, "named", sharedMappingsNamespace).first
                        } else {
                            ast.resolve(logger, dstTree, srcTree, "named", sharedMappingsNamespace).second
                        }
                    } catch (e: Exception) {
                        throw GradleException("Failed to parse $file: ${e.message}", e)
                    }
                } ?: MemoryMappingTree().apply {
                    visitNamespaces("source", listOf("destination"))
                    visitEnd()
                }
                val mrgTree = mergeMappings(srcTree, dstTree, extTree, sharedMappingsNamespace)
                TinyReader(mrgTree, "source", "destination").read()
            } else {
                val sourceMappings = TinyReader(srcTree, "named", sharedMappingsNamespace).read()
                val destinationMappings = TinyReader(dstTree, "named", sharedMappingsNamespace).read()
                if (mapping != null) {
                    val legacyMap = LegacyMapping.readMappingSet(mapping.toPath(), reverseMapping)
                    val clsMap = legacyMap.splitOffClassMappings()
                    val srcMap = sourceMappings
                    val dstMap = destinationMappings
                    legacyMap.mergeBoth(
                        srcMap.mergeBoth(clsMap).join(dstMap.reverse()).mergeBoth(clsMap),
                        MappingSet.create(LegacyMappingSetModelFactory()))
                } else {
                    val srcMap = sourceMappings!!
                    val dstMap = destinationMappings!!
                    srcMap.join(dstMap.reverse())
                }
            }
        } else if (!intermediateMappingsName.isPresent && classpath != null && (mapping != null || sourceMappings != null && destinationMappings != null)) {
            if (mapping != null) {
                if (sourceMappings != null && destinationMappings != null) {
                    val legacyMap = LegacyMapping.readMappingSet(mapping.toPath(), reverseMapping)
                    val clsMap = legacyMap.splitOffClassMappings()
                    val srcMap = sourceMappings!!.readMappings()
                    val dstMap = destinationMappings!!.readMappings()
                    legacyMap.mergeBoth(
                        srcMap.mergeBoth(clsMap).join(dstMap.reverse()).mergeBoth(clsMap),
                        MappingSet.create(LegacyMappingSetModelFactory()))
                } else {
                    LegacyMapping.readMappingSet(mapping.toPath(), reverseMapping)
                }
            } else {
                val srcMap = sourceMappings!!.readMappings()
                val dstMap = destinationMappings!!.readMappings()
                srcMap.join(dstMap.reverse())
            }
        } else {
            null
        }
        if (mappings != null) {
            classpath!!
            val javaTransformer = Transformer(mappings)
            javaTransformer.enableMessageCollector = params.enableRemapMessageCollector.getOrElse(false)
            javaTransformer.verboseCompilerMessages = logger.isInfoEnabled
            javaTransformer.patternAnnotation = patternAnnotation.orNull
            javaTransformer.manageImports = manageImports.getOrElse(false)
            javaTransformer.jdkHome = jdkHome.orNull?.asFile
            javaTransformer.remappedJdkHome = remappedjdkHome.orNull?.asFile
            LOGGER.debug("Remap Classpath:")
            javaTransformer.classpath = classpath.files.mapNotNull {
                if (it.exists()) {
                    it.absolutePath.also(LOGGER::debug)
                } else {
                    LOGGER.debug("$it (file does not exist)")
                    null
                }
            }.toTypedArray()
            LOGGER.debug("Remapped Classpath:")
            javaTransformer.remappedClasspath = remappedClasspath?.files?.mapNotNull {
                if (it.exists()) {
                    it.absolutePath.also(LOGGER::debug)
                } else {
                    LOGGER.debug("$it (file does not exist)")
                    null
                }
            }?.toTypedArray()
            val sources = mutableMapOf<String, String>()
            val processedSources = mutableMapOf<String, String>()
            sourceFiles.forEach { (relPath, inBase, _, _) ->
                if (relPath.endsWith(".java") || relPath.endsWith(".kt")) {
                    val text = String(Files.readAllBytes(inBase.resolve(relPath)))
                    sources[relPath] = text
                    val lines = text.lines()
                    val kws = keywords.get().entries.find { (ext, _) -> relPath.endsWith(ext) }
                    if (kws != null) {
                        processedSources[relPath] = CommentPreprocessor(vars.get()).convertSource(
                            kws.value,
                            lines,
                            lines.map { Pair(it, emptyList()) },
                            relPath,
                            // This pass feeds the remapper, so it must stay parseable: no `eval` prefixes.
                            finalPass = false,
                        ).joinToString("\n")
                    }
                }
            }
            val overwritesFiles = entries
                .mapNotNull { it.overwrites }
                .flatMap { base -> base.walk().filter { it.isFile }.map { Pair(base.toPath(), it) } }
            overwritesFiles.forEach { (base, file) ->
                if (file.name.endsWith(".java") || file.name.endsWith(".kt")) {
                    val relPath = base.relativize(file.toPath())
                    processedSources[relPath.toString()] = file.readText()
                }
            }
            mappedSources = javaTransformer.remap(sources, processedSources)
        }

        entries.forEach { it.generated.deleteRecursively() }

        val commentPreprocessor = CommentPreprocessor(vars.get())
        sourceFiles.forEach { (relPath, inBase, outBase, overwritesPath) ->
            val file = inBase.resolve(relPath).toFile()
            val outFile = outBase.resolve(relPath).toFile()
            if (overwritesPath != null && Files.exists(overwritesPath.resolve(relPath))) {
                return@forEach
            }
            val kws = keywords.get().entries.find { (ext, _) -> file.name.endsWith(ext) }
            if (kws != null) {
                val javaTransform = { lines: List<String> ->
                    mappedSources?.get(relPath)?.let { (source, errors) ->
                        val errorsByLine = mutableMapOf<Int, MutableList<String>>()
                        for ((line, error) in errors) {
                            errorsByLine.getOrPut(line, ::mutableListOf).add(error)
                        }
                        source.lines().mapIndexed { index: Int, line: String -> Pair(line, errorsByLine[index] ?: emptyList<String>()) }
                    } ?: lines.map { Pair(it, emptyList()) }
                }
                commentPreprocessor.convertFile(kws.value, file, outFile, javaTransform)
            } else {
                outFile.parentFile.mkdirs()
                file.copyTo(outFile)
            }
        }

        if (commentPreprocessor.fail) {
            throw GradleException("Failed to remap sources. See errors above for details.")
        }
    }

    private fun readMappings(path: Path, visitor: MappingVisitor) {
        if (path.extension == "jar") {
            FileSystems.newFileSystem(path).use { fileSystem ->
                fileSystem.getPath("mappings", "mappings.tiny").bufferedReader().use { reader ->
                    return MappingReader.read(reader, visitor)
                }
            }
        } else {
            return MappingReader.read(path, visitor)
        }
    }

    private fun inferSharedClassMappings(
        srcTree: MemoryMappingTree,
        dstTree: MemoryMappingTree,
        sharedNamespace: String,
    ) {
        val srcNsId = srcTree.getNamespaceId(sharedNamespace)
        val dstNsId = dstTree.getNamespaceId(sharedNamespace)

        val done = mutableSetOf<String>()

        for (srcCls in srcTree.classes) {
            val srcName = srcCls.getName(srcNsId)!!
            if (dstTree.getClass(srcName, dstNsId) != null) {
                done.add(srcName)
            }
        }

        var nextSharedId = 0
        do {
            val doneBeforeRound = done.size

            val srcMemberToClass = mutableMapOf<String, MutableList<String>>()
            for (cls in srcTree.classes) {
                val clsName = cls.getName(srcNsId)!!
                if (clsName in done) continue
                for (field in cls.fields) {
                    val name = field.getName(srcNsId)!!
                    if (!name.startsWith("field_")) continue
                    srcMemberToClass.getOrPut(name, ::mutableListOf).add(clsName)
                }
                for (method in cls.methods) {
                    val name = method.getName(srcNsId)!!
                    if (!name.startsWith("func_")) continue
                    srcMemberToClass.getOrPut(name, ::mutableListOf).add(clsName)
                }
            }
            val dstMemberToClass = mutableMapOf<String, MutableList<String>>()
            for (cls in dstTree.classes) {
                val clsName = cls.getName(dstNsId)!!
                if (clsName in done) continue
                for (field in cls.fields) {
                    val name = field.getName(dstNsId)!!
                    if (!name.startsWith("field_")) continue
                    dstMemberToClass.getOrPut(name, ::mutableListOf).add(clsName)
                }
                for (method in cls.methods) {
                    val name = method.getName(dstNsId)!!
                    if (!name.startsWith("func_")) continue
                    dstMemberToClass.getOrPut(name, ::mutableListOf).add(clsName)
                }
            }

            val srcMappings = tryInferMapping(srcTree, srcNsId, dstMemberToClass, done)
            val dstMappings = tryInferMapping(dstTree, dstNsId, srcMemberToClass, done)

            for ((srcName, dstNames) in srcMappings) {
                if (dstNames.isEmpty()) continue
                if (dstNames.size > 1) continue
                val dstName = dstNames.single()

                val revSrcNames = dstMappings.getValue(dstName)
                assert(revSrcNames.isNotEmpty())
                if (revSrcNames.size > 1) continue
                val revSrcName = revSrcNames.single()
                if (revSrcName != srcName) continue

                val srcCls = srcTree.getClass(srcName, srcNsId)!!
                val dstCls = dstTree.getClass(dstName, dstNsId)!!

                val sharedName = "class_${nextSharedId++}"
                srcCls.setDstName(sharedName, srcNsId)
                dstCls.setDstName(sharedName, dstNsId)
                done.add(sharedName)
            }
        } while (done.size > doneBeforeRound)
    }

    private fun tryInferMapping(
        srcTree: MappingTree,
        srcNsId: Int,
        dstMemberToClass: Map<String, List<String>>,
        done: Set<String>,
    ): Map<String, Collection<String>> {
        val results = mutableMapOf<String, Collection<String>>()
        for (srcCls in srcTree.classes) {
            val srcName = srcCls.getName(srcNsId)!!
            if (srcName in done) continue

            val candidates = mutableMapOf<String, Int>()

            for (srcField in srcCls.fields) {
                for (dstCls in dstMemberToClass[srcField.getName(srcNsId)!!] ?: emptyList()) {
                    candidates.compute(dstCls) { _, n -> (n ?: 0) + 1 }
                }
            }
            for (srcMethod in srcCls.methods) {
                for (dstCls in dstMemberToClass[srcMethod.getName(srcNsId)!!] ?: emptyList()) {
                    candidates.compute(dstCls) { _, n -> (n ?: 0) + 1 }
                }
            }

            if (candidates.isEmpty()) {
                results[srcName] = emptyList()
                continue
            }

            val (bestName, bestCount) = candidates.maxBy { it.value }
            if (candidates.all { (name, count) -> name === bestName || count < bestCount }) {
                results[srcName] = listOf(bestName)
            } else {
                results[srcName] = candidates.keys
            }
        }
        return results
    }

    private fun mergeMappings(
        srcTree: MappingTree,
        dstTree: MappingTree,
        extTree: MemoryMappingTree,
        sharedNamespace: String,
    ): MappingTree {
        val srcNamedNsId = srcTree.getNamespaceId("named")
        val srcSharedNsId = srcTree.getNamespaceId(sharedNamespace)
        val dstSharedNsId = dstTree.getNamespaceId(sharedNamespace)
        val dstNamedNsId = dstTree.getNamespaceId("named")
        val extSrcNsId = extTree.getNamespaceId("source")
        val extDstNsId = extTree.getNamespaceId("destination")

        val tmpTree = MemoryMappingTree()
        tmpTree.visitNamespaces(dstTree.srcNamespace, dstTree.dstNamespaces)
        val mrgTree = MemoryMappingTree()
        mrgTree.visitNamespaces("source", listOf("destination"))

        fun injectExtraMembers(extCls: MappingTree.ClassMapping) {
            for (extField in extCls.fields) {
                val srcName = extField.getName(extSrcNsId)
                val srcDesc = extField.getDesc(extSrcNsId)
                if (srcDesc == null) {
                    LOGGER.error("Owner ${extCls.getName(extSrcNsId)} of field $srcName does not appear to have any mappings. " +
                            "As such, you must provide the full signature of this method manually " +
                            "(if it does not change across versions, providing it for either version is sufficient).")
                    continue
                }
                mrgTree.visitField(srcName, srcDesc)
                mrgTree.visitDstName(MappedElementKind.FIELD, 0, extField.getName(extDstNsId))
            }
            for (extMethod in extCls.methods) {
                val srcName = extMethod.getName(extSrcNsId)
                val srcDesc = extMethod.getDesc(extSrcNsId)
                if (srcDesc == null) {
                    LOGGER.error("Owner ${extCls.getName(extSrcNsId)} of method $srcName does not appear to have any mappings. " +
                            "As such, you must provide the full signature of this method manually " +
                            "(if it does not change across versions, providing it for either version is sufficient).")
                    continue
                }
                mrgTree.visitMethod(srcName, srcDesc)
                mrgTree.visitDstName(MappedElementKind.METHOD, 0, extMethod.getName(extDstNsId))
            }
        }

        for (srcCls in srcTree.classes) {
            val extCls = extTree.removeClass(srcCls.getName(srcNamedNsId))
            val dstCls = if (extCls != null) {
                val dstName = extCls.getName(extDstNsId)
                dstTree.getClass(dstName, dstNamedNsId) ?: run {
                    tmpTree.visitClass(dstName)
                    tmpTree.visitDstName(MappedElementKind.CLASS, dstNamedNsId, dstName)
                    tmpTree.getClass(dstName)!!
                }
            } else {
                dstTree.getClass(srcCls.getName(srcSharedNsId), dstSharedNsId) ?: continue
            }
            mrgTree.visitClass(srcCls.getName(srcNamedNsId))
            mrgTree.visitDstName(MappedElementKind.CLASS, 0, dstCls.getName(dstNamedNsId))
            for (srcField in srcCls.fields) {
                val extField = extCls?.getField(srcField.getName(srcNamedNsId), srcField.getDesc(srcNamedNsId), extSrcNsId)
                if (extField != null) {
                    extCls.removeField(extField.srcName, extField.srcDesc)
                    mrgTree.visitField(srcField.getName(srcNamedNsId), srcField.getDesc(srcNamedNsId))
                    mrgTree.visitDstName(MappedElementKind.FIELD, 0, extField.getName(extDstNsId))
                    continue
                }
                val dstField = dstCls.getField(srcField.getName(srcSharedNsId), srcField.getDesc(srcSharedNsId), dstSharedNsId)
                    ?: dstCls.getField(srcField.getName(srcSharedNsId), null, dstSharedNsId)
                    ?: continue
                mrgTree.visitField(srcField.getName(srcNamedNsId), srcField.getDesc(srcNamedNsId))
                mrgTree.visitDstName(MappedElementKind.FIELD, 0, dstField.getName(dstNamedNsId))
            }
            for (srcMethod in srcCls.methods) {
                val extMethod = extCls?.getMethod(srcMethod.getName(srcNamedNsId), srcMethod.getDesc(srcNamedNsId), extSrcNsId)
                if (extMethod != null) {
                    extCls.removeMethod(extMethod.srcName, extMethod.srcDesc)
                    mrgTree.visitMethod(srcMethod.getName(srcNamedNsId), srcMethod.getDesc(srcNamedNsId))
                    mrgTree.visitDstName(MappedElementKind.METHOD, 0, extMethod.getName(extDstNsId))
                    continue
                }
                val dstMethod = dstCls.getMethod(srcMethod.getName(srcSharedNsId), srcMethod.getDesc(srcSharedNsId), dstSharedNsId)
                    ?: dstCls.getMethod(srcMethod.getName(srcSharedNsId), null, dstSharedNsId)
                    ?: continue
                mrgTree.visitMethod(srcMethod.getName(srcNamedNsId), srcMethod.getDesc(srcNamedNsId))
                mrgTree.visitDstName(MappedElementKind.METHOD, 0, dstMethod.getName(dstNamedNsId))
            }
            if (extCls != null) {
                injectExtraMembers(extCls)
            }
        }
        for (extCls in extTree.classes) {
            mrgTree.visitClass(extCls.getName(extSrcNsId))
            mrgTree.visitDstName(MappedElementKind.CLASS, 0, extCls.getName(extDstNsId))
            injectExtraMembers(extCls)
        }
        mrgTree.visitEnd()
        return mrgTree
    }
}

class CommentPreprocessor(private val vars: Map<String, Int>) {
    companion object {
        /** Marks a `//#case` group as allowed to match no alternative at all (`//#case optional`). */
        private const val OPTIONAL = "optional"
        /** Default branch of a `//#case` group (`//?else <content>`). */
        private const val ELSE_BRANCH = "else"
        private val EXPR_PATTERN = Pattern.compile("(.+)(==|!=|<=|>=|<|>)(.+)")
        // `X in A..B` where A and B are version-like literals or variable names. The low bound is inclusive,
        // the high bound is exclusive. A leading `not` inverts the test.
        private val RANGE_PATTERN = Pattern.compile("""(.+?)\s+(not\s+)?in\s+(.+?)\.\.(.+)""")
        // `X in [A, B, C]`, once again with an optional `not`.
        private val SET_PATTERN = Pattern.compile("""(.+?)\s+(not\s+)?in\s+\[(.+)]""")
        /** Replaced during expansion, so that the argument of `defined(X)` is not mistaken for an alias. */
        private val DEFINED_PATTERN = Pattern.compile("""defined\s*\(\s*([A-Za-z_]\w*)\s*\)""")
        // Conditions written without the version variable: `>=1.21.5`, `1.20.1`, `1.20.1..1.21.5`.
        private val BARE_COMPARISON = Pattern.compile("""(>=|<=|==|!=|>|<)\s*[0-9][\w.]*""")
        private val BARE_VERSION = Pattern.compile("""[0-9][\w.]*""")
        private val BARE_RANGE = Pattern.compile("""[0-9][\w.]*\.\.[0-9][\w.]*""")
        /** Cap on `//#define` expansion passes, so a cycle is reported instead of hanging. */
        private const val MAX_DEFINE_DEPTH = 8
    }

    var fail = false

    /**
     * Aliases declared by `//#define` directives. Cleared at the start of every [convertSource] call, since a
     * single instance serves all files of a task.
     */
    private val definitions = mutableMapOf<String, String>()

    /** The variable a bare condition such as `>=1.21.5` refers to. */
    private val primaryVar: String? = when {
        "MC" in vars -> "MC"
        vars.size == 1 -> vars.keys.first()
        else -> null
    }

    /** Appended to condition errors, so the values behind a condition are visible in the build log. */
    private val varsHint: String =
        if (vars.isEmpty()) "" else " (vars: " + vars.entries.joinToString(", ") { "${it.key}=${it.value}" } + ")"

    /**
     * Expands a condition before parsing:
     * - `defined(X)` becomes `1` or `0`,
     * - `//#define` aliases are substituted (parenthesized, so precedence is preserved),
     * - a condition that is just a comparison, a version or a range (`>=1.21.5`) is applied to [primaryVar].
     */
    private fun expandCondition(condition: String): String {
        var text = expandShorthand(condition.trim())
        if (text.contains("defined")) {
            val matcher = DEFINED_PATTERN.matcher(text)
            val builder = StringBuilder()
            var last = 0
            while (matcher.find()) {
                builder.append(text, last, matcher.start())
                builder.append(if (isDefined(matcher.group(1))) "1" else "0")
                last = matcher.end()
            }
            builder.append(text, last, text.length)
            text = builder.toString()
        }
        return expandDefines(text)
    }

    private fun isDefined(name: String): Boolean = name in vars || name in definitions

    /**
     * Stonecutter-style shorthand: dropping the version variable is allowed when the condition is unambiguously a
     * comparison, a version or a range, i.e. when it starts with an operator or a digit. `MC >= 1.21.5 && FABRIC`
     * starts with a name and is therefore left alone.
     */
    private fun expandShorthand(text: String): String {
        if (text.isEmpty()) return text
        val first = text[0]
        if (first.isLetter() || first == '!' || first == '(') return text
        val primary = primaryVar ?: throw InvalidExpressionException(text)
        return when {
            BARE_RANGE.matcher(text).matches() -> "$primary in $text"
            BARE_COMPARISON.matcher(text).matches() -> "$primary $text"
            // A bare integer stays a plain number (`0` and `1` are useful as literal true/false), only a
            // dot-separated version is read as an equality test.
            BARE_VERSION.matcher(text).matches() && '.' in text -> "$primary == $text"
            else -> text
        }
    }

    private fun expandDefines(text: String): String {
        if (definitions.isEmpty()) return text
        var result = text
        repeat(MAX_DEFINE_DEPTH) {
            val builder = StringBuilder()
            var changed = false
            var i = 0
            while (i < result.length) {
                val c = result[i]
                if (c.isLetter() || c == '_') {
                    var j = i
                    while (j < result.length && (result[j].isLetterOrDigit() || result[j] == '_')) j++
                    val word = result.substring(i, j)
                    val replacement = definitions[word]
                    if (replacement == null) {
                        builder.append(word)
                    } else {
                        builder.append('(').append(replacement).append(')')
                        changed = true
                    }
                    i = j
                } else {
                    builder.append(c)
                    i++
                }
            }
            result = builder.toString()
            if (!changed) return result
        }
        throw InvalidExpressionException(text)
    }

    private fun String.evalVarOrNull(): Int? {
        vars[this]?.let { return it }

        if ("." in this) {
            val (majorS, minorS, patchS) = this.split(".") + listOf("0")
            val major = majorS.toIntOrNull() ?: return null
            val minor = minorS.toIntOrNull() ?: return null
            val patch = patchS.toIntOrNull() ?: return null
            return major * 1_00_00 + minor * 1_00 + patch
        }

        return replace("_", "").toIntOrNull()
    }
    private fun String.evalVar() = evalVarOrNull() ?: throw NoSuchElementException("$this not in $vars")

    internal fun String.evalExpr(): Boolean = ExprParser(expandCondition(this)).parse()

    /**
     * 解析不带 `&&`、`||`、括号的原子表达式：范围、变量、比较。
     * 由 [ExprParser] 在确定需要求值时调用。
     */
    private fun evalAtom(atom: String): Boolean {
        if (atom.isEmpty()) {
            throw InvalidExpressionException(atom)
        }

        val rangeMatcher = RANGE_PATTERN.matcher(atom)
        if (rangeMatcher.matches()) {
            val lhs = rangeMatcher.group(1).trim().evalVar()
            val low = rangeMatcher.group(3).trim().evalVar()
            val high = rangeMatcher.group(4).trim().evalVar()
            val inside = lhs >= low && lhs < high
            return if (rangeMatcher.group(2) != null) !inside else inside
        }

        val setMatcher = SET_PATTERN.matcher(atom)
        if (setMatcher.matches()) {
            val lhs = setMatcher.group(1).trim().evalVar()
            val inside = setMatcher.group(3).split(',').any { it.trim().evalVar() == lhs }
            return if (setMatcher.group(2) != null) !inside else inside
        }

        val result = atom.evalVarOrNull()
        if (result != null) {
            return result != 0
        }

        val matcher = EXPR_PATTERN.matcher(atom)
        if (matcher.matches()) {
            val lhs = matcher.group(1).trim().evalVar()
            val rhs = matcher.group(3).trim().evalVar()
            return when (matcher.group(2)) {
                "==" -> lhs == rhs
                "!=" -> lhs != rhs
                ">=" -> lhs >= rhs
                "<=" -> lhs <= rhs
                ">" -> lhs > rhs
                "<" -> lhs < rhs
                else -> throw InvalidExpressionException(atom)
            }
        }
        throw InvalidExpressionException(atom)
    }

    private inner class ExprParser(private val source: String) {
        private var pos = 0

        fun parse(): Boolean {
            val result = parseOr(evaluate = true)
            skipWhitespace()
            if (pos < source.length) {
                throw InvalidExpressionException(source)
            }
            return result
        }

        private fun parseOr(evaluate: Boolean): Boolean {
            var left = parseAnd(evaluate)
            while (true) {
                skipWhitespace()
                if (source.startsWith("||", pos)) {
                    pos += 2
                    val right = parseAnd(evaluate && !left)
                    left = if (evaluate) left || right else false
                } else break
            }
            return left
        }

        private fun parseAnd(evaluate: Boolean): Boolean {
            var left = parseUnary(evaluate)
            while (true) {
                skipWhitespace()
                if (source.startsWith("&&", pos)) {
                    pos += 2
                    val right = parseUnary(evaluate && left)
                    left = if (evaluate) left && right else false
                } else break
            }
            return left
        }

        private fun parseUnary(evaluate: Boolean): Boolean {
            skipWhitespace()
            if (pos >= source.length) {
                throw InvalidExpressionException(source)
            }
            if (source[pos] == '!') {
                pos++
                val inner = parseUnary(evaluate)
                return if (evaluate) !inner else false
            }
            if (source[pos] == '(') {
                pos++
                val inner = parseOr(evaluate)
                skipWhitespace()
                if (pos >= source.length || source[pos] != ')') {
                    throw InvalidExpressionException(source)
                }
                pos++
                return inner
            }
            val atom = readAtom()
            if (atom.isEmpty()) {
                throw InvalidExpressionException(source)
            }
            return if (evaluate) evalAtom(atom) else false
        }

        private fun readAtom(): String {
            skipWhitespace()
            val start = pos
            while (pos < source.length) {
                if (source.startsWith("&&", pos) || source.startsWith("||", pos)) break
                if (source[pos] == ')') break
                pos++
            }
            return source.substring(start, pos).trim()
        }

        private fun skipWhitespace() {
            while (pos < source.length && source[pos].isWhitespace()) pos++
        }
    }

    private val String.indentation: String
        get() = takeWhile { it == ' ' || it == '\t' }

    /**
     * Collects the `//#define <name> <condition>` aliases of a file. Kept as a separate pass so aliases are
     * position independent and a duplicate (with a different condition) can be reported instead of silently
     * shadowing.
     */
    private fun collectDefinitions(kws: Keywords, lines: List<String>, fileName: String) {
        lines.forEachIndexed { index, raw ->
            val text = raw.trim()
            if (!text.startsWith(kws.define)) return@forEachIndexed
            val rest = text.substring(kws.define.length).trim()
            val name = rest.takeWhile { !it.isWhitespace() }
            val condition = rest.substring(name.length).trim()
            val lineno = index + 1
            if (name.isEmpty()) {
                throw ParserException(
                    "Expected `<name> <condition>` after ${kws.define} in line $lineno of $fileName"
                )
            }
            if (!name[0].isLetter() && name[0] != '_') {
                throw ParserException("Invalid name \"$name\" after ${kws.define} in line $lineno of $fileName")
            }
            if (condition.isEmpty()) {
                throw ParserException("Expected a condition after \"$name\" in line $lineno of $fileName")
            }
            val previous = definitions.put(name, condition)
            if (previous != null && previous != condition) {
                throw ParserException("Duplicate ${kws.define} of \"$name\" in line $lineno of $fileName")
            }
        }
    }

    fun convertSource(
        kws: Keywords,
        lines: List<String>,
        remapped: List<Pair<String, List<String>>>,
        fileName: String,
        /**
         * Whether this pass produces the final output. The pass that feeds the remapper must not comment lines out
         * with [Keywords.eval]: the remapper parses the text, and a commented-out brace leaves it unbalanced. Only
         * the last pass may apply the `eval` prefix.
         */
        finalPass: Boolean = true,
    ): List<String> {
        // Aliases are collected before anything is processed, so an alias may be used before its definition and
        // the whole file sees the same set.
        definitions.clear()
        collectDefinitions(kws, lines, fileName)
        val stack = mutableListOf<IfStackEntry>()
        val indentStack = mutableListOf<String>()
        var active = true
        var remapActive = true

        var n = 0
        var inBlockComment = false
        var inCase = false
        var caseLine = -1
        // Inside a `//#case` group the first matching `//?` alternative wins and the rest are skipped.
        // Outside a group, `//?` acts as a standalone single-line conditional.
        var caseMatched = false
        // Whether the current group opted out of the "some branch must match" check (`//#case optional`).
        var caseOptional = false
        // Whether the current `//#case` group contains at least one `//?` alternative. Used to distinguish
        // "no branch matched" (a user error worth reporting) from "the group has no branches at all" (fine).
        var caseHadAnyBranch = false

        fun evalCondition(condition: String): Boolean {
            if (!condition.startsWith(" "))
                throw ParserException("Expected space before condition in line $n of $fileName")
            try {
                return condition.trim().evalExpr()
            } catch (e: InvalidExpressionException) {
                throw ParserException("Invalid expression \"${e.message}\" in line $n of $fileName$varsHint")
            }
        }

        return lines.zip(remapped).map { (originalLine, lineMapped) ->
            val (line, errors) = lineMapped
            var ignoreErrors = false
            n++
            val trimmed = line.trim()
            // A line carrying a *trailing* `//?` is kept out of the remapper entirely and emitted as plain source
            // text. Such a line changes shape between passes, and the remapper re-attaches comments to whatever
            // syntax node follows them, which moves or drops the prefix this pass relies on - inside a class body
            // that turned the line back into live code. Feeding the original line through leaves the directive
            // exactly where it was written.
            val trailingCaseLine = !trimmed.startsWith(kws.caseBranch) && line.indexOf(kws.caseBranch) >= 0
            val mapped = if (inBlockComment) {
                val endIdx = line.indexOf(kws.blockEnd)
                if (endIdx >= 0) {
                    inBlockComment = false
                    when {
                        active -> line.substring(0, endIdx).trimEnd()
                        // The pass that feeds the remapper must not hand it a block comment: the remapper attaches
                        // comments to the syntax node that follows them, so a multi-line comment in the middle of a
                        // class reattaches elsewhere and the code it used to hide comes back. Emit plain per-line
                        // comments instead and leave the block form to the final pass.
                        !finalPass -> line.indentation + kws.eval + " " + line.substring(0, endIdx).trim()
                        else -> line
                    }
                } else {
                    if (!active && !finalPass) {
                        line.indentation + kws.eval + " " + line.trim()
                    } else {
                        line
                    }
                }
            } else if (trimmed.startsWith(kws.blockStart)) {
                inBlockComment = true
                val after = trimmed.substring(kws.blockStart.length).trimStart()
                when {
                    active -> if (after.isEmpty()) "" else line.takeWhile { it == ' ' || it == '\t' } + after
                    !finalPass -> line.indentation + kws.eval + " " + after
                    else -> line
                }
            } else if (trimmed.startsWith(kws.define)) {
                // Already collected by collectDefinitions; the line is kept so a second pass still sees it.
                line
            } else if (trimmed.startsWith(kws.error) || trimmed.startsWith(kws.warn)) {
                val isError = trimmed.startsWith(kws.error)
                val prefix = if (isError) kws.error else kws.warn
                val message = trimmed.substring(prefix.length).trim().ifEmpty { "unsupported version" }
                if (active) {
                    if (isError) {
                        throw ParserException("$message in line $n of $fileName$varsHint")
                    }
                    System.err.println("$fileName:$n: $message$varsHint")
                }
                line
            } else if (trimmed.startsWith(kws.caseStart)) {
                var trailingCaseText = trimmed.substring(kws.caseStart.length).trim()
                caseOptional = if (trailingCaseText.startsWith(OPTIONAL)) {
                    trailingCaseText = trailingCaseText.substring(OPTIONAL.length).trim()
                    true
                } else {
                    false
                }
                if (trailingCaseText.isNotEmpty() && !trailingCaseText.startsWith("//")) {
                    throw ParserException("Unexpected content after ${kws.caseStart} in line $n of $fileName")
                }
                if (inCase) {
                    throw ParserException(
                        "Nested ${kws.caseStart} in line $n of $fileName (opened in line $caseLine)"
                    )
                }
                inCase = true
                caseLine = n
                caseMatched = false
                caseHadAnyBranch = false
                line
            } else if (trimmed.startsWith(kws.caseEnd)) {
                if (!inCase) {
                    throw ParserException("Unexpected ${kws.caseEnd} in line $n of $fileName")
                }
                // Only complain about a group where a `//?` alternative actually existed but none matched.
                // Groups that contain no branches at all, groups inside an inactive `//#if`, and groups marked
                // `optional` are fine.
                if (active && caseHadAnyBranch && !caseMatched && !caseOptional) {
                    throw ParserException(
                        "No branch in the ${kws.caseStart} block starting at line $caseLine matched " +
                                "before ${kws.caseEnd} in line $n of $fileName; " +
                                "add a `${kws.caseBranch}$ELSE_BRANCH` default branch, mark the group " +
                                "`${kws.caseStart} $OPTIONAL`, or fix the conditions"
                    )
                }
                inCase = false
                line
            } else if (trimmed.startsWith(kws.caseBranch)) {
                // Line-level `//?`. Inside a `//#case` group this is an alternative; outside one it is a
                // standalone single-line conditional. The first matching alternative of a group wins and the
                // remaining ones are skipped **without evaluating their conditions**, which also means
                // `//?t ...` at the end of a group acts as a default branch.
                if (inCase) caseHadAnyBranch = true
                if (inCase && caseMatched) {
                    // An earlier alternative already won. Skip without parsing, so conditions that reference
                    // variables only defined for other versions do not blow up.
                    line
                } else {
                    val directive = trimmed.substring(kws.caseBranch.length).trim()
                    if (directive == ELSE_BRANCH || directive.startsWith("$ELSE_BRANCH ")) {
                        // `//?else <content>`: the explicit default branch of a group, i.e. always taken.
                        if (!inCase) {
                            throw ParserException(
                                "${kws.caseBranch}$ELSE_BRANCH is only allowed inside a ${kws.caseStart} " +
                                    "block, but line $n of $fileName is outside one"
                            )
                        }
                        val content = directive.substring(ELSE_BRANCH.length).trim()
                        if (active) {
                            caseMatched = true
                            if (content.isEmpty()) "" else line.indentation + content
                        } else {
                            line
                        }
                    } else {
                        val split = splitConditionAndDirective(directive)
                            ?: throw ParserException(
                                "Expected `<condition> ? <content>` after ${kws.caseBranch} in line $n of $fileName"
                            )
                        val matches = try {
                            split.first.evalExpr()
                        } catch (e: Exception) {
                            throw ParserException("Invalid condition \"${split.first}\" in line $n of $fileName$varsHint")
                        }
                        if (matches && active) {
                            if (inCase) caseMatched = true
                            if (split.second.isEmpty()) "" else line.indentation + split.second
                        } else {
                            line
                        }
                    }
                }
            } else if (trimmed.startsWith(kws.ifdef) || trimmed.startsWith(kws.ifndef)) {
                // Must be checked before `kws.if`, since both `//#ifdef` and `//#ifndef` start with `//#if`.
                val negated = trimmed.startsWith(kws.ifndef)
                val prefix = if (negated) kws.ifndef else kws.ifdef
                val name = trimmed.substring(prefix.length).trim()
                if (name.isEmpty()) {
                    throw ParserException("Expected a variable name after $prefix in line $n of $fileName")
                }
                val result = vars.containsKey(name) != negated
                stack.push(IfStackEntry(result, n, elseFound = false, trueFound = result))
                indentStack.push(line.indentation)
                active = active && result
                line
            } else if (trimmed.startsWith(kws.`if`)) {
                val result = evalCondition(trimmed.substring(kws.`if`.length))
                stack.push(IfStackEntry(result, n, elseFound = false, trueFound = result))
                indentStack.push(line.indentation)
                active = active && result
                line
            } else if (trimmed.startsWith(kws.elseif) || trimmed.startsWith(kws.elif)) {
                val prefix = if (trimmed.startsWith(kws.elseif)) kws.elseif else kws.elif
                if (stack.isEmpty()) {
                    throw ParserException("Unexpected elseif in line $n of $fileName")
                }
                if (stack.last().elseFound) {
                    throw ParserException("Unexpected elseif after else in line $n of $fileName")
                }

                indentStack.pop()
                indentStack.push(line.indentation)

                active = if (stack.last().trueFound) {
                    val last = stack.pop()
                    stack.push(last.copy(currentValue = false))
                    false
                } else {
                    val result = evalCondition(trimmed.substring(prefix.length))
                    stack.pop()
                    stack.push(IfStackEntry(result, n, elseFound = false, trueFound = result))
                    stack.all { it.currentValue }
                }
                line
            } else if (trimmed.startsWith(kws.`else`)) {
                if (stack.isEmpty()) {
                    throw ParserException("Unexpected else in line $n of $fileName")
                }
                val trailing = trimmed.substring(kws.`else`.length).trimStart()
                if (trailing.isNotEmpty() && !trailing.startsWith("//")) {
                    val hint = if (trailing.startsWith("#")) " (did you mean `${kws.elseif}`?)" else ""
                    throw ParserException("Unexpected content after ${kws.`else`} in line $n of $fileName$hint")
                }
                val entry = stack.last()
                if (entry.elseFound) {
                    throw ParserException("Unexpected else after else in line $n of $fileName")
                }
                stack.pop()
                stack.push(IfStackEntry(!entry.trueFound, n, elseFound = true, trueFound = entry.trueFound))
                indentStack.pop()
                indentStack.push(line.indentation)
                active = stack.all { it.currentValue }
                line
            } else if (trimmed.startsWith(kws.endif)) {
                if (stack.isEmpty()) {
                    throw ParserException("Unexpected endif in line $n of $fileName")
                }
                stack.pop()
                indentStack.pop()
                active = stack.all { it.currentValue }
                line
            } else if (trimmed.startsWith(kws.disableRemap)) {
                if (!remapActive) {
                    throw ParserException("Remapping already disabled in line $n of $fileName")
                }
                remapActive = false
                line
            } else if (trimmed.startsWith(kws.enableRemap)) {
                if (remapActive) {
                    throw ParserException("Remapping not disabled in line $n of $fileName")
                }
                remapActive = true
                line
            } else {
                if (active) {
                    if (trimmed.startsWith(kws.eval)) {
                        line.replaceFirst((Pattern.quote(kws.eval) + " ?").toRegex(), "").let {
                            if (it.trim().isEmpty()) "" else it
                        }
                    } else if (remapActive && !trailingCaseLine) {
                        line
                    } else {
                        ignoreErrors = true
                        originalLine
                    }
                } else {
                    val currIndent = indentStack.last()
                    if (trimmed.isEmpty()) {
                        currIndent + kws.eval
                    } else if (!trimmed.startsWith(kws.eval) && currIndent.length <= line.indentation.length) {
                        ignoreErrors = true
                        currIndent + kws.eval + " " + originalLine.substring(currIndent.length)
                    } else {
                        line
                    }
                }
            }
            if (errors.isNotEmpty() && !ignoreErrors) {
                fail = true
                for (message in errors) {
                    System.err.println("$fileName:$n: $message")
                }
            }
            val swapAt = line.indexOf(kws.replace)
            // The trailing directive is resolved against `mapped`, i.e. against the line as this pass will emit
            // it. An earlier pass may already have prefixed the line with `eval`; resolving against `line` would
            // then stack a second prefix (or lose the directive altogether), so the two passes would never
            // converge on the same output.
            val trailingBase = if (trimmed.startsWith(kws.caseBranch)) "" else mapped
            val trailingCaseAt = trailingBase.indexOf(kws.caseBranch)

            val outLine = if (swapAt >= 0 && active) {
                val base = line.substring(0, swapAt)
                val directive = line.substring(swapAt + kws.replace.length).trim()
                val split = splitConditionAndDirective(directive)
                    ?: throw ParserException(
                        "Expected `<condition> ? <replacement>` after ${kws.replace} in line $n of $fileName"
                    )
                val matches = try {
                    split.first.evalExpr()
                } catch (e: Exception) {
                    throw ParserException("Invalid condition \"${split.first}\" in line $n of $fileName$varsHint")
                }
                if (matches) {
                    val indent = line.indentation
                    val replacement = split.second
                    when {
                        // An empty replacement deletes the whole line. It stays in place as an empty line, so the
                        // remapper's line-for-line view is unaffected and the file remains valid.
                        replacement.isEmpty() -> ""
                        base.trimStart().startsWith("import ") -> if (replacement.startsWith("import ")) {
                            indent + replacement.trimEnd()
                        } else {
                            indent + "import " + replacement.removeSuffix(";") + ";"
                        }
                        else -> indent + replacement
                    }
                } else {
                    base.trimEnd()
                }
            } else if (trailingCaseAt >= 0 && active) {
                val code = trailingBase.substring(0, trailingCaseAt)
                val condition = trailingBase.substring(trailingCaseAt + kws.caseBranch.length).trim()
                if (condition.isEmpty()) {
                    throw ParserException("Expected a condition after ${kws.caseBranch} in line $n of ${fileName}")
                }
                if (inCase) caseHadAnyBranch = true
                if (inCase && caseMatched) {
                    trailingBase
                } else {
                    val matches = try {
                        condition.evalExpr()
                    } catch (e: Exception) {
                        throw ParserException("Invalid condition \"$condition\" in line $n of $fileName$varsHint")
                    }
                    if (matches) {
                        if (inCase) caseMatched = true
                        code.trimEnd()
                    } else if (finalPass) {
                        // Prefix with `eval` but keep the directive itself, so the line reaches the same state on
                        // every pass: the next pass strips the prefix, lands here again and re-applies it, which
                        // makes this a fixed point. Dropping the directive would let the line come back as live
                        // code on that pass, and wrapping it in `/* */` does not survive either, because the
                        // remapper treats a standalone block comment as trivia.
                        val base = trailingBase
                        base.indentation + kws.eval + " " + base.substring(base.indentation.length)
                    } else {
                        trailingBase
                    }
                }
            } else {
                mapped
            }
            if (active) applyInlineCase(kws, outLine, fileName, n) else outLine
        }.also {
            if (stack.isNotEmpty()) {
                throw ParserException("Missing endif in line ${stack.last().lineno} of $fileName")
            }
            if (inCase) {
                throw ParserException("Missing ${kws.caseEnd} in line $caseLine of $fileName")
            }
            if (inBlockComment) {
                throw ParserException("Missing ${kws.blockEnd} in $fileName")
            }
        }
    }

    private fun applyInlineCase(kws: Keywords, line: String, fileName: String, n: Int): String {
        var result = line
        var marker = result.indexOf(kws.inlineCase)
        while (marker >= 0) {
            val codeStart = marker + kws.inlineCase.length
            var block = result.indexOf(kws.inlineBranch, codeStart)
            if (block < 0) {
                throw ParserException(
                    "Expected a ${kws.inlineBranch} block after ${kws.inlineCase} in line $n of $fileName"
                )
            }
            val code = result.substring(codeStart, block)
            var replacement: String? = null
            var tail = -1
            while (block >= 0) {
                val contentStart = block + kws.inlineBranch.length
                val contentEnd = result.indexOf(kws.inlineEnd, contentStart)
                if (contentEnd < 0) {
                    throw ParserException(
                        "Missing ${kws.inlineEnd} for ${kws.inlineBranch} in line $n of $fileName"
                    )
                }
                val content = result.substring(contentStart, contentEnd)
                val separator = content.indexOf('?')
                if (separator < 0) {
                    throw ParserException(
                        "Expected `<condition> ? <replacement>` inside ${kws.inlineBranch} in line $n of $fileName"
                    )
                }
                val condition = content.substring(0, separator).trim()
                if (condition.isEmpty()) {
                    throw ParserException(
                        "Expected a condition inside ${kws.inlineBranch} in line $n of $fileName"
                    )
                }
                val matches = try {
                    condition.evalExpr()
                } catch (e: Exception) {
                    if (replacement == null) {
                        throw ParserException("Invalid condition \"$condition\" in line $n of $fileName$varsHint")
                    }
                    false
                }
                if (matches) {
                    if (replacement == null) {
                        replacement = content.substring(separator + 1).trim()
                    } else {
                        System.err.println(
                            "$fileName:$n: unreachable alternative in ${kws.inlineCase}: " +
                                    "\"$condition\" also holds, but an earlier block already won " +
                                    "(conditions must be written in descending order)"
                        )
                    }
                }
                tail = contentEnd + kws.inlineEnd.length
                val nextBlock = result.indexOf(kws.inlineBranch, tail)
                val nextMarker = result.indexOf(kws.inlineCase, tail)
                block = if (nextBlock >= 0 && (nextMarker < 0 || nextBlock < nextMarker)) nextBlock else -1
            }
            val text = replacement?.let {
                val lead = code.takeWhile { c -> c.isWhitespace() }
                val trail = code.reversed().takeWhile { c -> c.isWhitespace() }.reversed()
                lead + it + trail
            } ?: code
            result = result.substring(0, marker) + text + result.substring(tail)
            marker = result.indexOf(kws.inlineCase, marker + text.length)
        }
        return result
    }

    /**
     * Splits `"<condition> ? <rest>"` at the first `?` whose left hand side is a usable condition. An empty
     * `<rest>` is allowed and means "delete the content".
     */
    private fun splitConditionAndDirective(text: String): Pair<String, String>? {
        var i = 0
        while (i < text.length) {
            if (text[i] == '?') {
                val condition = text.substring(0, i).trim()
                val rest = text.substring(i + 1).trim()
                if (condition.isNotEmpty()) {
                    val accepted = try {
                        condition.evalExpr()
                        true
                    } catch (e: Exception) {
                        false
                    }
                    if (accepted) {
                        return Pair(condition, rest)
                    }
                }
            }
            i++
        }
        return null
    }

    /**
     * Writes an intermediate artifact when the `PREPROCESS_DUMP_DIR` environment variable is set, so the text the
     * remapper received can be compared with the text it produced.
     */
    private fun dump(name: String, content: String) {
        val dir = System.getenv("PREPROCESS_DUMP_DIR") ?: return
        try {
            val file = java.io.File(dir, name)
            file.parentFile?.mkdirs()
            file.writeText(content)
        } catch (e: Exception) {
            System.err.println("preprocess: could not write dump '$name': ${e.message}")
        }
    }

    fun convertFile(kws: Keywords, inFile: File, outFile: File, remap: ((List<String>) -> List<Pair<String, List<String>>>)? = null) {
        val string = inFile.readText()
        var lines = string.lines()
        val remapped = remap?.invoke(lines) ?: lines.map { Pair(it, emptyList()) }
        dump(inFile.name + ".path.txt", inFile.absolutePath)
        dump(inFile.name + ".source.txt", string)
        dump(
            inFile.name + ".secondpass.txt",
            remapped.mapIndexed { index, pair -> "${index + 1}: ${pair.first}" }.joinToString("\n")
        )
        try {
            lines = convertSource(kws, lines, remapped, inFile.path)
        } catch (e: Throwable) {
            if (e is ParserException) {
                throw e
            }
            throw RuntimeException("Failed to convert file $inFile", e)
        }
        outFile.parentFile.mkdirs()
        outFile.writeText(lines.joinToString("\n"))
    }

    data class IfStackEntry(
        var currentValue: Boolean,
        var lineno: Int,
        var elseFound: Boolean = false,
        var trueFound: Boolean = false
    )

    class InvalidExpressionException(expr: String) : RuntimeException(expr)

    class ParserException(str: String) : RuntimeException(str)
}

private fun <E> MutableList<E>.push(e: E) = add(e)
private fun <E> MutableList<E>.pop() = removeLast()