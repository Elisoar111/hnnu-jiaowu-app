# Windows 中文路径导致单元测试全挂的根因与修复

日期：2026-09-20。验证目标：v1.2.2 开发期，`D:\测试\zhengfang-apk-main`（项目路径含中文）。

## 现象

本机 `:app:testDebugUnitTest` 55 个测试类全部失败，且失败方式完全一致：

```
XXXTest > initializationError FAILED
    java.lang.ClassNotFoundException: com.hnnujw.course.academic.XXXTest
        at jdk.internal.loader.BuiltinClassLoader.loadClass
        at java.lang.Class.forName0
        at org.gradle.api.internal.tasks.testing.junit.JUnitTestClassExecutor.runTestClass
```

连测试类自身都加载不到，说明不是测试逻辑问题，而是测试 worker 拿到的**类路径本身是坏的**。

## 排查过程（哪些是排除项）

1. 类文件确实存在：`app/build/tmp/kotlin-classes/debugUnitTest/.../CourseNameKitTest.class`，`javap` 能正常解析。
2. Gradle 探针确认 `testClassesDirs` 与 `classpath` 均包含该目录。
3. 与配置缓存无关：`--no-configuration-cache` 一样失败。
4. 抓测试 worker 启动命令行（`--info`），发现类路径是通过 **argfile** 传的：
   `@C:\Users\44516\.gradle\.tmp\gradle-worker-classpath<N>.txt`。
5. 反查该 argfile 的字节：项目路径写成 `D:\\` + `e6 b5 8b e8 af 95` + `\\`，即 **UTF-8 编码的“测试”**。
6. 写一个探针类，把它从 `java.class.path` 里拿到的字符串按**码点**打印到文件（避开控制台编码干扰）：

```
[1] rawExists=false  normalizedExists=false
    codepoints = D:\\\u5a34\u5b2d\u762f\\zhengfang-apk-main\\app\\build\\...
```

`\u5a34\u5b2d\u762f` = **娴嬭瘯** —— 正是“测试”的 UTF-8 字节被按 **GBK** 解码的结果。
同一进程里直接构造 `new File("D:\\测试\\...")` 则 `exists=true`：目录没问题，纯粹是字符串被错读。

## 根因

| 环节 | 实际行为 |
| --- | --- |
| Gradle 写 argfile | `org.gradle.internal.process.ArgWriter$4` 里是 `new java.io.PrintWriter(java.io.File)` —— 无字符集构造函数，走 `Charset.defaultCharset()`；本项目 `gradle.properties` 里写了 `-Dfile.encoding=UTF-8`，于是 argfile 是 UTF-8 |
| JDK 启动器读 argfile | `java.exe` 展开 `@argfile` 时用**系统 ANSI 码页**（简中 Windows = GBK/ms936），本机 `sun.jnu.encoding=native.encoding=GBK` |
| 结果 | 两侧编码不一致 → 类路径里的中文目录全部变成乱码路径 → worker 找不到任何测试类 |

对照实验（本机 JDK 21）也证明了这一点：

| 传参方式 | 类路径里的“测试” |
| --- | --- |
| 命令行直传（不经 argfile） | 正确 |
| argfile 用 **GBK** 字节写 | 正确 |
| argfile 用 **UTF-8** 字节写 | 乱码 `娴嬭瘯` |
| 加 `-Dfile.encoding=UTF-8` / `-Dsun.jnu.encoding=UTF-8` | 都救不了（在 `@argfile` 之后生效已经太晚，且后者无法被覆盖） |

注意：**只影响经 argfile 传递的类路径**。worker 的其它参数（如 `-Dorg.gradle.internal.worker.tmpdir`、`--add-opens`）走命令行，java 用宽字符 API 传给子进程，中文路径是正常的——所以只有测试类加载坏掉，其余构建一切正常。

## 修复

`gradle.properties`：

```properties
org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=COMPAT -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8
kotlin.daemon.jvmargs=-Xmx2g -Dfile.encoding=UTF-8
```

* `file.encoding=COMPAT` = 跟随本机系统编码，让 Gradle 写 argfile 与启动器读 argfile **自动用同一套编码**。路径全 ASCII 的机器上同样正确（写 CP1252、读 CP1252），不必按机器硬编码 GBK。
* `stdout/stderr.encoding=UTF-8` 保持日志输出不乱。
* Kotlin 编译跑在独立 daemon（`kotlin.daemon.jvmargs`）里，仍然钉死 UTF-8，保证源码里的中文字面量不受 daemon 默认字符集影响。

`app/build.gradle` 顺带显式声明 javac 源码编码，防止 Java 源码受 daemon 默认字符集影响：

```groovy
compileOptions {
    sourceCompatibility JavaVersion.VERSION_17
    targetCompatibility JavaVersion.VERSION_17
    encoding "UTF-8"
}
```

**不要**把 `-Dfile.encoding=UTF-8` 写回去——那正是本 bug 的触发器。

## 验证

* `:app:testDebugUnitTest`：55 个测试类、**269 个用例全部通过**（2 项条件跳过，0 失败）。269 与源码里 `@Test` 标注数量（269）一致，用例没有被静默丢弃。
* 改动后用 `--rerun` 强制重编译 Kotlin 主源码，再扫描 1107 个 `.class` 里的中文字面量（`综测附加分`/`单项封顶`/`取消下载`/`今日课程` 等），命中的 UTF-8 字节数与改动前**完全一致**，证明源码编码未受影响。
* `:app:assembleDebug --rerun` 全量重跑通过。

## 副作用（已知、可接受）

构建脚本里那行 `println "📦 当前版本: ..."` 的 emoji 会退化成 `?`（GBK 无法表示 4 字节 emoji），中文部分正常。脚本里的中文注释是否被正确读取不影响构建逻辑；构建脚本中没有任何带中文的 `resValue`/`buildConfigField`，不存在资源被写坏的风险。
