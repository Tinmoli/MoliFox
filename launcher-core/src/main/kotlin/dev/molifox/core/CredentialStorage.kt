// SPDX-FileCopyrightText: 2026 Tinmoli and MoliFox Contributors
// SPDX-License-Identifier: GPL-3.0-only

// 负责账户凭据的存放位置与落盘保护方式：凭据不跟随便携数据目录；
// Windows 用 DPAPI(当前用户) 保护的用户密钥做 AES-GCM，其他平台不加密、只移出便携目录。

package dev.molifox.core

import com.google.gson.JsonObject
import com.sun.jna.platform.win32.Crypt32Util
import dev.molifox.core.i18n.I18n
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.SecureRandom
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readBytes

/** 用户密钥长度：AES-256。 */
private const val USER_KEY_BYTES = 32

/** 用户密钥文件体积上限；超过视为损坏并隔离重建。 */
private const val MAX_USER_KEY_FILE_BYTES = 4096

/** AES-GCM 安全随机 nonce 长度（字节）。 */
private const val GCM_NONCE_BYTES = 12

/** AES-GCM 认证标签长度（位）。 */
private const val GCM_TAG_BITS = 128

/** 隔离文件的时间后缀。 */
private val BROKEN_KEY_STAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")

/** Windows 信封保护标识。 */
private const val DPAPI_PROTECTION = "molifox-dpapi-aesgcm-v1"

/** 非 Windows 平台信封保护标识：不加密，仅保持文件结构一致。 */
private const val PLAIN_PROTECTION = "molifox-plaintext-v1"

/**
 * 凭据目录：[LauncherDirectories] 之外的位置，避免便携数据被整体复制后带走凭据。
 * Windows 用 LOCALAPPDATA（Roaming 会随域漫游，不放密钥材料），其他平台用 XDG_DATA_HOME。
 */
internal fun credentialDirectory(
    osName: String,
    environment: Map<String, String> = System.getenv(),
    home: Path = Path.of(System.getProperty("user.home")),
): Path {
    if (osName == "windows") {
        val base =
            environment["LOCALAPPDATA"]?.takeIf { it.isNotBlank() }?.let(Path::of)
                ?: home.resolve("AppData").resolve("Local")
        return base.resolve("MoliFox").resolve("credentials")
    }
    val base =
        environment["XDG_DATA_HOME"]?.takeIf { it.isNotBlank() }?.let(Path::of)
            ?: home.resolve(".local").resolve("share")
    return base.resolve("molifox").resolve("credentials")
}

/** 按平台选择凭据保护方式。 */
internal fun defaultCredentialProtection(
    osName: String,
    folder: Path,
): CredentialProtection =
    if (osName == "windows") {
        DpapiKeyProtection(folder.resolve("user-key.bin"))
    } else {
        PlainCredentialProtection()
    }

/** 把明文封装成可落盘的 JSON 信封；实现决定是否真正加密。 */
internal interface CredentialProtection {
    /** 信封中的 `protection` 标识。 */
    val name: String

    /** 是否真正加密落盘；false 时调用方应提示凭据为明文保存。 */
    val protectsAtRest: Boolean

    fun seal(
        id: String,
        plain: ByteArray,
    ): JsonObject

    /** 解出明文；格式、账户绑定或认证失败时返回 null。 */
    fun open(
        id: String,
        envelope: JsonObject,
    ): ByteArray?
}

/**
 * Windows：随机用户密钥由 DPAPI(当前用户) 保护后落在 `user-key.bin`，令牌用该密钥 AES-GCM 封装。
 * 只有当前 Windows 用户能解封密钥；DPAPI 不可用时拒绝保存，不回退到程序内置密钥。
 */
