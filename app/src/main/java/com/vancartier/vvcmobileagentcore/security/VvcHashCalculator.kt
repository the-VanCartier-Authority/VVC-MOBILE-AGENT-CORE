package com.vancartier.vvcmobileagentcore.security

import android.content.res.AssetManager
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale

object VvcHashCalculator {
    private const val SHA_256 = "SHA-256"
    private const val BUFFER_SIZE = 1024 * 1024

    fun calculateFileSha256(file: File): String {
        require(file.exists() && file.isFile) { "Archivo inválido para hash: ${file.absolutePath}" }
        return file.inputStream().use { inputStream -> calculateStreamSha256(inputStream) }
    }

    fun calculateAssetSha256(assetManager: AssetManager, assetPath: String): String {
        return assetManager.open(assetPath).use { inputStream -> calculateStreamSha256(inputStream) }
    }

    fun readAssetSha256Sidecar(assetManager: AssetManager, sidecarPath: String): String? {
        return runCatching {
            assetManager.open(sidecarPath).bufferedReader(Charsets.UTF_8).use { reader ->
                reader.readText()
                    .lineSequence()
                    .map { line -> line.trim() }
                    .firstOrNull { line -> line.isNotEmpty() && !line.startsWith("#") }
                    ?.split(Regex("\\s+"))
                    ?.firstOrNull()
                    ?.lowercase(Locale.US)
            }
        }.getOrNull()
    }

    fun verifyAssetAgainstSidecar(assetManager: AssetManager, assetPath: String): Boolean {
        val expected = readAssetSha256Sidecar(assetManager, "$assetPath.sha256") ?: return false
        val actual = calculateAssetSha256(assetManager, assetPath)
        return actual.equals(expected, ignoreCase = true)
    }

    private fun calculateStreamSha256(inputStream: InputStream): String {
        val digest = MessageDigest.getInstance(SHA_256)
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            val read = inputStream.read(buffer)
            if (read < 0) {
                break
            }
            if (read > 0) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
    }
}
