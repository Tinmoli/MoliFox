# MoliFox · 茉狐

面向 **Windows / Linux x64** 的 Minecraft Java 版桌面启动器，使用 **Kotlin + Compose Multiplatform** 开发。

茉狐以游戏实例、内容管理和启动配置为核心，采用统一的樱花粉界面，支持字体、背景、透明度与磨砂效果设置。布局与操作参考 PCL、HMCL、SJMCL、Prism Launcher 和 Modrinth App，代码与视觉素材独立实现。

**当前版本：0.4.0-beta（测试版）。** 项目仍在开发中；macOS、Android 及其他 CPU 架构暂不交付。本文状态核对日期为 **2026-10-29**，详细实测边界见 [验证记录](docs/VALIDATION.md)。

## 快速开始

### Windows

1. 将 `MoliFox.exe` 放在可写入的文件夹，例如自己的游戏目录。
2. 双击运行。启动器优先寻找合适的系统 Java；没有时会联网准备启动器所需的 Java 21。
3. 在“开始”页添加微软账户或离线账户。
4. 在“下载 → 原版”安装游戏，或在“实例”页选择已有游戏目录。
5. 选择实例并启动。有任务时右下角显示进度入口，点击打开任务中心；运行输出和错误在“设置 → 日志”查看。游戏运行时可以安装其他版本，也可以再次启动同一实例或另一个实例。

整合包也可以直接把 `.mrpack` 或支持的整合包 `.zip` 拖入窗口，检查预览与可选内容后点击“开始导入”；原文件保留。

### Linux x64

需要图形桌面，以及相应的字体、OpenGL/Mesa 和 X11 / XWayland 环境：

```sh
chmod +x MoliFox-linux-x64.run
./MoliFox-linux-x64.run
```

入口使用 `sha256sum` 校验文件；如果需要首次下载 Java，还需要 `curl` 或 `wget` 及 `tar`。文件、目录与保存位置使用桌面系统窗口：GNOME/GTK 需要 `zenity`，KDE 优先 `kdialog`。不同发行版的图形依赖可能不同，当前没有完整的发行版兼容性承诺。

### 首次启动需要联网吗？

采用小体积单文件分发，**首次启动需要联网下载并校验界面依赖**，准备好并缓存后可离线打开。优先使用兼容的系统 Java 17 或更高版本；缺少时联网准备 Java 21。不需要 Node.js 或浏览器内核，运行数据默认保存在程序旁的 MoliFox 文件夹。

缺少合适的 Java 时，首次准备 Java 需要联网。安装尚不存在的游戏、补齐文件、搜索下载内容、微软授权与刷新也需要联网。打开启动器、拥有完整游戏文件、完成正版认证是不同的条件。

## 功能与当前状态

“已有实现”表示代码和入口已接入，不代表所有版本、加载器组合与平台都已实测。

| 范围 | 已有实现 | 当前边界 |
| --- | --- | --- |
| 游戏实例 | 原版安装与修复、已有目录原地导入、多目录切换、搜索、排序、收藏、备注 | 外部目录直接使用原文件，导入不等于复制 |
| 加载器 | Fabric、Quilt、Forge、NeoForge 安装；外部及整合包实例可更换组件，变更前备份、失败恢复和上一选择回退 | 代表版本组合仍待补验；自定义补丁与模组不保证跨加载器兼容 |
| Java 与启动 | 系统 Java 检测、按需下载、兼容选择、自动/手动内存与实例策略、窗口、全屏、JVM / 游戏参数、环境变量 | 自动内存在启动时按可用内存计算，启动器 Java 与游戏 Java 分开选择 |
| 高级启动 | 全局默认与实例覆盖、只读参数预览、前后命令、取消与超时、启动器窗口生命周期 | 新窗口行为的 Linux 实测待补 |
| 账户与皮肤 | 开始页微软/离线账户、真实头像、皮肤库/正背面预览/PNG 导入导出、正版上传确认、离线本地预览 | 正版上传接口只以模拟服务验证；完整三维、离线游戏内自定义和正版进游戏闭环仍待补 |
| Modrinth 内容 | 搜索、详情、兼容版本、发布通道、必需及可选依赖、事务安装 | 不兼容时停止安装；不同资源类别的能力并不相同 |
| 本地内容 | 模组导入、启停、移除、声明读取；哈希识别、批量检查更新与版本锁定 | 未识别不等于已经最新；更新 GUI 全流程待收尾 |
| 整合包 | 多格式导入、预览、校验、隔离、失败回滚、升级预览、自有 ZIP 与标准 mrpack 导出向导 | 特殊组件、CurseForge 包升级、新格式真实游戏样本仍有边界，见格式文档和最新交接 |
| 网络与日志 | 官方源 / BMCLAPI、下载并发、系统 / 直连 / 手动代理、游戏日志与诊断导出 | 下载镜像与内容站点分别设置 |
| 个性化 | 浅色 / 深色 / 系统主题、主题色、背景、字体、缩放、玻璃与页面选项 | 整窗透明需重启；磨砂作用于启动器自己的背景层 |
| CurseForge | 检索、详情/版本、依赖、指纹更新和 manifest 包核心及桌面来源接线 | 真实服务需要获批 API Key，目前仍未完成真实 Key 验收 |
| 实例与存档维护 | 实例复制/整理/校验、目录/隔离迁移、存档备份恢复与跨实例复制 | 部分交互仍只有冒烟级证据；迁移时备份区保留在源目录 |
| 下载恢复 | 大文件分段、网络中断续传、重试与校验、显式取消清理 | 共享目录修改会排队；跨进程锁及无哈希资源的版本变化识别仍有限制 |
| 实例图标与截图 | 图片/自绘预设/自动图标、图库/日期/导出/回收、图片剪贴板与设背景 | 关键用例和 Windows 定向 GUI 通过，Linux 本批未实机 |

