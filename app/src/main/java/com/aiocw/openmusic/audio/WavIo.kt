package com.aiocw.openmusic.audio

import java.io.File
import java.io.FileOutputStream

/** 极简 WAV（PCM16 单声道）写出工具，用于练习录音回听 */
object WavIo {

    fun writePcm16(file: File, samples: ShortArray, sampleRate: Int) {
        val dataSize = samples.size * 2
        FileOutputStream(file).use { out ->
            val header = ByteArray(44)
            writeStr(header, 0, "RIFF")
            writeInt(header, 4, 36 + dataSize)
            writeStr(header, 8, "WAVE")
            writeStr(header, 12, "fmt ")
            writeInt(header, 16, 16)           // fmt 块大小
            writeShort(header, 20, 1)          // PCM
            writeShort(header, 22, 1)          // 单声道
            writeInt(header, 24, sampleRate)
            writeInt(header, 28, sampleRate * 2) // 字节率
            writeShort(header, 32, 2)          // 块对齐
            writeShort(header, 34, 16)         // 位深
            writeStr(header, 36, "data")
            writeInt(header, 40, dataSize)
            out.write(header)
            val data = ByteArray(dataSize)
            for (i in samples.indices) {
                val v = samples[i].toInt()
                data[i * 2] = (v and 0xFF).toByte()
                data[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
            }
            out.write(data)
        }
    }

    /** 混音两路 PCM16（同采样率），音量归一并限幅 */
    fun mix(a: ShortArray, b: ShortArray, gainA: Float = 1f, gainB: Float = 0.9f): ShortArray {
        val n = maxOf(a.size, b.size)
        val out = ShortArray(n)
        for (i in 0 until n) {
            val va = if (i < a.size) a[i] * gainA else 0f
            val vb = if (i < b.size) b[i] * gainB else 0f
            var v = va + vb
            if (v > 32767f) v = 32767f
            if (v < -32768f) v = -32768f
            out[i] = v.toInt().toShort()
        }
        return out
    }

    private fun writeStr(b: ByteArray, off: Int, s: String) {
        for (i in s.indices) b[off + i] = s[i].code.toByte()
    }

    private fun writeInt(b: ByteArray, off: Int, v: Int) {
        b[off] = (v and 0xFF).toByte()
        b[off + 1] = ((v shr 8) and 0xFF).toByte()
        b[off + 2] = ((v shr 16) and 0xFF).toByte()
        b[off + 3] = ((v shr 24) and 0xFF).toByte()
    }

    private fun writeShort(b: ByteArray, off: Int, v: Int) {
        b[off] = (v and 0xFF).toByte()
        b[off + 1] = ((v shr 8) and 0xFF).toByte()
    }
}
