预处理器
========

为支持多个 Minecraft 版本而使用的、受 [JCP](https://github.com/raydac/java-comment-preprocessor) 启发的预处理器。

本仓库基于 [ReplayMod/preprocessor](https://github.com/ReplayMod/preprocessor) 与
[Fallen-Breath/preprocessor](https://github.com/Fallen-Breath/preprocessor)。

English documentation: [README_en.md](README_en.md)

## 改动列表

- 支持自动 tab 缩进
- 新增 `mainProjectFile` 与 `mainProjectFileRel`，子项目布局更灵活
  ```groovy
  // 在根项目中
  preprocess {
    // 基准目录：根项目目录。未提供时使用 mainProjectFileRel
    mainProjectFile = "versions/mainProject"
    // 基准目录：子项目目录。默认值： "../mainProject"
    mainProjectFileRel = "../../mainProject"
  }
  ```
- 以当前 core 项目（在 `mainProject` 文件中定义）的节点作为图的根节点，切换并编译子项目时减少编译量。
  注意：对根项目的任务不生效
- "Missing endif" 报错附带行号提示
- `//#if` 中使用未定义变量时给出更清晰的报错
- 使用 architectury loom 时改进 srg 映射模式的探测
- 使用自定义的 [remap](https://github.com/ReplayMod/remap) 分支：https://github.com/liuyuexiaoyu1/remap
  - 减少无意义的警告信息
  - 默认关闭 remap 的 kotlin 编译器消息收集。可通过
    `preprocess { enableRemapMessageCollector = true }` 重新开启
- 兼容 Gradle 9 的配置缓存
  - 任务字段不再持有 `Configuration` 或 `Project`，它们都会让任务状态存储阶段的配置缓存失败
  - `preprocessCode` 的跨项目 classpath 不再触发"未持有独占锁就解析另一个项目的配置"错误
- 允许在 `settings.gradle(.kts)` 中声明预处理图
  （[#26](https://github.com/ReplayMod/preprocessor/issues/26)）
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
  图会与各个项目共享，因此已有的 `build.gradle(.kts)` 配置无需改动即可继续工作
- 严格化 `//#else`（[#13](https://github.com/ReplayMod/preprocessor/issues/13)）
  - `//#else` 之后的内容（例如把 `//#elseif` 误写成 `//#else#if MC>=12000`）会被拒绝，
    同一个 `//#if` 出现第二个 `//#else` 同样会被拒绝
- 条件表达式报错时附带当前变量取值，例如
  `Invalid condition "MC >= 12105" in line 12 of Foo.java (vars: MC=12105, FABRIC=1)`
- 新增以下指令与条件写法，详见下文：`//#replace`、`//#case`、`//?`、`//?else`、
  `/*#case*/`、`/*$$ ... $$*/`、`//#define`、`//#error`、`//#warn`、`//#ifndef`、
  区间 `X in A..B`、集合 `X in [A, B]`、`not in`、`defined(X)`、省略主变量的裸条件

## 基本用法

```java
        //#if MC>=11200
        // 这是 MC >= 1.12.0 的代码块
        category.addDetail(name, callable::call);
        //#else
        //$$ // 这是 MC < 1.12.0 的代码块
        //$$ category.setDetail(name, callable::call);
        //#endif
```

任何以 `//$$` 开头的注释都会根据外围条件自动启用或禁用。普通注释保持原样。
`//#else` 分支是可选的。

条件可以任意嵌套，但缩进必须与 `//#if` 所在行的代码缩进一致。
`//$$` 需要与最内层的 `//#if` 对齐。

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

较新 MC 版本的代码应放在 if-else 结构的第一个分支中。

`//#ifdef` 与 `//#ifndef` 按变量名是否在 `vars` 中定义来判断分支：

```java
//#ifdef FABRIC
//#ifndef NEOFORGE
```

## 条件表达式

- 比较与逻辑运算：`== != >= <= > <`、`&&`、`||`、`!`、括号
- 版本字面量：`12105`、`1.21.5` 与带下划线的 `121_05` 都表示同一个值
- 区间：`X in A..B`，下界包含、上界不包含，两端都接受点分字面量
  ```java
  //#if MC in 12005..12110
  ```
- 集合：`X in [A, B, C]`
  ```java
  //#if MC in [11904, 12001, 12105]
  ```
- 取反：`not in`，区间与集合形式都支持
  ```java
  //#if MC not in 12005..12110
  //#if MC not in [11900, 12110]
  ```
- 省略主变量：条件以运算符或数字开头时，变量名可以省去。"主变量"取 `vars` 中的 `MC`；
  只有一个变量时取该变量；否则裸条件会报错
  ```java
  //#if >= 1.21.5              // 等价于 MC >= 1.21.5
  //?1.21.5 ? same()           // 含点号，等价于 MC == 1.21.5
  //?1.21.2..1.21.6 ? ranged() // 等价于 MC in 1.21.2..1.21.6
  ```
  裸整数不作版本解释：`//#if 0` 仍然是"恒假"，不会变成 `MC == 0`
- `defined(X)`：判断 `X` 是否存在于 `vars` 或 `//#define`
  ```java
  //#if defined(FABRIC) && MC >= 12001
  ```
  `defined(X)` 在展开阶段就求值，因此其中的 `X` 不会被 `//#define` 别名替换掉

## 条件别名 `//#define`

```java
//#define NEW_API MC >= 12102
//#define NEW_BOTH NEW_API && FABRIC

//#if NEW_BOTH
//?NEW_API ? Orientation orientation,
//#replace NEW_API ? Orientation orientation,
//#endif
```

别名是文件级的，并且会先扫描全文再处理，因此可以写在定义之前。别名之间可以互相引用，
展开时自动加括号以保持优先级。重复定义且条件不同会报错。与 `vars` 同名时别名优先。

## 条件导入

依赖版本的 import 语句应独立于其它 import 之后、`static` 与 `java.*` 导入之前，
用行首 `//?` 写在导入段里：

```java
//?MC >= 12110 ? import fi.dy.masa.malilib.render.InventoryOverlayContext;
```

条件成立时该行成为真正的 import 语句，不成立时保持注释。相比写在条件块里的指令形式，
这种写法本身就是导入段的一部分，位置与顺序都由你掌握。

## 行尾替换 `//#replace`

重写自身所在行，条件是行尾注释的一部分：

```java
super.neighborChanged(blockState, level, blockPos, block, blockPos2, bl); //#replace >= 1.21.2 ? super.neighborChanged(blockState, level, blockPos, block, orientation, bl);
```

条件成立时整行替换为 `?` 之后的内容，不成立时保留 `?` 之前的原行并去掉指令。
`import` 开头的行会自动补上 `import ` 与结尾的 `;`。
替换内容留空表示删除该行（位置保留为空行，行数不变）。

写下来的那一行始终是真实代码，条件与替换在注释里，因此 IDE 能正常解析与跳转，
文件行数也不变。被注释的形态会连同指令一起保留，使得重复处理的结果稳定：

```text
条件不成立时 -> //$$ super.neighborChanged(...blockPos2...); //#replace >= 1.21.2 ? ...
```

## 整行候选组 `//#case`

```java
//#case
//?MC >= 12111 ? int x = 3;
//?MC >= 12110 ? int x = 2;
//?else ? int x = 1;
//#endcase
```

- `//?<条件> ? <内容>`：条件成立时去掉前缀变为真代码，不成立时整行保持注释
- `//?else ? <内容>`：显式默认分支，总是生效，同时满足"必须有分支命中"的检查
- `//#case optional`：允许整组一个分支都不命中
- 默认情况下，组内有分支但全不命中会报错
- `//?` 只能在 `//#case` 与 `//#endcase` 之间使用
- `//#case` 与 `//#endcase` 在产物中保留为注释，因为预处理会跑两趟

行尾形式用于"这一行只在条件成立时保留"：

```java
null, //?> 1.20.1
```

## 行内候选组 `/*#case*/`

在一行内的任意位置重写紧跟在标记之后的代码：

```java
register(/*#case*/ Old.class /*?MC >= 12111 ? New.class *//*?MC >= 12110 ? Mid.class */);
```

条件按降序书写，首个成立者生效，命中之后不再评估后续块，全部不成立时保留基线代码。
基线是真实代码（IDE 可以解析与跳转），指令是块注释（IDE 忽略），行数不变。
候选顺序写反（被跳过的块其实也成立）时会输出警告但不中断构建。

## 多行注释块 `/*$$ ... $$*/`

```java
//#if >= 1.21.11
/*$$
int a = 1;
int b = 2;
$$*/
//#endif
```

块内每行不需要 `//$$` 前缀。分支生效时两个标记行变为空行、块内成为真实代码；
分支不生效时整块就是一个普通的 Java 块注释。

## 构建期诊断 `//#error` 与 `//#warn`

```java
//#if >= 1.21.5
//#error MC 1.21.5 changed this signature, see issue #42
//#endif
```

两者只在其所在分支生效时触发。`//#error` 抛出异常中断构建，`//#warn` 仅打印到 stderr。

## 源码位置

源代码位于 `src/main`（具体 Gradle 项目由 `versions/mainVersion` 决定，例如 `11404` 对应 `:1.14.4`），
在构建其它版本时（Gradle 项目 `:1.8`、`:1.8.9` 等）会自动经过预处理器。
**不要**编辑 `versions/$MCVERSION/build/` 下的任何代码，它们是自动生成的，会被无提示地覆盖。

如果你想用另一个 Minecraft 版本开发或调试，可以把原始源码过一遍预处理器：

```bash
./gradle :1.9.4:setCoreVersion # 把 src/main 下的所有源码切换到 1.9.4
```

提交之前记得切回最新的分支。
切换到另一个分支再切回来时，要注意不要引入未提交的改动（例如由于缩进不同，嵌套条件尤其容易出问题）。

`replaymod_at.cfg` 文件使用同一个预处理器，但关键字不同（参见该文件中已有的例子）。
如有需要，可以在实现中加入更多的文件扩展名与关键字。

## 按版本覆盖的文件

如果整个文件都是版本相关的，可以在 `versions/$MCVERSION/src/main/java`（或对应的源集与语言目录）
放置同包同名的文件来为某个版本覆盖它。
一旦存在这样的文件，被覆盖的文件不再由其它版本派生，而下游版本将改为派生自这个新文件。
这还有个很大的好处：该文件是相应版本 Gradle 项目的一部分，因此可以用完整的 IDE 支持来编辑。

该特性与 `setCoreVersion` 完全兼容，覆盖文件会被按需生成或删除，来回切换的结果与初始状态一致。
core 项目本身不允许覆盖，其目录中若存在覆盖文件，会在 `setCoreVersion` 时被删除。

## Patterns

预处理器还支持定义简单的"查找与替换"式 pattern（更聪明的地方在于它是类型感知的），
通过 `@Pattern` 注解在一处或多处中心位置声明，然后应用到整个代码库。
这让原本必须写成预处理语句、或到处写成 `MCVer.getWindow(mc)` 的代码，现在可以直接写
`mc.getWindow()`，并在重映射时（若同一源码树中存在对应 pattern）自动转换为
`mc.window`（甚至是一个 Window 桩对象）：

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

所有 pattern 分支都应为单行，以免打乱缩进或行数。
传给 pattern 的参数在每个分支中必须按相同顺序使用（用行内局部变量绕开这一限制也可以）。
暂不支持在 Kotlin 代码中定义或应用 pattern。

要使用该特性，需要在你的模组中创建一个 `Pattern` 注解（名字可以不同）：

```java
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.METHOD)
public @interface Pattern {
}
```

然后在 `build.gradle` 中声明它：

```groovy
preprocess {
    patternAnnotation.set("com.replaymod.gradle.remap.Pattern")
}
```

## 许可证

本预处理器按 GNU 通用公共许可证第 3 版或其后续版本（由你选择）的条款提供。
完整许可证文本见 `LICENSE.md`。