### 页面入口

- **开始**：账户操作、选择实例、启动游戏与常用操作。
- **实例**：游戏目录和版本管理；详情包含“游戏设置 / 安装与更新 / 模组 / 资源包 / 存档 / 原理图”六页签，以及文件夹和维护入口。页签存在不代表已具备完整存档备份恢复等后续能力。
- **下载**：原版、整合包、模组、资源包、光影、数据包六分类。数据包需要明确目标存档，暂不能像模组一样直接安装到整个实例。
- **设置**：全局游戏设置、Java 管理、通用、外观、下载、日志、关于七分区。

一般设置使用页面内的保存按钮；外观选项按界面提示应用。Java、内存和高级启动配置支持实例覆盖；隔离只由全局开关控制。开启后使用 versions/版本名 保存模组、存档和配置，关闭后使用游戏根目录中的共用内容。旧编号目录会校验迁移并保留备份，切换前应确认实际目录。

## 数据存放与便携使用

默认结构如下。选择外部游戏目录或自定义下载缓存后，对应内容保存在所选位置。

```text
启动文件所在目录/
├─ MoliFox.exe                 Windows 启动文件
├─ MoliFox-linux-x64.run       Linux 启动文件，按需选择其一
├─ .minecraft/                默认游戏根目录
│  ├─ versions/               游戏版本与启动配置
│  ├─ libraries/              游戏依赖
│  ├─ assets/                 游戏资源
│  └─ instances/              茉狐自建的隔离实例
└─ MoliFox/                   启动器数据
   ├─ config/launcher.json    设置、账户资料及实例记录
   ├─ dependencies/           启动器入口、原生库、版本化界面依赖
   ├─ runtimes/               按需下载的 Java
   ├─ credentials/            每个账户的便携凭据文件
   ├─ appearance/             backgrounds / fonts / music / themes
   ├─ skins/                  本地皮肤库
   ├─ icons/                  用户及整合包图标
   ├─ backups/                配置快照、整合包原档、实例备份、迁移保留
   ├─ cache/                  downloads / metadata / skins / tmp
   ├─ logs/                   启动、运行日志
   └─ trash/                  系统回收站不可用时的保留目录
```

程序所在目录需要可写。外部游戏目录直接使用原位置，不自动复制进 `MoliFox/`；移除外部目录记录不会删除原文件。

旧配置与已知旧目录会自动迁移；冲突文件保留副本，不覆盖用户游戏。原生启动器退出后清理本次 `cache/tmp/session-*`，依赖与 Java 保留复用。具体规则见 [数据布局](docs/STORAGE_LAYOUT.md)。

备份或移动前先退出启动器与游戏，同时保留 `MoliFox/`、默认 `.minecraft/` 及自行选择的外部目录。配置含绝对路径，跨位置或跨系统移动后，可能需要重新选择游戏目录、Java、背景和自定义缓存。便携凭据可读取不代表全部路径自动迁移。

### 微软账户与凭据

Microsoft Client ID 已内置，普通用户无需填写，也不需要在桌面程序中放置应用密钥。用户于 2026-10-27 提供了 Mojang API 许可名单获批邮件。

微软密码只在微软网页输入，不经过启动器。只持久保存刷新令牌，运行时换取访问凭据；玩家名、UUID、皮肤地址等普通资料保存在配置中。

