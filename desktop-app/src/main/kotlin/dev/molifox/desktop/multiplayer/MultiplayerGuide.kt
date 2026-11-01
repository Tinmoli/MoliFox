// 展示房主与房客的“人话”教程：只讲操作顺序和常见问题，不堆协议术语。
package dev.molifox.desktop.multiplayer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.molifox.desktop.LocalStrings
import dev.molifox.desktop.MutedText
import dev.molifox.desktop.tr

@Composable
internal fun MultiplayerGuide(host: Boolean) {
    val strings = LocalStrings.current
    val steps = if (host) hostSteps() else guestSteps()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        MultiplayerGroup(
            Modifier.fillMaxWidth(),
            title =
                if (host) {
                    strings.t("dialogs.mp.tab.hostGuide")
                } else {
                    strings.t("dialogs.mp.tab.guestGuide")
                },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                steps.forEachIndexed { index, step ->
                    Text("${index + 1}. $step", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        MultiplayerGroup(Modifier.fillMaxWidth(), title = strings.t("dialogs.mp.guide.faq")) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                faqItems().forEach { (question, answer) ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(question, style = MaterialTheme.typography.bodyMedium)
                        MutedText(answer)
                    }
                }
            }
        }
        MultiplayerGroup(Modifier.fillMaxWidth(), title = strings.t("dialogs.mp.guide.notes")) {
            Text(boundaryNotice(), style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun hostSteps(): List<String> =
    listOf(
        tr("dialogs.mp.guide.host.step1"),
        tr("dialogs.mp.guide.host.step2"),
        tr("dialogs.mp.guide.host.step3"),
        tr("dialogs.mp.guide.host.step4"),
        tr("dialogs.mp.guide.host.step5"),
    )

private fun guestSteps(): List<String> =
    listOf(
        tr("dialogs.mp.guide.guest.step1"),
        tr("dialogs.mp.guide.guest.step2"),
        tr("dialogs.mp.guide.guest.step3"),
        tr("dialogs.mp.guide.guest.step4"),
        tr("dialogs.mp.guide.guest.step5"),
    )

/** 常见问题：覆盖最容易卡住用户的几件事，答案只描述启动器确实会做的事。 */
private fun faqItems(): List<Pair<String, String>> =
    listOf(
        tr("dialogs.mp.guide.faq1q") to tr("dialogs.mp.guide.faq1a"),
        tr("dialogs.mp.guide.faq2q") to tr("dialogs.mp.guide.faq2a"),
        tr("dialogs.mp.guide.faq3q") to tr("dialogs.mp.guide.faq3a"),
        tr("dialogs.mp.guide.faq4q") to tr("dialogs.mp.guide.faq4a"),
        tr("dialogs.mp.guide.faq5q") to tr("dialogs.mp.guide.faq5a"),
        tr("dialogs.mp.guide.faq6q") to tr("dialogs.mp.guide.faq6a"),
    )

private fun boundaryNotice(): String = tr("dialogs.mp.guide.boundary")
