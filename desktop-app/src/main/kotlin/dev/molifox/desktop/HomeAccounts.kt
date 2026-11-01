// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责开始页账户摘要卡。

package dev.molifox.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.gson.JsonParseException
import dev.molifox.core.Account
import dev.molifox.core.AccountType
import dev.molifox.core.LauncherState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.IOException
import java.nio.file.Path

/**
 * 开始页账户摘要：当前账户的头像、名字，行尾是「皮肤概览 / 刷新 / 箭头」。
 *
 * 皮肤概览与刷新位于开始页账户卡：两个 32dp 图标按钮紧挨箭头左侧， 皮肤概览复用 [SkinManagerDialog]，刷新对正版账户先拉正版档案、再让头像重读纹理， 离线 /
 * 外置认证账户只重读本地皮肤。账户列表页提供「删除」。 整块卡片仍然可点：两个按钮都是独立 clickable，按下事件在按钮内部就被消费，不会冒泡成「打开账户列表」。
 * 没有账户时只显示史蒂夫与「没有游戏账户」，不出现这两个按钮（没有可刷新的对象）。
 */
@Composable
fun HomeAccountSummary(
    state: LauncherState,
    actions: UiActions,
    onOpenAccounts: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = LocalStrings.current
    val account = state.data.accounts.find { it.id == state.data.activeAccountId }
    // 头像读取计数：皮肤概览报告更新、或点了刷新后递增，produceState 才会重取纹理（地址不变时也能重读）。
    var revision by remember(account?.id) { mutableStateOf(0) }
    var managingSkin by remember(account?.id) { mutableStateOf(false) }
    var refreshing by remember(account?.id) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // 没有账户时 skinFile 为 null，StaticSkinHead 会退回内置史蒂夫纹理。
    val skinPath by
        produceState<Path?>(null, account?.id, account?.skinUrl, account?.localSkinId, revision) {
            value = account?.let { actions.controller.accountSkin(it, refresh = revision > 0) }
        }

    fun reportRefreshFailure(failure: Exception) {
        actions.controller.store.notify(failure.message ?: tr("pages.home.accounts.refreshFailed"))
    }

    fun refresh() {
        val target = account ?: return
        if (refreshing) return
        refreshing = true
        scope.launch {
            try {
                refreshAccountProfile(actions, target) { reportRefreshFailure(it) }
            } finally {
                revision++
                refreshing = false
            }
        }
    }
    if (account != null && managingSkin) {
        SkinManagerDialog(
            account,
            actions,
            onUpdated = { revision++ },
            onDismiss = { managingSkin = false },
        )
    }
    val shape = RoundedCornerShape(Metrics.innerRadius)
    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            // hoverHighlight 自带 hoverable 与 120ms 淡入，整块可点：点哪里都进账户列表。
            .hoverHighlight(shape)
            .clickable(
                onClickLabel = strings.t("pages.home.accounts.openLabel"),
                onClick = onOpenAccounts,
            ).padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StaticSkinHead(
            skinPath,
            { actions.controller.store.log(tr("deskI.accounts.avatarFailed", it.message), true) },
            revision,
            Modifier.size(48.dp).clip(RoundedCornerShape(Metrics.innerRadius)),
        )
        Spacer(Modifier.width(12.dp))
        if (account == null) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    strings.t("pages.home.accounts.empty"),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    strings.t("pages.home.accounts.add"),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        } else {
            Text(
                account.name,
                Modifier.weight(1f),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // 皮肤和刷新按钮位于箭头左侧，触区为 32dp。
            // 它们各自是可点击的 IconButton，会在自己的节点消费按下事件，因此不会触发卡片的 onOpenAccounts。
            AccountIconAction(
                Icons.Outlined.Palette,
                strings.t("pages.home.accounts.skin"),
                { managingSkin = true },
                size = 32.dp,
            )
            AccountIconAction(
                Icons.Outlined.Refresh,
                if (refreshing) {
                    strings.t("pages.home.accounts.refreshing")
                } else {
                    strings.t("pages.home.accounts.refresh")
                },
                { refresh() },
                enabled = !refreshing,
                size = 32.dp,
            )
            Spacer(Modifier.width(2.dp))
        }
        Icon(
            Icons.Outlined.ChevronRight,
            strings.t("pages.home.accounts.openLabel"),
            Modifier.size(16.dp),
            MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 正版账户先重拉档案；离线与外置认证账户没有这个接口，只重读本地皮肤。 */
private suspend fun refreshAccountProfile(
    actions: UiActions,
    account: Account,
    onFailure: (Exception) -> Unit,
) {
    try {
        // 正版账户的皮肤地址写在正版档案里：先重新拉档案（顺带更新 skinUrl），
        // 再由 revision 让头像重读纹理缓存；离线与外置认证账户没有这个接口，只重读本地皮肤。
        if (account.type == AccountType.Microsoft) {
            actions.controller.skins.refreshProfile(account.id)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: IOException) {
        onFailure(failure)
    } catch (failure: JsonParseException) {
        onFailure(failure)
    } catch (failure: IllegalStateException) {
        onFailure(failure)
    } catch (failure: IllegalArgumentException) {
        onFailure(failure)
    }
}
