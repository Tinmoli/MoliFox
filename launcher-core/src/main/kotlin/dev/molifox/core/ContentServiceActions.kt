// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责汇总内容服务的实现。

package dev.molifox.core

/** 内容服务的实现汇总：继承链末端，[ContentService] 把公开接口委托给它。 */
internal class ContentServiceActions(
    core: ContentServiceCore,
) : ContentInstallFlowActions(core)
