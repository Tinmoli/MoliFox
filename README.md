# MoliFox · 茉狐

面向 **Windows / Linux x64** 的 Minecraft Java 版桌面启动器，使用 **Kotlin + Compose Multiplatform** 开发。

茉狐以游戏实例、内容管理和启动配置为核心，采用统一的樱花粉界面，支持字体、背景、透明度与磨砂效果设置。布局与操作遵循同类启动器的通用习惯，代码与视觉素材独立实现。

**当前版本：0.4.0-beta（测试版）。** 当前交付 **Windows x86_64** 与 **Linux x86_64**；macOS、Android、ARM 及其他 CPU 架构暂不交付。本文状态核对日期为 **2026-11-01**，详细实测边界见 [验证记录](docs/VALIDATION.md)。

## 快速开始

### Windows

1. 将 `MoliFox-0.4.0-beta-windows-x86_64.exe` 放在可写入的文件夹，例如自己的游戏目录（文件名带版本与平台，双击可运行）。
2. 双击运行。启动器优先寻找合适的系统 Java；没有时会先询问是否联网准备 Java 21，选“否”则退出并提示自行安装 Java 17 或更高版本。
3. 在“开始”页添加微软账户或离线账户。
4. 在“下载 → 原版”安装游戏，或在“实例”页选择已有游戏目录。
5. 选择实例并启动。有任务时右下角显示进度入口，点击打开任务中心；这里只显示当前任务，结束后五秒自动收起。取消安装会回收本次独占下载和新版本目录，已有/实际共用文件保留。运行输出和错误在“设置 → 日志”查看。游戏运行时可以安装其他版本，也可以再次启动同一实例或另一个实例。

整合包也可以直接把 `.mrpack` 或支持的整合包 `.zip` 拖入窗口，检查预览与可选内容后点击“开始导入”；原文件保留。

### Linux x64

需要图形桌面，以及相应的字体、OpenGL/Mesa 和 X11 / XWayland 环境：

```sh
chmod +x MoliFox-0.4.0-beta-linux-x86_64.run
./MoliFox-0.4.0-beta-linux-x86_64.run
```

入口使用 `sha256sum` 校验文件；如果需要首次下载 Java，还需要 `curl` 或 `wget` 及 `tar`。文件、目录与保存位置使用桌面系统窗口：GNOME/GTK 需要 `zenity`，KDE 优先 `kdialog`。不同发行版的图形依赖可能不同，当前没有完整的发行版兼容性承诺。

### 首次启动需要联网吗？

采用小体积单文件分发，**首次启动需要联网下载并校验界面依赖**，准备好并缓存后可离线打开。优先使用兼容的系统 Java 17 或更高版本；缺少时联网准备 Java 21。不需要 Node.js 或浏览器内核，运行数据默认保存在程序旁的 MoliFox 文件夹。

缺少合适的 Java 时，首次准备 Java 需要联网。安装尚不存在的游戏、补齐文件、搜索下载内容、微软授权与刷新也需要联网。打开启动器、拥有完整游戏文件、完成正版认证是不同的条件。

### 多人联机（EasyTier + Scaffolding）

1. 房主：进入游戏并打开存档，按 `Esc` 选择“对局域网开放”；然后在启动器“联机 → 联机大厅”点“创建房间”，把生成的 21 字符邀请码发给朋友。
2. 房客：在“联机 → 联机大厅”输入邀请码并加入，启动器会建立虚拟网络并把房主的 Minecraft 端口转发到本机；随后进入“多人游戏”，局域网列表里就会出现房主的世界。
3. 首次使用会先要求阅读并同意联机说明，然后自动下载 EasyTier 2.6.4 核心（约 31 MB）并校验 SHA-256；之后复用，不随启动器分发。下载一直卡住时，也可以自己下载官方 zip 后在“联机 → 联机设置”里从本地导入。
4. 优先 P2P 直连，失败后自动尝试内置的自建中继（`xg-easytier.chior.top:11010`，可在设置里换成自己的节点）。房主退出存档或点“关闭房间”后房间自动关闭；退出启动器会按“广播 → 本机转发 → Scaffolding → EasyTier”顺序回收进程。

联机核心 EasyTier 由第三方提供、独立进程运行，采用 LGPL-3.0 许可；房间创建与局域网发现依赖实际打开的游戏存档。网络名使用 `MoliFox-` 前缀，因此与 PCL CE / HMCL 的联机房间刻意不互通（不使用管理员权限、不安装 TUN 驱动）。

