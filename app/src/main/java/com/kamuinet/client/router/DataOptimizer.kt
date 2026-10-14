package com.kamuinet.client.router

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

object DataOptimizer {

    /**
     * Compresses payload if it's large enough to benefit from it.
     * Returns a Pair(compressedData, wasCompressed)
     */
    fun compress(data: ByteArray): Pair<ByteArray, Boolean> {
        // Joker Optimization: Increased threshold for 720p throughput
        if (data.size < 1024) return Pair(data, false) 

        val deflater = java.util.zip.Deflater(java.util.zip.Deflater.BEST_SPEED, true)
        return try {
            val bos = ByteArrayOutputStream()
            val gzos = object : GZIPOutputStream(bos) {
                init {
                    this.def = deflater
                }
            }
            
            gzos.use { it.write(data) }
            val compressed = bos.toByteArray()

            if (compressed.size < data.size * 0.85) {
                Pair(compressed, true)
            } else {
                Pair(data, false)
            }
        } catch (e: Exception) {
            Pair(data, false)
        } finally {
            deflater.end() // CRITICAL: Stop the native memory leak
        }
    }

    /**
     * Decompresses payload if it was marked as compressed
     */
    fun decompress(data: ByteArray): ByteArray {
        return try {
            val bis = ByteArrayInputStream(data)
            val gis = GZIPInputStream(bis)
            gis.readBytes()
        } catch (e: Exception) {
            data // Fallback to original if decompression fails
        }
    }
}
