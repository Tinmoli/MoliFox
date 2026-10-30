# 第三方组件许可与我们的实际义务

本文只列**我们确实分发或确实会下载**的组件，依据是各模块真实的 `build.gradle.kts`、`desktop-app` 的运行时清单生成逻辑，以及 `launcher-core` 的 Java 运行时下载代码。仅用于构建/测试的依赖单列在最后一节，不随产品分发。

- 版本取自 `build.gradle.kts`、`gradle` 依赖缓存中实际解析到的版本，以及 `desktop-app/build.gradle.kts` 生成的 `molifox-runtime.json` 清单条目。
- 「对我们的实际义务」只写我们真实需要做的事，不复制上游全文；完整条款在各组件仓库的 `LICENSE` 中。
- 我们的分发方式：单文件 `MoliFox.exe` / `MoliFox-linux-x64.run` 内含启动器自身代码、Kotlin 运行期与依赖清单；其余 jar 在**首次启动时从 Maven 中央仓库下载并按 SHA-256 校验**，jar 内保留上游 `META-INF` 许可声明；Java 运行时由用户按需从 Azul 或 Adoptium 官方源下载，解压后 `legal/` 目录原样保留。

## 1. 随产品分发或首次启动下载的组件

| 名称 | 版本 | 许可 | 对我们的实际义务 | 链接 |
| --- | --- | --- | --- | --- |
| Kotlin 标准库（`kotlin-stdlib`） | 2.4.21 | Apache-2.0 | 保留版权与许可声明，随分发附带 NOTICE（若有）；不得暗示 JetBrains 为我们的修改背书。 | <https://github.com/JetBrains/kotlin> |
| kotlinx.coroutines（`kotlinx-coroutines-core`、`kotlinx-coroutines-swing`） | 1.10.2 | Apache-2.0 | 同上：保留声明与 NOTICE；我们未修改其源码，无需额外标注修改。 | <https://github.com/Kotlin/kotlinx.coroutines> |
| Compose Multiplatform（runtime / ui / foundation / material3） | 1.12.2（material3 解析为 1.9.0） | Apache-2.0 | 保留声明；不注册或使用 JetBrains/Google 的商标；修改其源码时才需标注修改。 | <https://github.com/JetBrains/compose-multiplatform> |
| Material Icons Extended（`material-icons-extended-desktop`） | 1.7.3 | Apache-2.0 | 只把源码实际引用到的少量图标类打进 jar（其余 37 MB 不分发），仍须保留声明。 | <https://github.com/google/material-design-icons> |
| Skiko（`skiko-awt` 及 `skiko-awt-runtime-windows-x64` / `skiko-awt-runtime-linux-x64`） | 0.150.2 | Apache-2.0（Skiko）；其内置 Skia 二进制为 BSD-3-Clause | 保留 Skiko 的 Apache-2.0 声明，**并保留 Skia 的 BSD-3 版权声明、条款与免责声明**；不得用 Skia 作者名义为我们的产品背书。原生库只在首次启动时按平台下载一个。 | <https://github.com/JetBrains/skiko> · <https://skia.org/> |
| OkHttp | 4.12.0 | Apache-2.0 | 保留声明与 NOTICE；不修改时无额外义务。 | <https://github.com/square/okhttp> |
| Okio（OkHttp 的传递依赖） | 3.6.0 | Apache-2.0 | 同上；由 OkHttp 带入，我们未单独修改。 | <https://github.com/square/okio> |
| Gson | 2.13.2 | Apache-2.0 | 保留声明；用于解析版本清单、账户与运行时索引 JSON（确实在用，非遗留依赖）。 | <https://github.com/google/gson> |
| Apache Commons Compress | 1.28.0 | Apache-2.0 | 保留声明与 NOTICE；用于解压 tar.gz 形式的 Java 运行时。 | <https://commons.apache.org/proper/commons-compress/> |
| Apache Commons IO / Codec / Lang3（Commons Compress 的传递依赖） | IO 2.20.0 · Codec 1.19.0 · Lang3 3.18.0 | Apache-2.0 | 保留声明与 NOTICE；我们未直接调用，随 Compress 传递。 | <https://commons.apache.org/> |
| TomlJ | 1.1.1 | Apache-2.0 | 保留声明；用于读取 `mods.toml` 等 Toml 元数据。 | <https://github.com/tomlj/tomlj> |
| ANTLR4 Runtime（TomlJ 的传递依赖） | 4.11.1 | BSD-3-Clause | 保留版权声明、条款与免责声明；不得以 ANTLR 项目名义背书。 | <https://github.com/antlr/antlr4> |
| JNA / JNA Platform | 5.17.0 | Apache-2.0 **或** LGPL-2.1-or-later（双许可，我们选 Apache-2.0） | 按 Apache-2.0 履行：保留声明与 NOTICE。选此分支可避免 LGPL 的替换/重链接要求。 | <https://github.com/java-native-access/jna> |
| miniz（原生启动器内嵌 ZIP 读取） | 3.1.1 | MIT | 在二进制分发中保留其版权声明与许可文本；我们已经把 `launcher-bootstrap/native/miniz/LICENSE` 打进 jar 的 `META-INF/molifox/miniz/`。 | <https://github.com/richgel999/miniz> |
| Azul Zulu JDK/JRE（按需下载） | 由用户选择的 Java 主版本决定，安装时取厂商最新 GA 构建 | GPLv2 **附 Classpath Exception** | 分发未修改的运行时须附 GPLv2 全文与 Classpath Exception 文本，且不得移除其 `legal/` 目录；我们只下载、校验、解压，不修改运行时本身，因此不触发 GPLv2 的源程序义务。 | <https://www.azul.com/downloads/> |
| Eclipse Temurin（Adoptium，按需下载，Zulu 不可用时的回退） | 同上 | GPLv2 **附 Classpath Exception** | 同 Zulu：保留 `legal/` 与许可文本，不修改运行时即无需提供其源程序（上游本就公开）。 | <https://adoptium.net/> |
| MinGW-w64 运行期（静态链入 `MoliFox.exe`） | 随构建工具链 | GCC Runtime Library Exception 3.1（libgcc/libstdc++ 部分） | `-static` 把 GCC 运行库链进 exe；该例外允许这样做，不使我们的程序受 GPLv3 约束，但分发时仍须保留例外文本与版权声明。 | <https://www.gnu.org/licenses/gcc-exception-3.1.html> |