## 功能与当前状态

“已有实现”表示代码和入口已接入，不代表所有版本、加载器组合与平台都已实测。

| 范围 | 已有实现 | 当前边界 |
| --- | --- | --- |
| 游戏实例 | 原版安装与修复、已有目录原地导入、多目录切换、搜索、排序、收藏、备注 | 外部目录直接使用原文件，导入不等于复制 |
| 加载器 | Fabric、Quilt、Forge、NeoForge 安装；外部及整合包实例可更换组件，变更前备份、失败恢复和上一选择回退 | 代表版本组合仍待补验；自定义补丁与模组不保证跨加载器兼容 |
| Java 与启动 | 系统 Java 检测、按需下载、兼容选择、自动/手动内存与实例策略、窗口、全屏、JVM / 游戏参数、环境变量 | 自动内存在启动时按可用内存计算，启动器 Java 与游戏 Java 分开选择 |
| 高级启动 | 全局默认与实例覆盖、只读参数预览、前后命令、取消与超时、启动器窗口生命周期 | 新窗口行为的 Linux 实测待补 |
| 账户与皮肤 | 开始页微软/离线账户、真实头像、皮肤库/正背面预览/PNG 导入导出、正版上传确认、离线本地预览 | 正版上传接口只以模拟服务验证；完整三维、离线游戏内自定义和正版进游戏闭环仍待补 |
| 多人联机 | EasyTier 2.6.4 核心按需下载与 SHA-256 校验、21 字符邀请码、房主/房客会话、局域网世界发现与广播、P2P 优先 + 自建中继回退、NAT 类型诊断 | 真实两机联机、真实 Minecraft 进房、关闭启动器后 EasyTier 是否确实结束仍未实测；与 PCL CE / HMCL 刻意不互通；核心以 LGPL-3.0 独立进程运行 |
| Modrinth 内容 | 搜索、详情、兼容版本、发布通道、必需及可选依赖、事务安装 | 不兼容时停止安装；不同资源类别的能力并不相同 |
| 本地内容 | 模组导入、启停、移除、声明读取；哈希识别、批量检查更新与版本锁定；JarInJar 只读诊断（不安装、不写 `mods/`） | 未识别不等于已经最新；更新 GUI 全流程待收尾 |
| 整合包 | 多格式导入、预览、校验、隔离、失败回滚、升级预览、自有 ZIP 与标准 mrpack 导出向导 | 特殊组件、CurseForge 包升级、新格式真实游戏样本仍有边界，见格式文档和最新交接 |
| 网络与日志 | 官方源 / BMCLAPI、下载并发、系统 / 直连 / 手动代理、游戏日志与诊断导出 | 下载镜像与内容站点分别设置 |
| 个性化 | 浅色 / 深色 / 系统主题、主题色、背景、字体、缩放、玻璃与页面选项 | 整窗透明需重启；磨砂作用于启动器自己的背景层 |
| CurseForge | 检索、详情/版本、依赖、指纹更新和 manifest 包核心及桌面来源接线 | 公开镜像无需 API Key；真实镜像服务已探测可用，完整下载闭环仍待验收 |
| 实例与存档维护 | 实例复制/整理/校验、目录移动、存档备份恢复与跨实例复制 | Windows 关键操作有实际点击证据，Linux 桌面实测仍待补 |
| 下载恢复 | 大文件分段、网络中断续传、重试与校验、显式取消清理 | 共享目录修改会排队；跨进程锁及无哈希资源的版本变化识别仍有限制 |
| 实例图标与截图 | 图片/自绘预设/自动图标、图库/日期/导出/回收、图片剪贴板与设背景 | 关键用例和 Windows 定向 GUI 通过，Linux 本批未实机 |

### 页面入口

- **开始**：账户操作、选择实例、启动游戏与常用操作。
- **实例**：游戏目录和版本管理；详情包含“设置 / 模组 / 资源包 / 光影 / 存档 / 原理图”六个页签（原理图页签只在已有 `schematics/` 目录时出现），以及文件夹和维护入口。
- **下载**：原版、整合包、模组、资源包、光影、数据包、世界七分类。数据包需要明确目标存档；世界下载会校验 `level.dat` 并安装到实例 `saves/`，同名不覆盖。
- **设置**：全局游戏设置、Java 管理、通用、外观、下载、日志、关于七分区。