按用户选择，刷新令牌以便携 AES-GCM 封装保存在 `MoliFox/credentials/`。格式密钥内置，持有程序与凭据文件的人仍可能还原令牌，因此不要公开分享整个数据文件夹。旧 Windows DPAPI / Linux Secret Service 凭据会先读取并迁移，成功写入新文件后才清旧项；旧数据应先在原系统用户下迁移，无法读取时重新登录。详见 [凭据保存说明](docs/CREDENTIAL_STORAGE.md)。

`config/launcher.json` 不保存微软令牌，但设置中可能包含用户填写的 CurseForge API Key；分享配置或诊断资料前需要移除它。

## 验证进度与已知限制

上一完整核心回归为 **239 项（238 通过、1 权限跳过）**。晚间修复只验证实质改动的 **34 项关键检查（33 通过、1 权限跳过）**，没有重复跑全量。用户目录的四个实际 Fabric 版本搜索、兼容版本及官方皮肤 CDN 通过，真实 Java 自动内存参数与工作目录通过；定向 GUI 和新 Windows EXE 离线打开通过。两个单文件均已重建；Linux 本轮运行、CurseForge 真实 Key 与完整正版进游戏仍未验收。详见 [本轮审查](docs/REVIEW_2026-10-27.md)。

| 验证项 | 已记录结果 |
| --- | --- |
| Windows 启动器 | 无界面缓存、网络不可达条件下首次开窗通过；最近发行 EXE 的独立开窗检查通过 |
| Windows 原版 | Minecraft 1.20.1 实际进入主菜单并正常退出 |
| Windows Fabric | 游戏发现成功；本工具执行身份下的 ZIPFS 只读问题仍待普通桌面环境验收 |
| Linux 游戏 | WSLg / Mesa 软件渲染下，1.20.1 原版与 Fabric 0.16.14 有主菜单证据；Fabric 自动关闭和退出码通过 |
| Linux 启动文件 | 已构建并静态校验；`.run` 启动器界面及 Linux 在线安装未实测，不等同于 WSLg 游戏测试 |
| 微软真实服务 | 旧 DPAPI 存储下，网页授权、角色读取、重建控制器后刷新、本地移除通过；未用该会话启动游戏 |
| 新便携凭据 | 读写、迁移、损坏拒绝和模拟跨平台复制通过；新存储方式在线刷新及 Linux Secret Service 迁移待验收 |
| Modrinth 实际内容 | Sodium 历史版本、识别、更新、锁定与禁用状态保留通过，未执行下载的模组 |
| 完整 GUI 回归 | 新内容版本弹窗流程待收尾，不将独立开窗检查视为完整 GUI 通过 |

主要待办包括披风与动作动画、离线游戏内皮肤、程序更新替换与回滚、网络缓存维护、更多组件、第三方认证、CurseForge 真实 API 与新界面的更多真机验证。可拖动三维皮肤已实现。[HMCL 功能对照](docs/HMCL_FUNCTION_PARITY.md) 明确记录已有、部分和缺项，不宣称当前已经覆盖 HMCL 全部功能。

用户已确认按顺序实施核心功能；UI 细节由独立任务按参考与确认流程推进。实施顺序见 [功能路线图](docs/FUNCTION_ROADMAP.md)，具体缺口、参考与估算见 [功能差距清单](docs/FEATURE_AUDIT.md)。

## 从源码开发

### 环境

- **JDK 25**：当前构建工具使用，产物字节码目标为 Java 17。
- **Gradle 9.7.0**：仓库提供 Wrapper，首次构建需要下载工具与依赖。
- **Windows 原生入口**：MinGW-w64 的 `g++` 和 `windres`，通过 `MOLIFOX_MINGW_BIN` 指定目录或加入 `PATH`。
- **Linux**：POSIX Shell；运行界面还需要本机桌面依赖。

Wrapper 默认将 Gradle 依赖放在项目 `.gradle-home/`，临时文件放在 `.work/build-tmp/`。自行调用已安装的 Gradle 时，也应显式配置这些位置，避免产生项目外缓存。

**当前维护机**的 Wrapper 发行版缓存不完整，并有下载证书问题，应使用已安装的 Gradle 9.7.0；具体路径与可复用命令见 [维护交接](docs/AI_HANDOFF.md)。这不代表所有机器的 Wrapper 下载均已验证。

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

产物位于根目录 `MoliFox.exe` 与 `MoliFox-linux-x64.run`，SHA-256 摘要在 `docs/validation/`。已有产物不代表包含当前未验证的源码改动。

`:launcher-core:gameSmoke` 和 `:launcher-core:accountSmoke` 是需要桌面或用户参与的真实游戏与授权验收，不属于普通单元测试；执行方法及结果记录在交接和验证文档中。

### 代码结构与测试约定

