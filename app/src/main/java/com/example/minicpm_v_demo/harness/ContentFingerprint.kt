package com.example.minicpm_v_demo.harness

import java.security.MessageDigest

/**
 * Short, content-free fingerprints for local prompt diagnostics.
 *
 * Callers must retain the fingerprint only.  In particular, do not use this
 * helper as a reason to log the input text that was hashed.
 */
object ContentFingerprint {
    /** 将 UTF-8 字符串的 SHA-256 摘要截短，用于诊断中关联同一份内容。 */
    fun sha256Short(value: String, length: Int = 16): String {
        require(length in 8..64) { "fingerprint length must be between 8 and 64" }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
        return digest.take(length)
    }
}
