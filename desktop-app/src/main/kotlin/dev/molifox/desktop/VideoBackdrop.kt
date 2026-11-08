// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 视频背景：运行库就绪后才创建播放器，首帧就绪后才让画面进组合；声音、暂停、循环和画面裁切都跟随外观设置。
package dev.molifox.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.molifox.core.LauncherSettings
import dev.molifox.core.extractBackgroundSeed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.openani.mediamp.MediaStatus
import org.openani.mediamp.MediampPlayer
import org.openani.mediamp.MediampPlayerFactoryLoader
import org.openani.mediamp.compose.MediampPlayerSurface
import org.openani.mediamp.compose.MediampPlayerSurfaceProviderLoader
import org.openani.mediamp.compose.rememberMediampPlayer
import org.openani.mediamp.features.AspectRatioMode
import org.openani.mediamp.features.AudioLevelController
import org.openani.mediamp.features.FramePreview
import org.openani.mediamp.features.VideoAspectRatio
import org.openani.mediamp.isMediaLoaded
import org.openani.mediamp.mpv.MpvMediampPlayerFactory
import org.openani.mediamp.mpv.compose.MpvMediampPlayerSurfaceProvider
import org.openani.mediamp.source.UriMediaData
import java.nio.file.Path
import java.util.logging.Logger

private const val ACCENT_FRAME_WIDTH = 160
private const val ACCENT_FRAME_HEIGHT = 90
private const val ACCENT_FRAME_ATTEMPTS = 3
private const val ACCENT_FRAME_RETRY_MILLIS = 500L
private const val ACCENT_FRAME_SETTLE_MILLIS = 700L
private const val ACCENT_FRAME_WAIT_MILLIS = 10_000L
private const val FIRST_FRAME_WAIT_MILLIS = 10_000L
private const val RGB_MASK = 0xFFFFFF

/** 视频背景的技术日志；用户可见提示走设置页与下载弹窗，这里只记录降级原因。 */
private val videoLogger = Logger.getLogger("MoliFox.video")

