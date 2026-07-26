package dev.aidd.extractor.kotlin

import java.security.MessageDigest

internal fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte) }

internal fun sha256(text: String): String = sha256(text.toByteArray(Charsets.UTF_8))
