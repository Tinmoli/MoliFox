# 许可文档（中文）

本项目（MoliFox / 茉狐）向用户展示的许可信息，都收在这个目录里。

| 文件 | 内容 | 什么时候看它 |
| --- | --- | --- |
| [GPL-3.0-zh.md](GPL-3.0-zh.md) | GNU GPL 第 3 版全文的中文参考译文，按条逐节对应（序言 + 0–17 条 + 「如何应用」附录） | 想知道我们自己的代码适用什么许可、分发时要做什么 |
| [ADDITIONAL-TERM-HMCL-zh.md](ADDITIONAL-TERM-HMCL-zh.md) | HMCL 的 SPDX 附加条款 `AdditionRef-HMCL`：哪些是已核实的事实，哪些只是推断，权威正文该去哪里找 | 看到 `GPL-3.0-or-later WITH AdditionRef-HMCL` 这个字符串，想知道它约束谁 |
| [THIRD_PARTY-LICENSES-zh.md](THIRD_PARTY-LICENSES-zh.md) | 我们实际分发或下载的第三方组件：名称 / 版本 / 许可 / 对我们的实际义务 / 链接，含 Classpath Exception 的普通话解释 | 想核对某个依赖的许可，或准备做「关于 → 许可」界面 |

## 一句话说清边界

**我们的项目代码不包含 PCL / HMCL 的源码，因此不构成它们的 GPL 衍生作品。**

- PCL、PCL-CE、HMCL 只作为**只读参考**：看它们的功能划分、操作流程和设置层次，然后用自己的代码和素材独立实现。
- 不复制它们的源码、XAML、图片、图标、语言文件或品牌素材；不把它们的二进制打进我们的安装包。
- 因此 HMCL 的 `AdditionRef-HMCL` 附加条款、PCL 的许可，对我们的分发**不产生义务**；它们约束的是分发 HMCL/PCL 或其修改版的人。详细推理见 [ADDITIONAL-TERM-HMCL-zh.md](ADDITIONAL-TERM-HMCL-zh.md)。

## 我们自己的许可

MoliFox 自身代码采用 **GNU GPL 第 3 版**（`GPL-3.0-only`，见根目录 `LICENSE`），并按 GPLv3 第 7(b)、7(c) 条附加了两条要求（保留署名与许可声明；修改版须明确标识、不得冒充原版）。附加条款正文在根目录 `NOTICE`。

分发我们的程序时，你需要：随附 `LICENSE` 全文与 `NOTICE`；提供对应源程序；保留界面与文件中的著作权声明；修改版以改名或改版本号的方式与原版区分。以上义务的完整表述以英文 `LICENSE` / `NOTICE` 为准。

## 第三方组件

第三方组件各自保留自己的许可（多为 Apache-2.0，Skia 与 ANTLR 为 BSD-3-Clause，miniz 为 MIT，下载的 Java 运行时为 GPLv2 + Classpath Exception）。清单与逐项义务见 [THIRD_PARTY-LICENSES-zh.md](THIRD_PARTY-LICENSES-zh.md)。

## 读之前请知道

- 中文 GPL 译文是**非官方参考译文**，以英文原文为准；译文不构成法律意见。
- 文档里凡是「推断」，都明确标了「推断」，没有被写成已确认的条款。
- Minecraft 是 Mojang 的商标；MoliFox 是独立项目，与 Mojang、Microsoft、HMCL、PCL 均无隶属或背书关系。