设置按界面提示保存，外观即时应用。Java、内存和高级启动配置支持实例覆盖；隔离只由全局开关控制。开启后使用 versions/实例名 保存模组、存档和配置，关闭后使用游戏根目录。手动选择或自动发现后选中的目录就是后续安装目标；已提交任务保留提交时目标，不因切换目录而改变。

## 数据存放与便携使用

默认结构如下。选择外部游戏目录或自定义下载缓存后，对应内容保存在所选位置。

```text
启动文件所在目录/
├─ MoliFox-0.4.0-beta-windows-x86_64.exe   Windows 启动文件
├─ MoliFox-0.4.0-beta-linux-x86_64.run     Linux 启动文件，按需选择其一
├─ .minecraft/                默认游戏根目录
│  ├─ versions/               游戏版本与启动配置
│  ├─ libraries/              游戏依赖
│  ├─ assets/                 游戏资源
└─ MoliFox/                   启动器数据
   ├─ config/launcher.json    设置、账户资料及实例记录
   ├─ dependencies/           启动器入口、原生库、版本化界面依赖
   ├─ runtimes/               按需下载的 Java
   ├─ appearance/             backgrounds / fonts / music / themes
   ├─ skins/                  本地皮肤库
   ├─ icons/                  用户及整合包图标
   ├─ backups/                配置快照、整合包原档、实例与服务器列表备份
   ├─ cache/                  downloads / metadata / skins / tmp
   ├─ logs/                   启动、运行日志
   └─ trash/                  系统回收站不可用时的保留目录
```

程序所在目录需要可写。外部游戏目录直接使用原位置，不自动复制进 `MoliFox/`；移除外部目录记录不会删除原文件。

当前启动器配置格式为 2：旧配置里的实例数组会一次性迁移并立刻写回（只为磁盘上仍存在的游戏目录保留设置），之后配置不再写实例列表；旧目录结构与旧文件不再自动迁移，也不会替用户删除旧文件，现有游戏可重新选择目录扫描。当前格式的损坏配置仍保留备份恢复。原生启动器退出后清理本次 `cache/tmp/session-*`，依赖与 Java 保留复用。目录与清理规则见 [HANDOFF](docs/HANDOFF.md)。

备份或移动前先退出启动器与游戏，同时保留 `MoliFox/`、默认 `.minecraft/` 及自行选择的外部目录。配置含绝对路径，跨位置或跨系统移动后，可能需要重新选择游戏目录、Java、背景和自定义缓存。登录凭据保存在系统用户目录，不随便携目录移动。

### 微软账户与凭据

Microsoft Client ID 在构建时注入（环境变量 `MOLIFOX_MS_CLIENT_ID` / `MICROSOFT_CLIENT_ID` 或本机 `local.properties`），仓库不含明文 ID；取不到时为空串，用户仍可在设置里填写自定义 UUID。普通用户无需填写，也不需要在桌面程序中放置应用密钥。用户于 2026-10-27 提供了 Mojang API 许可名单获批邮件。

微软密码只在微软网页输入，不经过启动器。只持久保存刷新令牌，运行时换取访问凭据；玩家名、UUID、皮肤地址等普通资料保存在配置中。

