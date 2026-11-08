// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责账户凭据文件的读写与旧凭据迁移；存放位置与保护方式见 CredentialStorage.kt。

package dev.molifox.core

import com.sun.jna.platform.win32.Crypt32Util
import dev.molifox.core.i18n.I18n
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readBytes

private const val MAX_TOKEN_PLAINTEXT_BYTES = 32768
private const val MAX_WINDOWS_CREDENTIAL_BYTES = 65536
private const val CREDENTIAL_TOOL_TIMEOUT_SECONDS = 5L

/** 凭据信封文件的最大体积；超过视为损坏，不再解析。 */
private const val MAX_ENVELOPE_FILE_BYTES = 65536

/** 信封解出的载荷上限：明文上限 + GCM 认证标签。 */
private const val MAX_ENVELOPE_PAYLOAD_BYTES = 32784

/**
 * 账户刷新令牌的本地保存。位置与保护方式由 [CredentialProtection] 决定：
 * Windows 用 DPAPI 保护的用户密钥加密，其他平台只把文件移出便携目录、不加密。
 * 密码与 OAuth 设备码不保存在这里。
 */
class TokenVault
    internal constructor(
        private val store: LauncherStore,
        private val legacy: LegacyTokenStore,
        private val folder: Path = credentialDirectory(store.platform.osName),
        private val protection: CredentialProtection =
            defaultCredentialProtection(store.platform.osName, folder),
    ) {
        constructor(store: LauncherStore) : this(store, SystemLegacyTokenStore(store))

        private val memory = mutableMapOf<String, String>()
        private var warnedPlainStorage = false
        val persistent = true

        private fun file(id: String) = safePath(folder, "${safeFilename(id)}.json")

        @Synchronized
        fun save(
            id: String,
            token: String,
        ) {
            val target = file(id)
            val bytes = token.toByteArray(Charsets.UTF_8)
            require(token.isNotBlank() && bytes.size <= MAX_TOKEN_PLAINTEXT_BYTES) {
                I18n.t("miscA2.tokenVault.invalidCredentialFormat")
            }
            // 先封装再建目录：密钥不可用时不会留下半成品目录。
            val envelope = protection.seal(id, bytes)
            createCredentialDirectory(folder)
            val temporary = target.resolveSibling(".${target.name}-${UUID.randomUUID()}.tmp")
            try {
                createPrivateCredentialFile(temporary)
                Files.writeString(
                    temporary,
                    gson.toJson(envelope),
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE,
                )
                try {
                    Files.move(
                        temporary,
                        target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
                }
                restrictCredentialFile(target)
                memory[id] = token
                warnPlainStorageOnce()
            } finally {
                temporary.deleteIfExists()
            }
        }

        @Synchronized
        fun read(id: String): String? {
            val cached = memory[id]
            if (cached != null) return cached
            val target = file(id)
            return if (target.exists()) {
                val decoded =
                    runCatching {
                        decodeEnvelope(target, id)
                    }.getOrNull()
                if (decoded == null) {
                    store.log(I18n.t("miscA2.tokenVault.unreadableCredentialFile"), true)
                } else {
                    memory[id] = decoded
                }
                // Never resurrect an older credential when a newer file is corrupt.
                decoded
            } else {
                val previous = legacy.read(id)?.takeIf { it.isNotBlank() }
                if (previous != null) {
                    // Only retire the old entry after the new encrypted file has been written
                    // successfully.
                    save(id, previous)
                    runCatching { legacy.remove(id) }.onFailure {
                        store.log(I18n.t("miscA2.tokenVault.legacyCleanupFailed"), true)
                    }
                    store.log(I18n.t("miscA2.tokenVault.migratedToLocalStorage"))
                }
                previous
            }
        }

        @Synchronized
        fun remove(id: String) {
            file(id).deleteIfExists()
            legacy.remove(id)
            memory.remove(id)
        }

        private fun decodeEnvelope(
            target: Path,
            id: String,
        ): String? {
            require(
                target.isRegularFile() &&
                    !Files.isSymbolicLink(target) &&
                    target.fileSize() <= MAX_ENVELOPE_FILE_BYTES,
            )
            val payload = protection.open(id, readObject(target)) ?: return null
            require(payload.size in 1..MAX_ENVELOPE_PAYLOAD_BYTES)
            return payload.toString(Charsets.UTF_8).takeIf { it.isNotBlank() }
        }

        /** 明文保存时只在本次运行记录一次，避免每次保存都刷日志。 */
        private fun warnPlainStorageOnce() {
            if (protection.protectsAtRest || warnedPlainStorage) return
            warnedPlainStorage = true
            val message = I18n.t("miscA2.tokenVault.plainStorageNotice", folder)
            store.log(message, false)
            store.notify(message)
        }
    }

internal interface LegacyTokenStore {
    fun read(id: String): String?

    fun remove(id: String)
}

/** Read-only compatibility with previous MoliFox releases; no new system-keyring entries. */
private class SystemLegacyTokenStore(
    private val store: LauncherStore,
) : LegacyTokenStore {
    private fun windowsFile(id: String) = safePath(store.directories.credentials, "${safeFilename(id)}.bin")

    override fun read(id: String): String? =
        runCatching {
            if (store.platform.osName == "windows") {
                val file = windowsFile(id)
                if (!file.isRegularFile() || file.fileSize() > MAX_WINDOWS_CREDENTIAL_BYTES) {
                    null
                } else {
                    Crypt32Util.cryptUnprotectData(file.readBytes()).toString(Charsets.UTF_8)
                }
            } else {
                keyring("lookup", id)
            }
        }.getOrNull()

    override fun remove(id: String) {
        if (store.platform.osName == "windows") {
            windowsFile(id).deleteIfExists()
        } else {
            keyring("clear", id)
        }
    }

    private fun keyring(
        action: String,
        id: String,
    ): String? =
        runCatching {
            val process =
                ProcessBuilder("secret-tool", action, "application", "molifox", "account", id)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
            try {
                val finished = process.waitFor(CREDENTIAL_TOOL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                if (!finished || process.exitValue() != 0) {
                    null
                } else {
                    process.inputStream
                        .bufferedReader()
                        .readText()
                        .trimEnd()
                        .takeIf { it.isNotBlank() }
                }
            } finally {
                if (process.isAlive) process.destroyForcibly()
            }
        }.getOrNull()
}
