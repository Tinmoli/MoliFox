// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责账户凭据的本地加密保存与读取。

package dev.molifox.core

import com.google.gson.JsonObject
import com.sun.jna.platform.win32.Crypt32Util
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
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
            require(token.isNotBlank() && bytes.size <= 32768) { "登录凭据格式无效，请重新登录" }
            val nonce = ByteArray(12).also(random::nextBytes)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce))
            cipher.updateAAD(associatedData(id))
            val envelope =
                JsonObject().apply {
                    addProperty("formatVersion", 1)
                    addProperty("protection", PROTECTION)
                    addProperty("nonce", Base64.getEncoder().encodeToString(nonce))
                    addProperty(
                        "payload",
                        Base64.getEncoder().encodeToString(cipher.doFinal(bytes)),
                    )
                }
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
            memory[id]?.let {
                return it
            }
            val target = file(id)
            if (target.exists()) {
                val decoded =
                    runCatching {
                        require(
                            target.isRegularFile() &&
                                !Files.isSymbolicLink(target) &&
                                target.fileSize() <= 65536,
                        )
                        val envelope = readObject(target)
                        require(
                            envelope.int("formatVersion") == 1 &&
                                envelope.string("protection") == PROTECTION,
                        )
                        val nonce = Base64.getDecoder().decode(envelope.string("nonce"))
                        require(nonce.size == 12)
                        val payload = Base64.getDecoder().decode(envelope.string("payload"))
                        require(payload.size in 17..32784)
                        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, nonce))
                        cipher.updateAAD(associatedData(id))
                        cipher.doFinal(payload).toString(Charsets.UTF_8).takeIf { it.isNotBlank() }
                    }.getOrNull()
                if (decoded ==
                    null
                ) {
                    store.log("账户凭据文件无法读取，请重新登录微软账户", true)
                } else {
                    memory[id] = decoded
                }
                // Never resurrect an older credential when a newer portable file is corrupt.
                return decoded
            }
            val previous = legacy.read(id)?.takeIf { it.isNotBlank() } ?: return null
            // Only retire the old entry after the new encrypted file has been written successfully.
            save(id, previous)
            runCatching { legacy.remove(id) }.onFailure { store.log("便携凭据已迁移，旧系统凭据未能清理", true) }
            store.log("账户登录凭据已迁移为便携保存")
            return previous
        }

        @Synchronized
        fun remove(id: String) {
            file(id).deleteIfExists()
            legacy.remove(id)
            memory.remove(id)
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
                if (!file.isRegularFile() || file.fileSize() > 65536) {
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
                if (!process.waitFor(5, TimeUnit.SECONDS) || process.exitValue() != 0) {
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
