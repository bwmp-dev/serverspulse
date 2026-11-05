package com.serverspulse.agent.core

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal class PlayerIdentity(folder: File) {
    private val key: ByteArray

    init {
        folder.mkdirs()
        val path = File(folder, "player-identity.key").toPath()
        if (!Files.exists(path)) {
            val generated = ByteArray(32).also { SecureRandom().nextBytes(it) }
            try {
                Files.write(path, generated, StandardOpenOption.CREATE_NEW)
            } catch (_: java.nio.file.FileAlreadyExistsException) {
                // Another runtime created the persistent key first.
            }
        }
        key = Files.readAllBytes(path)
        require(key.size == 32) { "Invalid player identity key" }
    }

    fun uuid(raw: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        val bytes = mac.doFinal(raw.lowercase(java.util.Locale.ROOT).toByteArray(Charsets.UTF_8))
        bytes[6] = ((bytes[6].toInt() and 15) or 64).toByte()
        bytes[8] = ((bytes[8].toInt() and 63) or 128).toByte()
        val buffer = java.nio.ByteBuffer.wrap(bytes)
        return UUID(buffer.long, buffer.long).toString()
    }

    fun username(rawUuid: String): String = "p_" + uuid(rawUuid).replace("-", "").take(14)
}
