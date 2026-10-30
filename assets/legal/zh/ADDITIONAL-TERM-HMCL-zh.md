# `AdditionRef-HMCL`：我们知道什么、推断什么

本文说明 HMCL 源码头部 SPDX 表达式 `GPL-3.0-or-later WITH AdditionRef-HMCL` 中的附加条款部分。
**结论先行：这个附加条款的「正文」没有以独立文件随源码发布；仓库里只有一个名为
「Additional Terms for Hello Minecraft! Launcher」的附加条款段落（在 `LICENSE` 末尾），
它与 `AdditionRef-HMCL` 这个标识符是否逐字对应，仓库本身没有写明。**
本文把已核实的事实与推断严格分开，不把推断当成条款正文。

> 说明：本项目只把 HMCL 作为**只读参考**，不复制其代码与素材，因此本条款对我们当前的分发**不产生义务**。
> 本文存在的意义是：一旦有人在别的场合分发 HMCL 或其衍生作品，或我们要在文档中引用该项目，能说清楚边界。

## 1. 已核实的事实

核实对象：`D:\Downloads\HMCL-main`（HMCL 源码的本地只读副本）。

| 编号 | 事实 | 核实方式 |
| --- | --- | --- |
| F1 | SPDX 字符串出现在 `config/checkstyle/license-header.txt` 第 2 行：`^// SPDX-License-Identifier: GPL-3\.0-or-later WITH AdditionRef-HMCL$` | 直接读取该文件 |
| F2 | 同样的字符串作为文件头写在 869 个 `.java` 文件中；含 `.idea/copyright/HMCL.xml` 等配置，全仓库共 893 个文件出现该字符串 | `grep -rl "AdditionRef-HMCL"` 计数 |
| F3 | 全仓库搜索 `AdditionRef`，所有命中都是「使用该标识符」的位置，**没有任何一处定义它**；没有 `LICENSES/` 目录，没有 `.reuse/`，也没有任何文件名含 `exception` 的许可证文件 | `grep -rn "AdditionRef"`、`ls -d LICENSES .reuse`、`find -iname "*exception*"` |
| F4 | 仓库里唯一以文字形式存在的 HMCL 附加条款，在根目录 `LICENSE` 第 677–684 行，标题为 `Additional Terms for Hello Minecraft! Launcher` | 读取 `LICENSE` |
| F5 | `LICENSE` 其余部分（第 1–675 行）是 GPLv3 标准全文，未作修改 | 与 GNU 官方文本对照 |
| F6 | 该仓库没有把标识符映射到条款 URL 的文件（SPDX 3 中可用 `customIdToUri` 记录这种映射，此处不存在） | 搜索 `customIdToUri`、`AdditionRef` 定义 |

### F4 的原文（英文，逐字引自 `LICENSE` 第 677–684 行）

```text
Additional Terms for Hello Minecraft! Launcher

  1. When you distribute a modified version of the software, you must change
the software name or the version number in a reasonable way in order to
distinguish it from the original version. Under GPLv3, 7(c).

  2. You must not remove the copyright declaration displayed in the software.
Under GPLv3, 7(b).
```

### F4 的中文翻译（供阅读，非官方译文）

> **Hello Minecraft! Launcher 附加条款**
>
> 1. 当你分发本软件的修改版本时，你必须以合理方式更改软件名称或版本号，以便与原版区分。依据 GPLv3 第 7(c) 条。
> 2. 你不得移除软件中显示的著作权声明。依据 GPLv3 第 7(b) 条。

这两条正好对应 GPLv3 第 7 条第 3 段允许的两种附加条款：7(c)（禁止虚假陈述来源、要求修改版标明不同）与 7(b)（要求保留特定的法律声明或作者署名）。

## 2. 已知 / 推断 / 未知

| 项目 | 状态 | 内容 |
| --- | --- | --- |
| HMCL 的许可主体 | **已知** | `GPL-3.0-or-later`，即 GPLv3 或任何更高版本 |
| 附加条款存在 | **已知** | 存在，且在 `LICENSE` 末尾有两条文字（F4） |
| 附加条款正文随源码发布 | **已知：没有以独立文件发布** | 没有 `AdditionRef-HMCL` 定义文件、没有例外清单（F3、F6） |
| `AdditionRef-HMCL` 是否就等于 F4 那两条 | **推断（高可信，未获证实）** | 理由：F4 是仓库内唯一的 HMCL 附加条款；其两条正好落在 GPLv3 §7 允许的 7(b)/7(c) 范围内；标识符名为 `AdditionRef-` + 项目缩写 `HMCL`。但仓库未写「本标识符指以下条款」，所以这只是推断 |
| `AdditionRef-` 前缀的含义 | **已知（SPDX 规范）** | SPDX 3.0.1 附录 B 定义 `addition-ref = [DocumentRef-(idstring):] "AdditionRef-"(idstring)`，用于引用**未收录进 SPDX 许可证列表的自定义附加条款**；规范同时说明这类附加文本「可能修改、也可能不修改原许可证条款」 |
| `WITH` 的效力 | **已知（SPDX 规范）** | `GPL-3.0-or-later WITH AdditionRef-HMCL` = 以 GPLv3（或更高版本）为基础，**再加上** `AdditionRef-HMCL` 所指的附加文本；左操作数是许可证，右操作数是附加条款，两者叠加而非替换 |
| 附加条款是否取消著佐权 | **已知：不取消** | GPLv3 §7 只允许「附加许可（对条件作例外）」或 §7(a)–(f) 范围内的附加条件；无论哪种，整个作品仍受 GPLv3 管辖（§5(c)、§7 第 1 段） |
| 是否需要署名 | **推断（中可信）** | 若 F4 即为正文，则义务是「保留软件中显示的著作权声明」（7(b)），而不是「在任何文档中标注 HMCL」。名称含 `AdditionRef` 只说明它是「附加条款的引用」，并不当然产生署名义务。GPLv3 §7(e) 还允许许可人保留商标权，但 F4 未提商标 |
| 官方权威正文的位置 | **未知（需向 HMCL 求证）** | 见第 4 节 |