刷新令牌不再跟随便携目录，保存在系统用户目录：Windows 为 `%LOCALAPPDATA%\MoliFox\credentials\`，用 DPAPI 保护的用户密钥做 AES-GCM 加密，只有当前 Windows 用户能解封；其他平台为 `$XDG_DATA_HOME/molifox/credentials`（默认 `~/.local/share/molifox/credentials`），按用户决定不加密，同用户进程可读。旧 Windows DPAPI / Linux Secret Service 凭据仍会先读取并迁移，成功写入新位置后才清旧项；更早的便携凭据文件不再解析，需要重新登录。详见 [凭据保存说明](docs/CREDENTIAL_STORAGE.md)。

`config/launcher.json` 不保存微软令牌，也不保存 CurseForge API Key；CurseForge 只使用可在设置中修改的公共镜像地址。

## 验证进度与已知限制

最近一次回归（2026-11-01 午）：`:launcher-core:test` **712 项**（1 项跳过）、`:multiplayer-core:jvmTest` **153 项**、`:desktop-app:test` **15 项**，0 失败；`detekt` / `ktlintCheck` / `checkI18n` 全仓 **0 issues**；`portableWindows`、`portableLinux`、`verifyDesktopPackages` 通过。发行包输出到 `dist/`：Windows **14,265,344 字节**（SHA-256 `d3c89421…04008f3`），Linux **12,709,744 字节**（SHA-256 `fdd0b94b…cecbd116`）。本轮**没有重跑 GUI 冒烟**：`uiSmoke` / `multiplayerUiSmoke` 的最近记录早于当前界面改动，不能当作当前版本已通过。真实存档联机闭环、两机互通、微软正版登录全流程、当前发行包的点击启动与自动更新替换仍未验收。详见 [验证记录](docs/VALIDATION.md)。

下表是历史验证记录，部分证据早于当前版本（例如“Windows 启动器”一行来自旧的首启方案）；当前版本本轮实际重跑的是单元测试、编译、打包与包校验，见上方段落。

| 验证项 | 已记录结果 |
| --- | --- |
| Windows 启动器 | 无界面缓存、网络不可达条件下首次开窗通过；最近发行 EXE 的独立开窗检查通过 |
| Windows 原版 | Minecraft 1.20.1 实际进入主菜单并正常退出 |
| Windows Fabric | 游戏发现成功；本工具执行身份下的 ZIPFS 只读问题仍待普通桌面环境验收 |
| Linux 游戏 | WSLg / Mesa 软件渲染下，1.20.1 原版与 Fabric 0.16.14 有主菜单证据；Fabric 自动关闭和退出码通过 |
| Linux 启动文件 | 已在 WSL Arch + Xvfb 软件渲染下运行 `.run`：首页、联机页、EasyTier 核心在线下载与启动通过；未覆盖发行版桌面与 Linux 真实游戏 |
| 微软真实服务 | 旧 DPAPI 存储下，网页授权、角色读取、重建控制器后刷新、本地移除通过；未用该会话启动游戏 |
| 新便携凭据 | 读写、迁移、损坏拒绝和模拟跨平台复制通过；新存储方式在线刷新及 Linux Secret Service 迁移待验收 |
| Modrinth 实际内容 | Sodium 历史版本、识别、更新、锁定与禁用状态保留通过，未执行下载的模组 |
| 完整 GUI 回归 | 最近一轮自动化 GUI 冒烟未复跑（本轮按约定不重跑）；此前记录的 `uiSmoke` 结果来自旧界面版本，不能代表当前版本 |

主要待办：真实存档联机闭环与两机互通、Linux 发行版桌面与真实游戏验收、微软正版登录真实全流程、当前发行包的点击启动与自动更新替换。接手顺序与边界见 [NEXT_AI_HANDOFF](docs/NEXT_AI_HANDOFF.md)，不宣称当前已经覆盖完整功能。

## 从源码开发

### 环境

- **JDK 25**：当前构建工具使用，产物字节码目标为 Java 17。
- **Gradle 9.7.0**：仓库提供 Wrapper，首次构建需要下载工具与依赖。
- **Windows 原生入口**：MinGW-w64 的 `g++` 和 `windres`，通过 `MOLIFOX_MINGW_BIN` 指定目录或加入 `PATH`。
- **Linux**：POSIX Shell；运行界面还需要本机桌面依赖。

Wrapper 默认将 Gradle 依赖放在项目 `.dev/gradle-home/`，临时文件放在 `.dev/work/build-tmp/`。自行调用已安装的 Gradle 时，也应显式配置这些位置，避免产生项目外缓存。

**当前维护机**使用已安装的 Gradle 9.7.0；具体路径与可复用命令见 [HANDOFF](docs/HANDOFF.md)。这不代表所有机器的 Wrapper 下载均已验证。

### 常用命令

在项目根目录执行。Windows PowerShell：

```powershell
# 从源码打开界面
.\gradlew.bat :desktop-app:run --no-daemon --console=plain

# 核心回归
.\gradlew.bat :launcher-core:test --no-daemon --console=plain

# Windows 单文件构建
.\gradlew.bat :desktop-app:portableWindows --no-daemon --console=plain

# 构建并校验 Windows / Linux 两个分发包
.\gradlew.bat :desktop-app:verifyDesktopPackages --no-daemon --console=plain

# Windows GUI 全流程与单文件开窗检查，按顺序单独运行
.\gradlew.bat :desktop-app:uiSmoke --no-daemon --console=plain
.\gradlew.bat :desktop-app:nativeSmoke --no-daemon --console=plain

