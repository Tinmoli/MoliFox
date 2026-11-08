// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责给界面提示标注流程类型；界面按类型判断结果，不解析本地化文案。

package dev.molifox.core

/** 提示所属的流程类型，配合 [LauncherStore.noticeKind] 使用。 */
enum class LauncherNoticeKind {
    /** 普通信息提示，不参与任何流程判断。 */
    Info,

    /** 设置已保存；设置页据此清掉连续调整时的成功提示。 */
    SettingsSaved,

    /** 登录成功。 */
    LoginSignedIn,

    /** 登录流程结束（失败、验证码过期或用户取消），界面据此收起验证码。 */
    LoginEnded,
}
