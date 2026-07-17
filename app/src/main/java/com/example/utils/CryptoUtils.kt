package com.example.utils

import java.security.MessageDigest
import java.security.SecureRandom

object CryptoUtils {
  fun generateSalt(length: Int = 16): ByteArray {
    val salt = ByteArray(length)
    SecureRandom().nextBytes(salt)
    return salt
  }

  fun hashWithSalt(input: String, salt: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update(salt)
    val hash = digest.digest(input.toByteArray(Charsets.UTF_8))
    return hash.joinToString("") { "%02x".format(it) }
  }

  fun verifyHash(input: String, salt: ByteArray, hash: String): Boolean {
    return hashWithSalt(input, salt) == hash
  }
}
