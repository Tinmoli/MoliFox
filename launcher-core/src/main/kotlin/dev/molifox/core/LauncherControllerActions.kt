// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责汇总桌面入口的实现。

package dev.molifox.core

/** 桌面入口的实现汇总：继承链末端，[LauncherController] 把公开接口委托给它。 */
internal class LauncherControllerActions(
    core: LauncherControllerCore,
) : LauncherQueryActions(core)
