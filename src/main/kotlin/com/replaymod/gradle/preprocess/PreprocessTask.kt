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
    val ifdef: String,
    val elseif: String,
    val elif: String,
    val `else`: String,
    val endif: String,
    val eval: String,
    val swapwhen: String = "//#swapwhen",
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
            elseif = "//#elseif",
            elif = "//#elif",
            `else` = "//#else",
            endif = "//#endif",
            eval = "//$$",
            swapwhen = "//#swapwhen",
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
            elseif = "##elseif",
            elif = "##elif",
            `else` = "##else",
            endif = "##endif",
            eval = "#$$",
            swapwhen = "##swapwhen",
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
        private val EXPR_PATTERN = Pattern.compile("(.+)(==|!=|<=|>=|<|>)(.+)")
        // `X in A..B` where A and B are version-like literals or variable names. The low bound is inclusive,
        // the high bound is exclusive.
        private val RANGE_PATTERN = Pattern.compile("""(.+?)\s+in\s+(.+?)\.\.(.+)""")
    }

    var fail = false

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

    internal fun String.evalExpr(): Boolean = ExprParser(this).parse()

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
            val low = rangeMatcher.group(2).trim().evalVar()
            val high = rangeMatcher.group(3).trim().evalVar()
            return lhs >= low && lhs < high
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

    fun convertSource(
        kws: Keywords,
        lines: List<String>,
        remapped: List<Pair<String, List<String>>>,
        fileName: String,
    ): List<String> {
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
        // Whether the current `//#case` group contains at least one `//?` alternative. Used to distinguish
        // "no branch matched" (a user error worth reporting) from "the group has no branches at all" (fine).
        var caseHadAnyBranch = false

        fun evalCondition(condition: String): Boolean {
            if (!condition.startsWith(" "))
                throw ParserException("Expected space before condition in line $n of $fileName")
            try {
                return condition.trim().evalExpr()
            } catch (e: InvalidExpressionException) {
                throw ParserException("Invalid expression \"${e.message}\" in line $n of $fileName")
            }
        }

        return lines.zip(remapped).map { (originalLine, lineMapped) ->
            val (line, errors) = lineMapped
            var ignoreErrors = false
            n++
            val trimmed = line.trim()
            val mapped = if (inBlockComment) {
                val endIdx = line.indexOf(kws.blockEnd)
                if (endIdx >= 0) {
                    inBlockComment = false
                    if (active) line.substring(0, endIdx).trimEnd() else line
                } else {
                    line
                }
            } else if (trimmed.startsWith(kws.blockStart)) {
                inBlockComment = true
                if (active) {
                    val after = trimmed.substring(kws.blockStart.length).trimStart()
                    if (after.isEmpty()) "" else line.takeWhile { it == ' ' || it == '\t' } + after
                } else {
                    line
                }
            } else if (trimmed.startsWith(kws.caseStart)) {
                if (trimmed.length > kws.caseStart.length
                    && !trimmed.substring(kws.caseStart.length).startsWith("//")) {
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
                // Groups that contain no branches at all, and groups inside an inactive `//#if`, are fine.
                if (active && caseHadAnyBranch && !caseMatched) {
                    throw ParserException(
                        "No branch in the ${kws.caseStart} block starting at line $caseLine matched " +
                                "before ${kws.caseEnd} in line $n of $fileName; " +
                                "add a `${kws.caseBranch}t` default branch or fix the conditions"
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
                    val split = splitConditionAndDirective(directive)
                        ?: throw ParserException(
                            "Expected `<condition> <content>` after ${kws.caseBranch} in line $n of $fileName"
                        )
                    val matches = try {
                        split.first.evalExpr()
                    } catch (e: Exception) {
                        throw ParserException("Invalid condition \"${split.first}\" in line $n of $fileName")
                    }
                    if (matches && active) {
                        if (inCase) caseMatched = true
                        line.takeWhile { it == ' ' || it == '\t' } + split.second
                    } else {
                        line
                    }
                }
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
            } else if (trimmed.startsWith(kws.ifdef)) {
                val result = vars.containsKey(trimmed.substring(kws.ifdef.length))
                stack.push(IfStackEntry(result, n, elseFound = false, trueFound = result))
                indentStack.push(line.indentation)
                active = active && result
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
                    } else if (remapActive) {
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
            val swapAt = line.indexOf(kws.swapwhen)
            val trailingCaseAt = if (trimmed.startsWith(kws.caseBranch)) -1 else line.indexOf(kws.caseBranch)

            val outLine = if (swapAt >= 0 && active) {
                val base = line.substring(0, swapAt)
                val directive = line.substring(swapAt + kws.swapwhen.length).trim()
                val split = splitConditionAndDirective(directive)
                    ?: throw ParserException(
                        "Expected `<condition> <replacement>` after ${kws.swapwhen} in line $n of $fileName"
                    )
                val matches = try {
                    split.first.evalExpr()
                } catch (e: Exception) {
                    throw ParserException("Invalid condition \"${split.first}\" in line $n of $fileName")
                }
                if (matches) {
                    val indent = line.takeWhile { it == ' ' || it == '\t' }
                    val replacement = split.second
                    if (base.trimStart().startsWith("import ")) {
                        if (replacement.startsWith("import ")) {
                            indent + replacement.trimEnd()
                        } else {
                            indent + "import " + replacement.removeSuffix(";") + ";"
                        }
                    } else {
                        indent + replacement
                    }
                } else {
                    base.trimEnd()
                }
            } else if (trailingCaseAt >= 0 && active) {
                val code = line.substring(0, trailingCaseAt)
                val condition = line.substring(trailingCaseAt + kws.caseBranch.length).trim()
                if (condition.isEmpty()) {
                    throw ParserException("Expected a condition after ${kws.caseBranch} in line $n of ${fileName}")
                }
                if (inCase) caseHadAnyBranch = true
                if (inCase && caseMatched) {
                    line
                } else {
                    val matches = try {
                        condition.evalExpr()
                    } catch (e: Exception) {
                        throw ParserException("Invalid condition \"$condition\" in line $n of $fileName")
                    }
                    if (matches) {
                        if (inCase) caseMatched = true
                        code.trimEnd()
                    } else {
                        val indent = line.takeWhile { it == ' ' || it == '\t' }
                        indent + kws.eval + " " + code.trimStart()
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
                        throw ParserException("Invalid condition \"$condition\" in line $n of $fileName")
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

    private fun splitConditionAndDirective(text: String): Pair<String, String>? {
        var i = 0
        while (i < text.length) {
            if (text[i] == '?') {
                val condition = text.substring(0, i).trim()
                val rest = text.substring(i + 1).trim()
                if (condition.isNotEmpty() && rest.isNotEmpty()) {
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

    fun convertFile(kws: Keywords, inFile: File, outFile: File, remap: ((List<String>) -> List<Pair<String, List<String>>>)? = null) {
        val string = inFile.readText()
        var lines = string.lines()
        val remapped = remap?.invoke(lines) ?: lines.map { Pair(it, emptyList()) }
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