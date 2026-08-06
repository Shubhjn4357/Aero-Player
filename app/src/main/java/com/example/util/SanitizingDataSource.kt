package com.example.util

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.IOException

/**
 * A wrapping DataSource that intercepts and sanitizes malformed or unsupported EBML/Matroska headers
 * (such as ContentCompAlgo 7 or other non-standard compression algorithm tags)
 * so ExoPlayer's MatroskaExtractor can parse MKV/WebM files without throwing ParserException.
 */
class SanitizingDataSource(private val upstream: DataSource) : DataSource {

    private val tailBuffer = ByteArray(16)
    private var tailSize = 0

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    @Throws(IOException::class)
    override fun open(dataSpec: DataSpec): Long {
        tailSize = 0
        return upstream.open(dataSpec)
    }

    override fun getUri(): Uri? {
        return upstream.getUri()
    }

    override fun getResponseHeaders(): Map<String, List<String>> {
        return upstream.getResponseHeaders()
    }

    @Throws(IOException::class)
    override fun close() {
        tailSize = 0
        upstream.close()
    }

    @Throws(IOException::class)
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val bytesRead = upstream.read(buffer, offset, length)
        if (bytesRead > 0) {
            sanitizeBuffer(buffer, offset, bytesRead)
        }
        return bytesRead
    }

    private fun sanitizeBuffer(buffer: ByteArray, offset: Int, length: Int) {
        val totalLen = tailSize + length
        val temp = ByteArray(totalLen)
        if (tailSize > 0) {
            System.arraycopy(tailBuffer, 0, temp, 0, tailSize)
        }
        System.arraycopy(buffer, offset, temp, tailSize, length)

        var i = 0
        while (i <= totalLen - 4) {
            // Check for Matroska EBML ID_CONTENT_COMP_ALGO (0x50, 0x35)
            if (temp[i] == 0x50.toByte() && temp[i + 1] == 0x35.toByte()) {
                val sizeByte = temp[i + 2].toInt() and 0xFF
                if (sizeByte == 0x81) { // 1-byte length integer
                    val algoVal = temp[i + 3].toInt() and 0xFF
                    if (algoVal != 0 && algoVal != 3) {
                        // Unsupported algorithm (e.g., ContentCompAlgo 7). Sanitize to 0 (uncompressed / zlib fallback)
                        temp[i + 3] = 0x00.toByte()
                        val bufIdx = (i + 3) - tailSize
                        if (bufIdx in 0 until length) {
                            buffer[offset + bufIdx] = 0x00.toByte()
                        }
                    }
                    i += 4
                    continue
                } else if (sizeByte == 0x82 && i <= totalLen - 5) { // 2-byte length integer
                    val val1 = temp[i + 3].toInt() and 0xFF
                    val val2 = temp[i + 4].toInt() and 0xFF
                    val combinedVal = (val1 shl 8) or val2
                    if (combinedVal != 0 && combinedVal != 3) {
                        temp[i + 3] = 0x00.toByte()
                        temp[i + 4] = 0x00.toByte()
                        val bufIdx3 = (i + 3) - tailSize
                        if (bufIdx3 in 0 until length) buffer[offset + bufIdx3] = 0x00.toByte()
                        val bufIdx4 = (i + 4) - tailSize
                        if (bufIdx4 in 0 until length) buffer[offset + bufIdx4] = 0x00.toByte()
                    }
                    i += 5
                    continue
                }
            }
            i++
        }

        // Maintain tail buffer for scanning across read boundaries
        val saveLen = minOf(8, totalLen)
        tailSize = saveLen
        System.arraycopy(temp, totalLen - saveLen, tailBuffer, 0, saveLen)
    }
}

class SanitizingDataSourceFactory(
    private val upstreamFactory: DataSource.Factory
) : DataSource.Factory {
    override fun createDataSource(): DataSource {
        return SanitizingDataSource(upstreamFactory.createDataSource())
    }
}
