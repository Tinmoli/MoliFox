// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责账户凭据的本地加密保存与读取。

package dev.molifox.core

import com.google.gson.JsonObject
import com.sun.jna.platform.win32.Crypt32Util
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.io.path.bufferedReader
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.inputStream
import kotlin.io.path.isRegularFile
import kotlin.io.path.isSymbolicLink
import kotlin.io.path.name
import kotlin.io.path.readBytes
import kotlin.io.path.readText

private const val MAX_TOKEN_PLAINTEXT_BYTES = 32768
private const val TOKEN_GCM_TAG_BITS = 128
private const val MAX_WINDOWS_CREDENTIAL_BYTES = 65536
private const val CREDENTIAL_TOOL_TIMEOUT_SECONDS = 5L

/** AES-GCM 安全随机 nonce 长度（字节）。 */
private const val GCM_NONCE_BYTES = 12

/** 凭据信封文件的最大体积；超过视为损坏，不再解析。 */
private const val MAX_ENVELOPE_FILE_BYTES = 65536

/** 信封 payload 的最小长度：1 字节明文 + GCM 认证标签。 */
private const val MIN_ENVELOPE_PAYLOAD_BYTES = 17

/** 信封 payload 的最大长度：明文上限 + GCM 认证标签。 */
private const val MAX_ENVELOPE_PAYLOAD_BYTES = 32784

/**
 * Portable AES-GCM credential storage. The application key is intentionally shared between
 * installations: this hides readable tokens on disk but is not OS-bound secret storage. Passwords
 * and private OAuth device codes are never saved here.
 */
class TokenVault
    internal constructor(
        private val store: LauncherStore,
        private val legacy: LegacyTokenStore,
    ) {
        constructor(store: LauncherStore) : this(store, SystemLegacyTokenStore(store))

        private val memory = mutableMapOf<String, String>()
        val persistent = true
        private val folder
            get() = store.dataDirectory.resolve("credentials")

        private fun file(id: String) = safePath(folder, "${safeFilename(id)}.json")

        @Synchronized
        fun save(
            id: String,
            token: String,
        ) {
            val target = file(id)
            val bytes = token.toByteArray(Charsets.UTF_8)
            require(token.isNotBlank() && bytes.size <= MAX_TOKEN_PLAINTEXT_BYTES) {
                "登录凭据格式无效，请重新登录"
            }
            val envelope = encryptEnvelope(id, bytes)
            target.parent.createDirectories()
            val temporary = target.resolveSibling(".${target.name}-${UUID.randomUUID()}.tmp")
            try {
                Files.writeString(
                    temporary,
                    gson.toJson(envelope),
                    StandardOpenOption.CREATE_NEW,
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
                memory[id] = token
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
                    store.log("账户凭据文件无法读取，请重新登录微软账户", true)
                } else {
                    memory[id] = decoded
                }
                // Never resurrect an older credential when a newer portable file is corrupt.
                decoded
            } else {
                val previous = legacy.read(id)?.takeIf { it.isNotBlank() }
                if (previous != null) {
                    // Only retire the old entry after the new encrypted file has been written
                    // successfully.
                    save(id, previous)
                    runCatching { legacy.remove(id) }.onFailure {
                        store.log("便携凭据已迁移，旧系统凭据未能清理", true)
                    }
                    store.log("账户登录凭据已迁移为便携保存")
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

        private fun encryptEnvelope(
            id: String,
            bytes: ByteArray,
        ): JsonObject {
            val nonce = ByteArray(12).also(random::nextBytes)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TOKEN_GCM_TAG_BITS, nonce))
            cipher.updateAAD(associatedData(id))
            return JsonObject().apply {
                addProperty("formatVersion", 1)
                addProperty("protection", PROTECTION)
                addProperty("nonce", Base64.getEncoder().encodeToString(nonce))
                addProperty(
                    "payload",
                    Base64.getEncoder().encodeToString(cipher.doFinal(bytes)),
                )
            }
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
            val envelope = readObject(target)
            require(
                envelope.int("formatVersion") == 1 &&
                    envelope.string("protection") == PROTECTION,
            )
            val nonce = Base64.getDecoder().decode(envelope.string("nonce"))
            require(nonce.size == GCM_NONCE_BYTES)
            val payload = Base64.getDecoder().decode(envelope.string("payload"))
            require(payload.size in MIN_ENVELOPE_PAYLOAD_BYTES..MAX_ENVELOPE_PAYLOAD_BYTES)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TOKEN_GCM_TAG_BITS, nonce))
            cipher.updateAAD(associatedData(id))
            return cipher.doFinal(payload).toString(Charsets.UTF_8).takeIf { it.isNotBlank() }
        }

        private fun associatedData(id: String) = "$PROTECTION|$id".toByteArray(Charsets.UTF_8)

        private companion object {
            const val PROTECTION = "molifox-portable-aesgcm-v1"
            val random = SecureRandom()

            // Public format key, not a secret. Keep stable across releases so portable data stays
            // readable.
            val key =
                SecretKeySpec(
                    MessageDigest
                        .getInstance("SHA-256")
                        .digest(
                            "MoliFox/portable-refresh/v1/7ae1fe67-8045-4a28-8ccc-dc0bcc353e02"
                                .toByteArray(Charsets.UTF_8),
                        ),
                    "AES",
                )
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
    private fun windowsFile(id: String) =
        safePath(store.dataDirectory.resolve("credentials"), "${safeFilename(id)}.bin")

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
