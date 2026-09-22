Preprocessor
============

A [JCP](https://github.com/raydac/java-comment-preprocessor)-inspired preprocessor, used to support multiple
Minecraft versions.

This repository is based on [ReplayMod/preprocessor](https://github.com/ReplayMod/preprocessor) and
[Fallen-Breath/preprocessor](https://github.com/Fallen-Breath/preprocessor).

中文文档：[README.md](README.md)

## Modifications

- Automatic tab indentation support
- `mainProjectFile` and `mainProjectFileRel` for more flexible subproject layout
  ```groovy
  // in root project
  preprocess {
    // base dir: root project directory. Use mainProjectFileRel if not provided
    mainProjectFile = "versions/mainProject"
    // base dir: subproject directory. Default: "../mainProject"
    mainProjectFileRel = "../../mainProject"
  }
  ```
- Use the node of the current core project (defined in the `mainProject` file) as the root node of the graph, so
  less compilation work when switching to and compiling a subproject. Note: this does not work for tasks of the
  root project
- Line number hint for the "Missing endif" error
- Improved error message when using an undefined variable in a `//#if` expression
- Improved srg mapping mode detection when using architectury loom
- Use a custom [remap](https://github.com/ReplayMod/remap) fork: https://github.com/liuyuexiaoyu1/remap
  - Less useless warning messages
  - Message logging of remap's kotlin compiler message collector is disabled by default. Re-enable it with
    `preprocess { enableRemapMessageCollector = true }`
- Gradle 9 configuration cache compatibility
  - Task fields no longer hold a `Configuration` or a `Project`, both of which made the configuration cache fail
    while storing the task state
  - The cross-project classpath of `preprocessCode` no longer triggers the "resolution of another project's
    configuration without an exclusive lock" error
- Allow declaring the preprocess graph in `settings.gradle(.kts)`
  ([#26](https://github.com/ReplayMod/preprocessor/issues/26))
  ```kotlin
  // settings.gradle.kts
  plugins { id("com.replaymod.preprocess") version "<version>" }

  preprocess {
      val mc11802 = createNode("1.18.2", 1_18_02, "")
      val mc11900 = createNode("1.19", 1_19_00, "")
      mc11900.link(mc11802, null)
      for (node in getNodes()) {
          include(":${node.project}")
          project(":${node.project}").apply { projectDir = file("versions/${node.project}") }
      }
  }
  ```
  The graph is shared with the projects, so an existing `build.gradle(.kts)` setup keeps working unchanged
- Be strict about `//#else` ([#13](https://github.com/ReplayMod/preprocessor/issues/13))
  - Content after `//#else` (such as the `//#elseif` typo `//#else#if MC>=12000`) is rejected, and so is a second
    `//#else` for the same `//#if`
- Condition errors list the current variable values, e.g.
  `Invalid condition "MC >= 12105" in line 12 of Foo.java (vars: MC=12105, FABRIC=1)`
- Added directives and condition forms, documented below: `//#replace`, `//#case`, `//?`, `//?else`, `/*#case*/`,
  `/*$$ ... $$*/`, `//#define`, `//#error`, `//#warn`, `//#ifndef`, ranges `X in A..B`, sets `X in [A, B]`,
  `not in`, `defined(X)` and bare conditions with the primary variable omitted

## Basic usage

```java
        //#if MC>=11200
        // This is the block for MC >= 1.12.0
        category.addDetail(name, callable::call);
        //#else
        //$$ // This is the block for MC < 1.12.0
        //$$ category.setDetail(name, callable::call);
        //#endif
```

Any comments starting with `//$$` will automatically be introduced / removed based on the surrounding
condition(s). Normal comments are left untouched. The `//#else` branch is optional.

Conditions can be nested arbitrarily but their indention shall always be equal to the indention of the code at
the `//#if` line. The `//$$` shall be aligned with the inner-most `//#if`.

```java
    //#if MC>=10904
    public CPacketResourcePackStatus makeStatusPacket(String hash, Action action) {
        //#if MC>=11002
        return new CPacketResourcePackStatus(action);
        //#else
        //$$ return new CPacketResourcePackStatus(hash, action);
        //#endif
    }
    //#else
    //$$ public C19PacketResourcePackStatus makeStatusPacket(String hash, Action action) {
    //$$     return new C19PacketResourcePackStatus(hash, action);
    //$$ }
    //#endif
```

Code for the more recent MC version shall be placed in the first branch of the if-else-construct.

`//#ifdef` and `//#ifndef` branch on whether a name is defined in `vars`:

```java
//#ifdef FABRIC
//#ifndef NEOFORGE
```

## Conditions

- Comparisons and logic: `== != >= <= > <`, `&&`, `||`, `!`, parentheses
- Version literals: `12105`, `1.21.5` and the underscore form `121_05` all denote the same value
- Ranges: `X in A..B`, inclusive on the low end and exclusive on the high end, both bounds accept
  dot-separated literals
  ```java
  //#if MC in 12005..12110
  ```
- Sets: `X in [A, B, C]`
  ```java
  //#if MC in [11904, 12001, 12105]
  ```
- Negation: `not in`, for both the range and the set form
  ```java
  //#if MC not in 12005..12110
  //#if MC not in [11900, 12110]
  ```
- Omitted variable: when a condition starts with an operator or a digit the variable name may be left out. The
  "primary variable" is `MC` when present in `vars`, the only variable when there is exactly one, and bare
  conditions are an error otherwise
  ```java
  //#if >= 1.21.5              // same as MC >= 1.21.5
  //?1.21.5 ? same()           // contains a dot, same as MC == 1.21.5
  //?1.21.2..1.21.6 ? ranged() // same as MC in 1.21.2..1.21.6
  ```
  A bare integer is not read as a version: `//#if 0` stays "always false" instead of becoming `MC == 0`
- `defined(X)`: tests whether `X` exists in `vars` or as a `//#define`
  ```java
  //#if defined(FABRIC) && MC >= 12001
  ```
  It is resolved during expansion, so its argument is not replaced by a `//#define` alias

## Condition aliases: `//#define`

```java
//#define NEW_API MC >= 12102
//#define NEW_BOTH NEW_API && FABRIC

//#if NEW_BOTH
//?NEW_API ? Orientation orientation,
//#replace NEW_API ? Orientation orientation,
//#endif
```

Aliases are file-scoped and collected before anything is processed, so one may be used before its definition.
They may refer to each other; substitutions are parenthesized so precedence is preserved. A duplicate definition
with a different condition is an error. An alias takes precedence over a `vars` entry of the same name.

## Conditional imports

Version-dependent import statements shall be placed separately from and after all other imports but before the
`static` and `java.*` imports, written in the import section with a leading `//?`:

```java
//?MC >= 12110 ? import fi.dy.masa.malilib.render.InventoryOverlayContext;
```

When the condition holds the line becomes a real import statement, otherwise it stays a comment. Unlike a
directive hidden inside a conditional block, this line is part of the import section itself, so its position and
ordering are in your hands.

## Trailing replacement: `//#replace`

Rewrites its own line, with the condition as part of a trailing comment:

```java
super.neighborChanged(blockState, level, blockPos, block, blockPos2, bl); //#replace >= 1.21.2 ? super.neighborChanged(blockState, level, blockPos, block, orientation, bl);
```

When the condition holds the whole line becomes what follows the `?`; otherwise the part before the `?` is kept
and the directive is dropped. A line starting with `import` gets `import ` and a trailing `;` added for you.
An empty replacement deletes the line, keeping its position as an empty line so the line count stays stable.

The line as written is always real code, and both the condition and the replacement live in a comment, so IDEs
parse and navigate it normally and the line count never changes. The commented-out form keeps the directive with
it, which makes the result stable under repeated processing:

```text
condition does not hold -> //$$ super.neighborChanged(...blockPos2...); //#replace >= 1.21.2 ? ...
```

## Line alternatives: `//#case`

```java
//#case
//?MC >= 12111 ? int x = 3;
//?MC >= 12110 ? int x = 2;
//?else ? int x = 1;
//#endcase
```

- `//?<condition> ? <content>`: when it holds the prefix is removed and the line becomes real code, otherwise the
  line stays a comment
- `//?else ? <content>`: an explicit default branch, always taken, and it satisfies the must-match check
- `//#case optional`: allows the group to match no branch at all
- By default a group that has branches but matches none is an error
- `//?` is only allowed between `//#case` and `//#endcase`
- `//#case` and `//#endcase` remain in the output as comments, because preprocessing runs twice

The trailing form keeps a line only while a condition holds:

```java
null, //?> 1.20.1
```

## Inline alternatives: `/*#case*/`

Rewrites the code that follows the marker, anywhere in a line:

```java
register(/*#case*/ Old.class /*?MC >= 12111 ? New.class *//*?MC >= 12110 ? Mid.class */);
```

Conditions are written in descending order, the first one that holds wins, the blocks after it are not evaluated,
and when none holds the baseline is kept. The baseline is real code (IDEs resolve and highlight it), the
directives are block comments (IDEs ignore them), and the line count never changes. When a skipped block would
also have held, a warning is printed but the build continues.

## Multi-line comment blocks: `/*$$ ... $$*/`

```java
//#if >= 1.21.11
/*$$
int a = 1;
int b = 2;
$$*/
//#endif
```

Lines inside such a block do not need the `//$$` prefix. While the branch is active both marker lines become
empty lines and the content becomes real code; while it is inactive the whole block is an ordinary Java block
comment.

## Build-time diagnostics: `//#error` and `//#warn`

```java
//#if >= 1.21.5
//#error MC 1.21.5 changed this signature, see issue #42
//#endif
```

Both only fire while the branch they are in is active. `//#error` throws and fails the build, `//#warn` only
prints to stderr.

## Source location

The source code resides in `src/main` (gradle project determined by `versions/mainVersion` e.g. with `11404`
it'll be `:1.14.4`) and is automatically passed through the preprocessor when any of the other versions are built
(gradle projects `:1.8`, `:1.8.9`, etc.).
Do **NOT** edit any of the code in `versions/$MCVERSION/build/` as it is automatically generated and will be
overwritten without warning.

You can pass the original source code through the preprocessor if you wish to develop/debug with another version
of Minecraft:

```bash
./gradle :1.9.4:setCoreVersion # switches all sources in src/main to 1.9.4
```

Make sure to switch back to the most recent branch before committing!
Care should also be taken that switching to a different branch and back doesn't introduce any uncommitted changes
(e.g. due to different indention, especially in case of nested conditions).

The `replaymod_at.cfg` file uses the same preprocessor but with different keywords (see already existent examples
in that file).
If required, more file extensions and keywords can be added to the implementation.

## Per-version files

If entire files are very version specific, they may be overwritten for any version by placing a new file with the
same package and name in `versions/$MCVERSION/src/main/java` (or the respective source set / language folder).
If such a file is present, the overwritten file will no longer be derived from another version and any downstream
versions will be derived from the new file instead.
This also has the huge advantage that the file may be edited with full IDE support because it is actually part of
the respective version's Gradle project.

This feature is fully compatible with `setCoreVersion` and overwrite files will be moved generated/removed as
required such that switching back and forth leaves the same result as you started out with.
The core project itself does not allow for overwrites and any present in its folder will be deleted on
`setCoreVersion`.

## Patterns

The preprocessor also supports defining simple "search and replace"-like patterns (but smarter in that they are
type-aware) annotated by a `@Pattern` annotation in one or more central places which then are applied all over
the code base.
This allows code which would previously have to be written with preprocessor statements or as
`MCVer.getWindow(mc)` all over the code base to instead now use the much more intuitive `mc.getWindow()` and be
automatically converted to `mc.window` (or even a Window stub object) on remap if a pattern for that exists
anywhere in the same source tree:

```java
    @Pattern
    private static Window getWindow(MinecraftClient mc) {
        //#if MC>=11500
        return mc.getWindow();
        //#elseif MC>=11400
        //$$ return mc.window;
        //#else
        //$$ return new com.replaymod.core.versions.Window(mc);
        //#endif
    }
```

All pattern cases should be a single line as to not mess with indentation and/or line count.
Any arguments passed to the pattern must be used in the pattern in the same order in every case (introducing
in-line locals to work around that is fine).
Defining and/or applying patterns in/on Kotlin code is not yet supported.

To use this feature, you must create a `Pattern` (name may be different) annotation in your mod:

```java
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.METHOD)
public @interface Pattern {
}
```

and then declare it in your `build.gradle`:

```groovy
preprocess {
    patternAnnotation.set("com.replaymod.gradle.remap.Pattern")
}
```

## License

The Preprocessor is provided under the terms of the GNU General Public License Version 3 or (at your option) any
later version.
See `LICENSE.md` for the full license text.