# 清理被强制中断而遗留的测试数据
.\gradlew.bat cleanTestData --no-daemon --console=plain
```

Linux：

```sh
chmod +x gradlew
./gradlew :launcher-core:test --no-daemon --console=plain
./gradlew :desktop-app:run --no-daemon --console=plain
./gradlew :desktop-app:portableLinux --no-daemon --console=plain
```

产物位于根目录，文件名带版本与平台：`MoliFox-<版本>-windows-x86_64.exe` 与 `MoliFox-<版本>-linux-x86_64.run`，SHA-256 摘要在 `build/validation/`。已有产物不代表包含当前未验证的源码改动。

`:launcher-core:gameSmoke` 和 `:launcher-core:accountSmoke` 是需要桌面或用户参与的真实游戏与授权验收，不属于普通单元测试；执行方法及结果记录在交接和验证文档中。

### 代码结构与测试约定

| 目录 | 职责 |
| --- | --- |
| `desktop-app/` | Compose 桌面界面、页面操作与桌面分发构建 |
| `launcher-core/` | 实例、Java、安装与启动、账户、网络、内容和整合包 |
| `multiplayer-core/` | EasyTier 2.6.4 安装/进程/CLI、Scaffolding 协议、局域网世界发现与广播、本机端口转发与房间会话（common 接口 + JVM 实现） |
| `launcher-bootstrap/` | 单文件入口、Java 准备、依赖清单和首次联网下载校验 |
| `tests/` | 全部测试、GUI 冒烟检查与验证工具（按模块分目录：`tests/<模块>/<source set>/`，工具在 `tests/tools/`），模块内不再保留 `src/test` |
| `tests/tools/` | GUI 探针、冒烟脚本、打包检查与平台验证工具 |
| `dist/` | `portableWindows` / `portableLinux` 生成的发行包（不提交） |
| `.dev/` | 本地构建缓存与测试工具数据：`gradle-home/`、`work/`、`tooling/`（不提交） |
| `docs/` | 交接、参考研究、路线图、验证及清理记录 |

类型与 Compose 函数使用大驼峰，普通函数、变量和参数使用小驼峰。新增设置必须连接实际行为，文件操作需保留失败、取消与回滚边界。

测试统一放在 `tests/`（源码按模块分目录，临时数据在 `tests/.tmp/`），成功或失败后自动清理，不删除用户的 `MoliFox/`、游戏或外部目录。可复用依赖保留在项目内。GUI 测试顺序运行，只操作测试窗口；界面截图最多保留一套，不保留第三方截图或缓存源码，也不提交到公开仓库。

## 文档索引

| 文档 | 内容 |
| --- | --- |
| [NEXT_AI_HANDOFF](docs/NEXT_AI_HANDOFF.md) | 接手入口：先看这个，再看下面几份 |
| [HANDOFF](docs/HANDOFF.md) | 当前状态、模块与依赖、构建/测试/打包命令、行为边界 |
| [VALIDATION](docs/VALIDATION.md) | 最近一次实测证据与未验证内容 |
| [REFERENCE_RESEARCH](docs/REFERENCE_RESEARCH.md) | 官方资料、参考与独立实现边界 |
| [PACK_FORMATS](docs/PACK_FORMATS.md) | 整合包导入格式、组件兼容和迁移范围 |
| [STORAGE_LAYOUT](docs/STORAGE_LAYOUT.md) | 数据目录结构与便携说明 |
| [CREDENTIAL_STORAGE](docs/CREDENTIAL_STORAGE.md) | 凭据格式、迁移与便携边界 |
| [CHANGELOG](docs/CHANGELOG.md) | 版本变更记录 |
| [AGENTS](AGENTS.md) | 项目工作约定 |

## 许可 / License 与参考

MoliFox 以 GNU 通用公共许可证第 3 版（GPL-3.0-only）发布，仅适用第 3 版、不适用任何后续版本；完整条款见 [LICENSE](LICENSE)，随包署名与第 7(b)/(c) 节附加条款见 [NOTICE](assets/legal/NOTICE)。

Copyright (C) 2026 Tinmoli and MoliFox Contributors。源代码在本仓库中公开：[Tinmoli/MoliFox](https://github.com/Tinmoli/MoliFox)，发布版本与问题反馈也在该仓库。

第三方依赖及其许可见 [THIRD_PARTY](assets/legal/THIRD_PARTY.md)；Minecraft、游戏资源与作者作品保持各自归属。

界面布局、操作习惯与包格式遵循同类产品的通用做法，独立实现；未复制任何第三方源码、图标、截图或宣传素材作为茉狐的产品资源。
