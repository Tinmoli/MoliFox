// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 首次启动的用户协议门与全窗口阅读页：正文随安装包发布，语言跟随界面语言。

package dev.molifox.desktop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext

/** 当前协议版本；与 assets/legal/AGREEMENT*.md 首部一致。提升版本号即让所有用户重新确认。 */
internal const val AGREEMENT_VERSION = "2026-11-02.1"

/** 协议正文资源在 /META-INF/molifox/ 下，构建时由 assets/legal/ 复制而来。 */
private const val AGREEMENT_RESOURCE_ROOT = "/META-INF/molifox/"

/** 四种语言各一份正文；其他语言使用英文，资源缺失时再回落到简体中文。 */
internal fun agreementResourceName(languageTag: String): String =
    when (languageTag) {
        "zh-CN" -> "AGREEMENT.md"
        "zh-TW" -> "AGREEMENT.zh-TW.md"
        "zh-HK" -> "AGREEMENT.zh-HK.md"
        else -> "AGREEMENT.en-US.md"
    }

internal object AgreementCatalog {
    private val cache = mutableMapOf<String, String>()

    /** 读取指定语言的协议正文；读不到时返回空串，由调用方显示缺文提示。 */
    fun load(languageTag: String): String =
        synchronized(cache) {
            cache.getOrPut(languageTag) {
                loadResource(agreementResourceName(languageTag))
                    ?: loadResource("AGREEMENT.md")
                    ?: ""
            }
        }

    private fun loadResource(name: String): String? =
        javaClass
            .getResourceAsStream(AGREEMENT_RESOURCE_ROOT + name)
            ?.bufferedReader(Charsets.UTF_8)
            ?.use { it.readText() }
}

/** 首次启动的协议门：小窗口，拒绝即退出，旁边查看正文；同意后写入协议版本。 */
@Composable
internal fun AgreementPrompt(
    onView: () -> Unit,
    onDecline: () -> Unit,
    onAccept: () -> Unit,
    saving: Boolean = false,
    error: String? = null,
) {
    val strings = LocalStrings.current
    Dialog(onDismissRequest = {}, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            Modifier.width(460.dp),
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(Metrics.dialogRadius),
            border = BorderStroke(1.dp, hairline()),
            shadowElevation = 12.dp,
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    strings.t("agreement.prompt.title"),
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center,
                )
                Text(
                    strings.t("agreement.prompt.hint"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
                HorizontalDivider(color = hairline())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SecondaryButton(onDecline) { Text(strings.t("agreement.decline")) }
                    Spacer(Modifier.weight(1f))
                    PrimaryButton(strings.t("agreement.accept"), enabled = !saving, onClick = onAccept)
                    Spacer(Modifier.width(8.dp))
                    SecondaryButton(onView) { Text(strings.t("agreement.view")) }
                }
            }
        }
    }
}

/** 全窗口阅读页：覆盖整个启动器窗口，随时可返回，正文随界面语言切换。 */
@Composable
internal fun AgreementViewer(onClose: () -> Unit) {
    val strings = LocalStrings.current
    val body by produceState<String?>(null, strings.languageTag) {
        value = withContext(Dispatchers.IO) { AgreementCatalog.load(strings.languageTag) }
    }
    Surface(
        Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, strings.t("agreement.back"))
                }
                Text(
                    strings.t("agreement.title"),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
            }
            HorizontalDivider(color = hairline())
            ScrollColumn(Modifier.fillMaxSize()) {
                val document = body
                when {
                    document == null -> CircularProgressIndicator()
                    document.isBlank() -> MutedText(strings.t("agreement.missing"))
                    else -> MarkdownText(document)
                }
            }
        }
    }
}

internal fun UiActions.acceptAgreement() {
    if (agreementSaving) return
    agreementSaving = true
    agreementError = null
    run {
        try {
            val error =
                withContext(Dispatchers.IO) {
                    applySetting(this@acceptAgreement) { it.copy(agreementVersion = AGREEMENT_VERSION) }
                }
            withContext(Dispatchers.Swing) {
                agreementError =
                    if (controller.store.data.settings.agreementVersion == AGREEMENT_VERSION) {
                        null
                    } else {
                        tr("agreement.saveFailed", error ?: tr("miscA1.runtimeActions.operationFailed"))
                    }
            }
        } finally {
            withContext(NonCancellable + Dispatchers.Swing) { agreementSaving = false }
        }
    }
}