## 3. 怎么理解 `WITH`（推断部分明确标注）

SPDX 表达式 `A WITH B` 的语义是「A 许可证，加上 B 这份附加条款」。对 `GPL-3.0-or-later WITH AdditionRef-HMCL`：

1. **基础许可证不变**：仍然是 GPLv3 或更高版本，仍要求向接收者提供源程序、保留声明、以同一许可证授权整体（§5）。附加条款不会把 GPL 变成 MIT/Apache 之类的宽松许可。
2. **附加条款只能在 GPLv3 §7 的框架内生效**：§7 允许两类内容——(i) 对某些条件作例外的「附加许可」，(ii) 限于 §7(a)–(f) 的附加条件。超出这个范围的附加条款，依 §7 第 4 段被视为「进一步限制」，接收者可以移除。
3. **对下游可移除性**：§7 第 2 段允许传递者从副本中移除附加许可；但如果附加条款本身写明「修改时必须移除/保留」，则从其约定。F4 第 2 条（不得移除著作权声明）属于 7(b) 型条件，不是可随手移除的「附加许可」。
4. **`AdditionRef` 不是 SPDX 官方例外**：它不在 SPDX 许可证列表里，是 HMCL 自定义的引用名。因此任何工具都**无法**只凭这个字符串还原条款内容——必须有外部映射。

> **推断（未证实）**：`AdditionRef` 这个命名最可能表示「additional terms reference」（附加条款引用），即指向一份随项目发布的附加文本；`HMCL` 是项目缩写。因此它**最可能**要求的不是「在第三方文档中标注 HMCL」，而是「分发 HMCL 修改版时保留其著作权声明、并以改名或改版本号的方式区分原版」（即 F4 的两条）。上面这几句是推断，不是条款原文；在拿到官方说明前，不应把它当作已确认的义务。

## 4. 权威正文应该去哪里找

按可信度排序。前两项是「应当能找到」的位置，第三项是规范层面的对照。

1. **HMCL 仓库根目录 `LICENSE` 末尾**（我们已核实的本地副本即来自此处）
   <https://github.com/HMCL-dev/HMCL/blob/main/LICENSE>
   这是目前唯一有文字的位置；请以该文件的当前版本为准，因为上游可能修改附加条款。
2. **HMCL 官方发布说明 / 仓库文档**：GitHub Releases 页与仓库 `docs/` 目录。若上游日后把 `AdditionRef-HMCL` 抽成独立文件或给出 `customIdToUri` 映射，应优先采信那里，而不是本文第 1 节的转述。
   <https://github.com/HMCL-dev/HMCL/releases>
3. **SPDX 规范（用于确认 `WITH`/`AdditionRef-` 的语法与语义，不含 HMCL 条款内容）**
   <https://spdx.github.io/spdx-spec/v3.0.1/annexes/spdx-license-expressions/>

**如果上述位置都找不到名为 `AdditionRef-HMCL` 的独立正文**，正确的做法是：按 F4 的 `LICENSE` 末尾文字理解义务，并在需要确定性时直接向上游询问，而不是自行补写条款。本文不提供、也不猜测任何未在源码中出现的条款文字。

## 5. 对本项目（MoliFox）的影响

| 场景 | 是否有义务 |
| --- | --- |
| 我们只阅读 HMCL 源码、独立实现功能（当前做法） | **无**。不构成 GPL 衍生作品，附加条款不适用 |
| 我们在文档中提及 HMCL 的名字、链接其仓库 | **无附加条款义务**。属于对项目的指称；GPLv3 §7(e) 允许许可人保留商标权，但 F4 未主张商标限制。仍应避免暗示官方背书 |
| 我们复制 HMCL 的代码、素材、图标或语言文件 | **有**。整件作品须以 GPLv3 授权、提供源程序、保留著作权声明，并按 F4 第 1 条改名/改版本号区分原版 |
| 我们分发 HMCL 的修改版二进制 | **有**。除上述外还须随附 `LICENSE` 全文与附加条款，且不得移除软件内著作权声明 |

## 6. 我们不做的事

- 不把 F4 的翻译当作官方译文；它是我们为阅读便利所做的翻译，冲突时以英文原文为准。
- 不声称 `AdditionRef-HMCL` 的正文「不存在」——准确说法是：**该标识符未被定义为独立文件；仓库内唯一的附加条款文字在 `LICENSE` 末尾，二者对应关系未获上游明示。**
- 不把推断写成「HMCL 要求我们必须署名」。目前没有任何已核实的文字支持这一说法。

---

相关文件：[GPL-3.0-zh.md](GPL-3.0-zh.md)（GPLv3 中文参考译文，第 7 条为附加条款的规则来源）· [THIRD_PARTY-LICENSES-zh.md](THIRD_PARTY-LICENSES-zh.md) · [README-zh.md](README-zh.md)