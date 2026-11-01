// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责启动前解析版本配置，配置缺失时按原有语义恢复。

package dev.molifox.core

import dev.molifox.core.i18n.I18n
import kotlinx.coroutines.CancellationException
import java.io.IOException

/** 启动资料解析：先按现有配置解析，失败时外部版本直接报错、受管实例重装后重试。 */
internal class LaunchProfileResolver(
    private val store: LauncherStore,
    private val pipeline: MinecraftInstallPipeline,
) {
    suspend fun resolveForLaunch(
        instance: GameInstance,
        context: JobContext,
    ): ResolvedProfile {
        val resolver = VersionResolver(store.minecraftRoot(instance), store.platform)
        return try {
            resolver.resolve(instance.versionId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: IOException) {
            recoverProfile(instance, resolver, context, error)
        } catch (error: IllegalStateException) {
            recoverProfile(instance, resolver, context, error)
        } catch (error: IllegalArgumentException) {
            recoverProfile(instance, resolver, context, error)
        }
    }

    private suspend fun recoverProfile(
        instance: GameInstance,
        resolver: VersionResolver,
        context: JobContext,
        error: Exception,
    ): ResolvedProfile {
        if (instance.externalProfile) {
            throw IOException(
                I18n.t("diagA2.launchProfileResolver.externalProfileBroken", instance.versionId),
                error,
            )
        }
        context.progress(0f, I18n.t("diagA2.launchProfileResolver.restoreProfile"))
        pipeline.install(instance.id, context)
        return resolver.resolve(store.instance(instance.id).versionId)
    }
}
