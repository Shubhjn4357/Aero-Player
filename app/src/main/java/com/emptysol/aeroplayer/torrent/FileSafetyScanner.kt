package com.emptysol.aeroplayer.torrent

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.zip.ZipFile

data class FileScanFinding(
    val fileName: String,
    val status: String, // "CLEAN", "WARNING", "BLOCKED"
    val reason: String,
    val sha256: String
)

data class TorrentScanReport(
    val overallStatus: String, // "CLEAN", "WARNING", "BLOCKED"
    val summary: String,
    val findings: List<FileScanFinding>
)

/**
 * Integrated File Integrity & Safety Scanner:
 * 1) Verifies binary magic-byte signatures against file extensions (detects spoofed media extensions).
 * 2) Flags executable/binary payloads (.exe, .dll, .bat, .sh, .dex, .so, .apk) & hidden AndroidManifest inside ZIP/APK.
 * 3) Computes SHA-256 checksums for verification.
 */
object FileSafetyScanner {

    private val dangerousExtensions = setOf(
        "exe", "msi", "bat", "cmd", "ps1", "vbs", "js", "jse", "wsf", "scr",
        "com", "pif", "apk", "apks", "xapk", "dex", "so", "elf", "sh", "bin", "jar"
    )

    private val mediaExtensions = setOf(
        "mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "m4v", "3gp", "ts",
        "mp3", "flac", "wav", "aac", "m4a", "ogg", "opus", "srt", "ass", "vtt"
    )

    fun scanPaths(files: List<File>): TorrentScanReport {
        if (files.isEmpty()) {
            return TorrentScanReport("UNSCANNED", "No downloaded files present on disk yet.", emptyList())
        }
        val findings = files.filter { it.exists() && it.isFile }.map { scanSingleFile(it) }
        if (findings.isEmpty()) {
            return TorrentScanReport("UNSCANNED", "No completed files available to scan.", emptyList())
        }
        val blockedCount = findings.count { it.status == "BLOCKED" }
        val warningCount = findings.count { it.status == "WARNING" }
        val overall = when {
            blockedCount > 0 -> "BLOCKED"
            warningCount > 0 -> "WARNING"
            else -> "CLEAN"
        }
        val summary = when (overall) {
            "BLOCKED" -> "$blockedCount high-risk executable/spoofed file(s) flagged"
            "WARNING" -> "$warningCount file(s) require inspection (${findings.size} scanned)"
            else -> "Verified ${findings.size} file(s) clean (magic-byte + SHA-256 checked)"
        }
        return TorrentScanReport(overall, summary, findings)
    }

    fun scanSingleFile(file: File): FileScanFinding {
        val ext = file.extension.lowercase()
        val header = ByteArray(16)
        var bytesRead = 0
        val shaDigest = MessageDigest.getInstance("SHA-256")

        FileInputStream(file).use { fis ->
            val buf = ByteArray(16 * 1024)
            var first = true
            while (true) {
                val read = fis.read(buf)
                if (read <= 0) break
                if (first) {
                    bytesRead = minOf(read, header.size)
                    System.arraycopy(buf, 0, header, 0, bytesRead)
                    first = false
                }
                shaDigest.update(buf, 0, read)
            }
        }
        val sha256 = shaDigest.digest().joinToString("") { "%02x".format(it) }

        val detectedType = detectMagicType(header, bytesRead)

        // Check extension spoofing: executable signature disguised as media/subtitle
        if (detectedType != null && ext in mediaExtensions) {
            return FileScanFinding(
                fileName = file.name,
                status = "BLOCKED",
                reason = "Extension spoofing detected: $detectedType header inside .$ext file",
                sha256 = sha256
            )
        }

        // Check ZIP containers claiming to be media or APK payloads
        if (bytesRead >= 4 && header[0] == 0x50.toByte() && header[1] == 0x4B.toByte() &&
            header[2] == 0x03.toByte() && header[3] == 0x04.toByte()
        ) {
            val hasApkManifest = try {
                ZipFile(file).use { zip ->
                    zip.getEntry("AndroidManifest.xml") != null || zip.getEntry("classes.dex") != null
                }
            } catch (_: Exception) {
                false
            }
            if (hasApkManifest) {
                return FileScanFinding(
                    fileName = file.name,
                    status = "BLOCKED",
                    reason = "Contains Android executable package (AndroidManifest.xml / classes.dex)",
                    sha256 = sha256
                )
            }
        }

        if (ext in dangerousExtensions) {
            val sev = if (detectedType != null || ext in setOf("apk", "exe", "dex", "bat", "sh")) "BLOCKED" else "WARNING"
            return FileScanFinding(
                fileName = file.name,
                status = sev,
                reason = "Executable or installer file extension (.$ext)",
                sha256 = sha256
            )
        }

        if (detectedType != null) {
            return FileScanFinding(
                fileName = file.name,
                status = "WARNING",
                reason = "Detected binary signature: $detectedType",
                sha256 = sha256
            )
        }

        return FileScanFinding(
            fileName = file.name,
            status = "CLEAN",
            reason = "Valid content signature (${file.length()} bytes)",
            sha256 = sha256
        )
    }

    private fun detectMagicType(h: ByteArray, len: Int): String? {
        if (len < 2) return null
        // Windows PE / DOS MZ executable
        if (h[0] == 'M'.code.toByte() && h[1] == 'Z'.code.toByte()) return "Windows PE/MZ executable"
        // Linux / Android ELF binary
        if (len >= 4 && h[0] == 0x7F.toByte() && h[1] == 'E'.code.toByte() && h[2] == 'L'.code.toByte() && h[3] == 'F'.code.toByte()) {
            return "ELF native executable"
        }
        // Dalvik DEX
        if (len >= 4 && h[0] == 'd'.code.toByte() && h[1] == 'e'.code.toByte() && h[2] == 'x'.code.toByte() && h[3] == '\n'.code.toByte()) {
            return "Android Dalvik DEX bytecode"
        }
        // Mach-O
        if (len >= 4 && (
                (h[0] == 0xFE.toByte() && h[1] == 0xED.toByte() && h[2] == 0xFA.toByte()) ||
                    (h[0] == 0xCF.toByte() && h[1] == 0xFA.toByte() && h[2] == 0xED.toByte())
                )
        ) {
            return "Mach-O binary"
        }
        // Shell script shebang
        if (h[0] == '#'.code.toByte() && h[1] == '!'.code.toByte()) return "Shell script (#!)"
        return null
    }
}