internal class DpapiKeyProtection(
    private val keyFile: Path,
) : CredentialProtection {
    override val name = DPAPI_PROTECTION
    override val protectsAtRest = true
    private val random = SecureRandom()
    private val key: SecretKeySpec by lazy { loadOrCreateKey() }

    override fun seal(
        id: String,
        plain: ByteArray,
    ): JsonObject {
        val nonce = ByteArray(GCM_NONCE_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, nonce))
        cipher.updateAAD(associatedData(id))
        return JsonObject().apply {
            addProperty("formatVersion", 1)
            addProperty("protection", name)
            addProperty("account", id)
            addProperty("nonce", Base64.getEncoder().encodeToString(nonce))
            addProperty("payload", Base64.getEncoder().encodeToString(cipher.doFinal(plain)))
        }
    }

    override fun open(
        id: String,
        envelope: JsonObject,
    ): ByteArray? =
        runCatching {
            require(envelope.int("formatVersion") == 1 && envelope.string("protection") == name)
            require(envelope.string("account") == id)
            val nonce = Base64.getDecoder().decode(envelope.string("nonce"))
            require(nonce.size == GCM_NONCE_BYTES)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, nonce))
            cipher.updateAAD(associatedData(id))
            cipher.doFinal(Base64.getDecoder().decode(envelope.string("payload")))
        }.getOrNull()

    private fun associatedData(id: String) = "$name|$id".toByteArray(Charsets.UTF_8)

    private fun loadOrCreateKey(): SecretKeySpec {
        readKey()?.let { return it }
        val fresh = ByteArray(USER_KEY_BYTES).also(random::nextBytes)
        val protected = protect(fresh)
        val verified = runCatching { Crypt32Util.cryptUnprotectData(protected) }.getOrNull()
        require(verified != null && verified.contentEquals(fresh)) {
            I18n.t("miscA2.tokenVault.keyProtectionSelfCheckFailed")
        }
        writeAtomically(protected)
        return SecretKeySpec(fresh, "AES")
    }

    private fun readKey(): SecretKeySpec? {
        val usable =
            keyFile.isRegularFile() &&
                !Files.isSymbolicLink(keyFile) &&
                keyFile.fileSize() <= MAX_USER_KEY_FILE_BYTES
        val plain =
            if (usable) {
                runCatching { Crypt32Util.cryptUnprotectData(keyFile.readBytes()) }.getOrNull()
            } else {
                null
            }
        if (plain != null && plain.size == USER_KEY_BYTES) return SecretKeySpec(plain, "AES")
        quarantine()
        return null
    }

    private fun protect(bytes: ByteArray): ByteArray =
        runCatching { Crypt32Util.cryptProtectData(bytes) }.getOrElse { failure ->
            throw IllegalStateException(I18n.t("miscA2.tokenVault.keyProtectionUnavailable"), failure)
        }

    /** 解不开的旧密钥改名隔离；下次保存重新生成，避免整份凭据静默失效。 */
    private fun quarantine() {
        if (!keyFile.exists()) return
        val stamp = LocalDateTime.now().format(BROKEN_KEY_STAMP)
        val broken = keyFile.resolveSibling("${keyFile.name}.broken-$stamp")
        runCatching { Files.move(keyFile, broken, StandardCopyOption.REPLACE_EXISTING) }
    }

    private fun writeAtomically(bytes: ByteArray) {
        keyFile.parent.createDirectories()
        val temporary = keyFile.resolveSibling(".${keyFile.name}-${UUID.randomUUID()}.tmp")
        try {
            Files.write(temporary, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
            try {
                Files.move(
                    temporary,
                    keyFile,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, keyFile, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temporary.deleteIfExists()
        }
    }
}

/**
 * 其他平台：不做加密，只把凭据移出便携目录。
 * 明确不提供机密性：同用户的任意进程都能读出刷新令牌；界面与文档需按此说明。
 */
internal class PlainCredentialProtection : CredentialProtection {
    override val name = PLAIN_PROTECTION
    override val protectsAtRest = false

    override fun seal(
        id: String,
        plain: ByteArray,
    ): JsonObject =
        JsonObject().apply {
            addProperty("formatVersion", 1)
            addProperty("protection", name)
            addProperty("account", id)
            addProperty("payload", Base64.getEncoder().encodeToString(plain))
        }

    override fun open(
        id: String,
        envelope: JsonObject,
    ): ByteArray? =
        runCatching {
            require(envelope.int("formatVersion") == 1 && envelope.string("protection") == name)
            require(envelope.string("account") == id)
            Base64.getDecoder().decode(envelope.string("payload"))
        }.getOrNull()
}