## 2. Classpath Exception 到底改变了什么（普通话解释）

GPLv2 本身对「链接」的态度很严：如果我们的程序与 GPLv2 代码结合成一个作品，整个作品就可能都要按 GPLv2 发布。Java 程序的常态恰恰是「大量引用 JDK 的类」——没有这个例外，任何闭源或不同许可证的 Java 程序都没法合法跑在 OpenJDK 上。

Classpath Exception（CPE）就是为此加的**附加许可**（GPLv2 版的 §7 式例外）：

- **它允许**：把 JDK 的类库与独立程序「链接」（编译期引用、运行期 classpath 加载），而不要求该独立程序也按 GPLv2 发布。
- **它不改变**：JDK 自身仍是 GPLv2+CPE。你若**修改**了运行时（改 class 库、改 JVM），修改部分仍须按 GPLv2 提供源程序。
- **它只覆盖**「独立程序 + 类库」这种结合方式，不覆盖把 JDK 代码**复制进**我们源码的情形。

对我们的具体结论：

1. 我们下载、解压、原样使用 Zulu/Temurin，不修改其文件 → 只需保留许可文本与 `legal/` 目录，不需要发布它们的源码（上游已公开）。
2. 我们的 Kotlin 代码 `import java.*`、通过 classpath 启动游戏 → 属于 CPE 覆盖的链接方式，我们的代码**不因此变成 GPLv2 作品**。
3. 游戏本身（Minecraft）与我们无关，也不通过 CPE 获得任何许可。
4. 注意区分：**我们的项目代码是 GPLv3**（见根目录 `LICENSE`），这是我们的选择，不是 CPE 或 JDK 的要求。

## 3. 只用于构建或测试、不随产品分发的依赖

这些出现在 `build.gradle.kts` 里，但不会进入 `MoliFox.exe` / `.run` 或运行时下载清单：

| 名称 | 版本 | 许可 | 说明 |
| --- | --- | --- | --- |
| Gradle Wrapper | 见 `gradle/wrapper/` | Apache-2.0 | 仅构建工具。 |
| JUnit Jupiter / JUnit Platform | 5.13.4 | EPL-2.0 | 仅测试。 |
| OkHttp MockWebServer / okhttp-tls | 4.12.0 | Apache-2.0 | 仅测试。 |
| `kotlin("test")` | 随 Kotlin 2.4.21 | Apache-2.0 | 仅测试。 |

## 4. 我们如何履行这些义务（现状核对）

| 义务 | 履行方式 | 位置 |
| --- | --- | --- |
| 附带 GPLv3 全文与附加条款 | 打包进 jar 的 `META-INF/molifox/` | 根目录 `LICENSE`、`NOTICE` |
| 第三方组件清单与许可 | 打包 `THIRD_PARTY.md`，并在本文给出中文对照 | 根目录 `THIRD_PARTY.md`、本文 |
| Apache-2.0 的声明与 NOTICE | 依赖 jar 原样携带 `META-INF` 声明；首次启动下载的 jar 保留各自 `META-INF` | 运行时下载目录 |
| Skia 的 BSD-3 声明 | 随 Skiko 原生包内的许可文件保留；本文单独列明 | Skiko 下载包 |
| miniz 的 MIT 声明 | 显式打入 jar | `META-INF/molifox/miniz/LICENSE` |
| Java 运行时的 GPLv2+CPE 文本 | 解压后 `legal/` 目录原样保留 | `MoliFox/runtimes/<vendor>-<major>-<os>-<arch>/legal/` |

## 5. 已知的不足（如实记录）

1. Skia 的 BSD-3 声明目前**依赖 Skiko 原生包自带的许可文件**，我们没有在「关于」界面单独展示它；若做许可界面，应把 Skia 的 BSD-3 文本一并展示。
2. Zulu 与 Temurin 的 `legal/` 目录保留在安装目录，但没有在界面上提供「查看许可」入口。
3. 版本号随依赖升级会变；升级依赖时应同步更新本表，版本以 `build.gradle.kts` 与运行时清单为准，而不是以本表为准。

---

相关文件：[README-zh.md](README-zh.md) · [GPL-3.0-zh.md](GPL-3.0-zh.md) · [ADDITIONAL-TERM-HMCL-zh.md](ADDITIONAL-TERM-HMCL-zh.md)