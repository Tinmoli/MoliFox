// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 实例列表的读取中 / 读取失败 / 空列表状态。

package dev.molifox.desktop.instances

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.molifox.desktop.EmptyState
import dev.molifox.desktop.PrimaryButton
import dev.molifox.desktop.SecondaryButton

@Composable
internal fun InstanceLoadingState(modifier: Modifier) {
    Column(
        modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(
            Modifier.size(28.dp),
            strokeWidth = 2.5.dp,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "正在读取实例…",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun InstanceErrorState(
    modifier: Modifier,
    message: String,
    onRetry: () -> Unit,
) {
    Column(
        modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Outlined.ErrorOutline,
            null,
            Modifier.size(32.dp),
            MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.height(10.dp))
        Text("无法读取游戏目录", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            message,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(12.dp))
        SecondaryButton(onRetry) { Text("重试", fontSize = 13.sp) }
    }
}

@Composable
internal fun InstanceEmptyState(
    modifier: Modifier,
    noInstance: Boolean,
    busy: Boolean,
    onNew: () -> Unit,
    onClearFilter: () -> Unit,
) {
    Column(
        modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        EmptyState(
            if (noInstance) "还没有游戏实例" else "没有匹配的实例",
            if (noInstance) {
                "安装一个新版本，从游戏目录导入，或直接新建实例。"
            } else {
                "调整关键词、版本、加载器或收藏筛选后再试。"
            },
            Icons.Outlined.Widgets,
        )
        if (noInstance) {
            PrimaryButton(
                "安装第一个版本",
                Icons.Outlined.Add,
                enabled = !busy,
                onClick = onNew,
            )
        } else {
            SecondaryButton(onClearFilter) { Text("清除筛选", fontSize = 13.sp) }
        }
    }
}
