package com.aiocw.openmusic.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 麦克风实时音高监听：AudioRecord 采集 → YIN 检测 → 中值平滑 → 主线程友好回调。
 *
 * 注意：需调用方持有 RECORD_AUDIO 运行时权限。
 */
class MicPitchMonitor(private val listener: Listener) {

    interface Listener {
        /** freq <= 0 表示当前未检测到人声；rms 为帧内均方根音量 */
        fun onPitch(freq: Double, confidence: Float, rms: Float)
    }

    private var record: AudioRecord? = null
    private var thread: Thread? = null

    @Volatile
    private var running = false

    private val detector = YinPitchDetector(SAMPLE_RATE)
    private val smoother = MedianSmoother(5)
    private val window = FloatArray(WINDOW)
    private val hopBuf = FloatArray(HOP)

    /** 录音捕获（回听用） */
    @Volatile
    var captureEnabled = false
    private val captureLock = Any()
    private val captureBuf = java.io.ByteArrayOutputStream()

    fun clearCapture() = synchronized(captureLock) { captureBuf.reset() }

    fun drainCapture(): ShortArray = synchronized(captureLock) {
        val bytes = captureBuf.toByteArray()
        captureBuf.reset()
        ShortArray(bytes.size / 2) { i ->
            ((bytes[i * 2].toInt() and 0xFF) or (bytes[i * 2 + 1].toInt() shl 8)).toShort()
        }
    }

    val isRunning: Boolean get() = running

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (running) return true
        val source = try {
            val r = buildRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            if (r.state == AudioRecord.STATE_INITIALIZED) r
            else {
                r.release()
                buildRecord(MediaRecorder.AudioSource.MIC)
            }
        } catch (_: Exception) {
            return false
        }
        if (source.state != AudioRecord.STATE_INITIALIZED) {
            source.release()
            return false
        }
        record = source
        running = true
        smoother.reset()
        window.fill(0f)
        thread = Thread({ captureLoop(source) }, "MicPitchMonitor").also { it.start() }
        return true
    }

    fun stop() {
        running = false
        thread?.join(800)
        thread = null
        record?.release()
        record = null
    }

    private fun buildRecord(source: Int): AudioRecord {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT
        )
        return AudioRecord(
            source, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_FLOAT, maxOf(minBuf, WINDOW * 4)
        )
    }

    private fun captureLoop(rec: AudioRecord) {
        rec.startRecording()
        while (running) {
            val read = rec.read(hopBuf, 0, HOP, AudioRecord.READ_BLOCKING)
            if (read <= 0) break
            if (captureEnabled) {
                synchronized(captureLock) {
                    for (i in 0 until read) {
                        var v = (hopBuf[i] * 32767f).toInt()
                        if (v > 32767) v = 32767
                        if (v < -32768) v = -32768
                        captureBuf.write(v and 0xFF)
                        captureBuf.write((v shr 8) and 0xFF)
                    }
                }
            }
            // 滑动窗口：丢弃最老的 HOP 个样本，追加新样本
            System.arraycopy(window, read, window, 0, WINDOW - read)
            System.arraycopy(hopBuf, 0, window, WINDOW - read, read)

            var sumSq = 0f
            for (s in window) sumSq += s * s
            val rms = sqrt(sumSq / WINDOW)

            val result = detector.detect(window)
            val freq = if (result.isVoiced) smoother.push(result.freq) else {
                smoother.reset()
                -1.0
            }
            listener.onPitch(if (result.isVoiced && freq > 0) freq else -1.0, result.confidence, rms)
        }
        try {
            rec.stop()
        } catch (_: IllegalStateException) {
        }
    }

    companion object {
        const val SAMPLE_RATE = 44100
        private const val WINDOW = 4096
        private const val HOP = 1024
    }
}