@Composable
internal fun VideoBackdrop(
    dataDirectory: Path,
    videoPath: String,
    settings: LauncherSettings,
    paused: Boolean,
    onAccent: (String?) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var ready by remember(dataDirectory) { mutableStateOf(false) }
    LaunchedEffect(dataDirectory, videoPath) {
        ready =
            withContext(Dispatchers.IO) {
                VideoRuntime.prepareInstalled(dataDirectory)
            }
    }
    if (!ready) {
        // 运行库检查完成前不画任何东西：露出下层 LauncherBackdrop 的主题背景色。
        return
    }
    // 首次启动会话里 BootstrapWindow 已把 AWT 事件线程提前创建，它的 context classloader
    // 仍是没有依赖 jar 的系统加载器，ServiceLoader 扫不到 mpv-desktop 的 services 声明；
    // 播放器工厂与 surface 提供方都要显式注册，漏了后者会在 surface 组合时抛
    // 「No MediampPlayerFactory implementation found」并让整个启动器退出。
    remember {
        MediampPlayerFactoryLoader.register(MpvMediampPlayerFactory())
        MediampPlayerSurfaceProviderLoader.register(MpvMediampPlayerSurfaceProvider())
        Unit
    }
    val player = rememberMediampPlayer()
    // 播放器调用都在组合协程里：打开/暂停失败只降级为主题背景色并记录技术日志，
    // 异常一旦冒出组合会触发 bootstrap 的「遇到问题」弹窗并退出整个启动器。
    LaunchedEffect(player, videoPath) {
        runCatching {
            player.setMediaData(UriMediaData(Path.of(videoPath).toUri().toString()), playWhenReady = true)
        }.onFailure { failure ->
            videoLogger.warning("Video backdrop open failed: ${failure.message}")
        }
    }
    LaunchedEffect(player, paused) {
        runCatching { if (paused) player.pause() else player.play() }
            .onFailure { failure -> videoLogger.warning("Video playback toggle failed: ${failure.message}") }
    }
    // 声音跟随设置；它不依赖画面，放在首帧门闸之前，避免等待期间漏放视频音轨。
    val audio = remember(player) { runCatching { player.features.get(AudioLevelController.Key) }.getOrNull() }
    LaunchedEffect(audio, settings.videoSoundEnabled, settings.videoVolume) {
        audio ?: return@LaunchedEffect
        audio.setMute(!settings.videoSoundEnabled)
        if (settings.videoSoundEnabled) audio.setVolume(audio.maxVolume * settings.videoVolume.coerceIn(0f, 1f))
    }
    // MPV 渲染线程在第一帧视频解码出来之前会先把清屏黑发布进环形缓冲，surface 一进
    // 组合就会把这段黑画出来——「先白底、再黑闪、才出画面」就是这个窗口。等播放真正
    // 开始（或暂停态下媒体已载入）再让 surface 进组合，首帧即是真实画面；等待超时视为
    // 视频不可用，停住播放并保持主题背景色，避免只有声音没有画面。
    var surfaceReady by remember(player) { mutableStateOf(false) }
    LaunchedEffect(player, paused) {
        surfaceReady =
            withTimeoutOrNull(FIRST_FRAME_WAIT_MILLIS) {
                player.state.first { it.isPlaying || (paused && it.isMediaLoaded) }
            } != null
        if (!surfaceReady) {
            videoLogger.warning("Video first frame not ready in time; keeping theme backdrop")
            runCatching { player.pause() }
        }
    }
    if (!surfaceReady) {
        // 首帧确认前不画任何东西：露出下层 LauncherBackdrop 的主题背景色。
        return
    }
    // 主题种子只取一帧：FramePreview 的 CPU 回读不能进入播放循环，否则又会回到卡顿。
    val accentCallback by rememberUpdatedState(onAccent)
    LaunchedEffect(player, videoPath) {
        accentCallback(null)
        videoAccentHex(player)?.let(accentCallback)
    }
    LaunchedEffect(player) {
        player.state.collect { state ->
            if (state.mediaStatus is MediaStatus.Ended) {
                runCatching {
                    player.seekTo(0)
                    player.play()
                }.onFailure { failure -> videoLogger.warning("Video loop restart failed: ${failure.message}") }
            }
        }
    }
    val aspect = remember(player) { runCatching { player.features.get(VideoAspectRatio.Key) }.getOrNull() }
    LaunchedEffect(aspect, settings.backgroundFit) {
        aspect ?: return@LaunchedEffect
        aspect.setMode(
            when (settings.backgroundFit) {
                "fit" -> AspectRatioMode.FIT
                "stretch" -> AspectRatioMode.STRETCH
                else -> AspectRatioMode.CROP
            },
        )
    }
    Box(modifier.fillMaxSize()) {
        // MPV 的 Compose surface 自己维护原生渲染线程和合成纹理；不要再叠加
        // FramePreview 的 CPU 回读，否则每帧都会整屏解码并拷贝，画面变糊且掉帧。
        MediampPlayerSurface(player, Modifier.fillMaxSize())
    }
}

/** 播放器就绪后只取一帧，避免把 FramePreview 的整帧回读带回播放循环。 */
private suspend fun videoAccentHex(player: MediampPlayer): String? {
    val preview = runCatching { player.features.get(FramePreview.Key) }.getOrNull()
    val ready =
        withTimeoutOrNull(ACCENT_FRAME_WAIT_MILLIS) {
            player.state.first { it.isPlaying || it.isMediaLoaded }
        } != null
    if (preview == null || !ready) return null
    delay(ACCENT_FRAME_SETTLE_MILLIS)
    repeat(ACCENT_FRAME_ATTEMPTS) {
        val position = player.currentPositionMillis.value.coerceAtLeast(0L)
        val frame =
            runCatching {
                preview.getPreviewFrame(position, ACCENT_FRAME_WIDTH, ACCENT_FRAME_HEIGHT)
            }.getOrNull()
        if (frame != null && frame.pixels.isNotEmpty()) {
            val seed = withContext(Dispatchers.Default) { extractBackgroundSeed(frame.pixels) }
            return "#%06X".format(seed and RGB_MASK)
        }
        delay(ACCENT_FRAME_RETRY_MILLIS)
    }
    return null
}