| 目录 | 职责 |
| --- | --- |
| `desktop-app/` | Compose 桌面界面、页面操作与桌面分发构建 |
| `launcher-core/` | 实例、Java、安装与启动、账户、网络、内容和整合包 |
| `launcher-bootstrap/` | 单文件入口、Java 准备、依赖清单和首次联网下载校验 |
| `tools/` | GUI 探针、打包检查和平台验证工具 |
| `docs/` | 交接、参考研究、路线图、验证及清理记录 |

类型与 Compose 函数使用大驼峰，普通函数、变量和参数使用小驼峰。新增设置必须连接实际行为，文件操作需保留失败、取消与回滚边界。

测试统一放在 `.work/tests/`，成功或失败后自动清理，不删除用户的 `MoliFox/`、游戏或外部目录。可复用依赖保留在项目内。GUI 测试顺序运行，只操作测试窗口；界面截图最多保留一套，第三方参考截图与缓存源码仅放在已忽略的 `docs/ui-reference/`，不提交到公开仓库。

## 文档索引

| 文档 | 内容 |
| --- | --- |
| [HANDOFF](docs/HANDOFF.md) | 当前进度、在途改动、下一步；顶部记录优先 |
| [AI_HANDOFF](docs/AI_HANDOFF.md) | 维护机环境、构建命令、代码入口和已知问题 |
| [VALIDATION](docs/VALIDATION.md) | 实测平台、证据及未验收内容 |
| [FUNCTION_ROADMAP](docs/FUNCTION_ROADMAP.md) | 已确认的功能实施顺序 |
| [FEATURE_AUDIT](docs/FEATURE_AUDIT.md) | 实施前差距；当前状态以交接为准 |
| [CONTENT_MANAGEMENT](docs/CONTENT_MANAGEMENT.md) | 内容详情、版本、识别、更新与锁定 |
| [CURSEFORGE](docs/CURSEFORGE.md) | CurseForge 核心接口、手动文件流程及待接线边界 |
| [PACK_FORMATS](docs/PACK_FORMATS.md) | 整合包导入格式、组件兼容和迁移范围 |
| [MRPACK_EXPORT](docs/MRPACK_EXPORT.md) | 标准整合包导出接口、选择与分发权限、验证边界 |
| [NEXT_AI_HANDOFF](docs/NEXT_AI_HANDOFF.md) | 当前全量交接、停工位置、接续顺序与晚间审查清单 |
| [REVIEW_2026-10-27](docs/REVIEW_2026-10-27.md) | 用户反馈修复、代码审查、真实服务与定向验证 |
| [HMCL_FUNCTION_PARITY](docs/HMCL_FUNCTION_PARITY.md) | HMCL 功能入口级对照、独立实现进度和剩余缺项 |
| [SKIN_MANAGEMENT](docs/SKIN_MANAGEMENT.md) | 皮肤库、预览、正版上传和离线预览边界 |
| [INSTANCE_ICONS](docs/INSTANCE_ICONS.md) | 实例图片/预设、自动识别和资产归属 |
| [SCREENSHOT_GALLERY](docs/SCREENSHOT_GALLERY.md) | 图库、导出回收、图片复制与背景 |
| [CREDENTIAL_STORAGE](docs/CREDENTIAL_STORAGE.md) | 凭据格式、迁移与便携边界 |
| [UI_HANDOFF](docs/UI_HANDOFF.md) | UI 任务分工、参考流程及阶段记录 |
| [REFERENCE_RESEARCH](docs/REFERENCE_RESEARCH.md) | 官方资料、参考及独立实现边界 |
| [UI_DESIGN](docs/UI_DESIGN.md) | 早期设计依据，当前布局以最新交接为准 |
| [CLEANUP](docs/CLEANUP.md) | 临时数据与目录清理规则 |
| [AGENTS](AGENTS.md) | 项目工作约定 |

## 许可与参考

项目自身源码采用 [GNU GPLv3](LICENSE)，并附加第 7(b)/(c) 节允许的保留署名及修改版明确区分条款，详见 [NOTICE](NOTICE)。第三方依赖及其许可见 [THIRD_PARTY](THIRD_PARTY.md)；Minecraft、游戏资源与作者作品保持各自归属。源代码、发布版本及问题反馈见 [Tinmoli/MoliFox](https://github.com/Tinmoli/MoliFox)。

参考项目：[PCL](https://github.com/Meloong-Git/PCL)、[HMCL](https://github.com/HMCL-dev/HMCL)、[SJMCL](https://github.com/UNIkeEN/SJMCL)、[Prism Launcher](https://github.com/PrismLauncher/PrismLauncher)、[Modrinth App](https://github.com/modrinth/code)。参考用于理解布局、操作与格式，未复制其源码、图标、截图或宣传素材作为茉狐的产品资源。
