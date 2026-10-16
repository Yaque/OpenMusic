package com.aiocw.openmusic.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.util.Arrays
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * 运行时加法合成「钢琴」音色。
 *
 * 8 次谐波叠加 + 指数衰减包络（高次谐波衰减更快），
 * 多声部在专用渲染线程内混音，单 AudioTrack 流式输出。
 */
class PianoSynth {

    private data class Voice(
        val midi: Int,
        val freq: Double,
        val velocity: Float,
        val startSample: Long,
    ) {
        var releaseSample: Long = -1L
    }

    private val voices = ArrayList<Voice>()
    private var track: AudioTrack
    private var thread: Thread? = null

    @Volatile
    private var running = false

    @Volatile
    private var totalSamples = 0L

    /** 录音捕获（回听用）：开启后每块 PCM 进缓冲 */
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

    init {
        val minBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuf, BLOCK * 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    fun start() {
        if (running) return
        running = true
        track.play()
        thread = Thread({ renderLoop() }, "PianoSynth").also {
            it.priority = Thread.MAX_PRIORITY - 1
            it.start()
        }
    }

    fun stop() {
        running = false
        thread?.join(500)
        thread = null
        releaseAll()
        try {
            track.stop()
        } catch (_: IllegalStateException) {
        }
    }

    /** 按下琴键：发声并自动释放（钢琴点按式） */
    fun noteOn(midi: Int, velocity: Float = 0.9f) {
        synchronized(voices) {
            voices.removeAll { it.midi == midi && it.releaseSample >= 0 }
            voices.add(Voice(midi, com.aiocw.openmusic.music.Note.midiToFreq(midi), velocity, totalSamples))
        }
    }

    /** 松开琴键 */
    fun release(midi: Int) {
        synchronized(voices) {
            voices.forEach {
                if (it.midi == midi && it.releaseSample < 0) it.releaseSample = totalSamples
            }
        }
    }

    fun releaseAll() {
        synchronized(voices) {
            voices.forEach { if (it.releaseSample < 0) it.releaseSample = totalSamples }
        }
    }

    private fun renderLoop() {
        val mix = FloatArray(BLOCK)
        val pcm = ShortArray(BLOCK)
        while (running) {
            Arrays.fill(mix, 0f)
            val now = totalSamples
            synchronized(voices) {
                var i = 0
                while (i < voices.size) {
                    if (!renderVoice(voices[i], mix, now)) voices.removeAt(i) else i++
                }
                totalSamples += BLOCK
            }
        // 软限幅
            for (n in 0 until BLOCK) {
                var v = mix[n] * MASTER_GAIN
                if (v > 1f) v = 1f
                if (v < -1f) v = -1f
                pcm[n] = (v * 32767f).toInt().toShort()
            }
            if (captureEnabled) {
                synchronized(captureLock) {
                    for (s in pcm) {
                        val v = s.toInt()
                        captureBuf.write(v and 0xFF)
                        captureBuf.write((v shr 8) and 0xFF)
                    }
                }
            }
            track.write(pcm, 0, BLOCK)
        }
    }

    /** 渲染一个声部到混音缓冲；返回 false 表示声音已结束可移除 */
    private fun renderVoice(v: Voice, buf: FloatArray, now: Long): Boolean {
        val age0 = now - v.startSample
        if (age0 < 0) return true
        // 点按式自动释放：发声超过 AUTO_RELEASE 秒后淡出
        if (v.releaseSample < 0 && age0 > AUTO_RELEASE_SAMPLES) v.releaseSample = v.startSample + AUTO_RELEASE_SAMPLES
        var audible = false
        val relSample = v.releaseSample
        for (n in buf.indices) {
            val age = (age0 + n).toDouble()
            val t = age / SAMPLE_RATE
            // 释放段快速淡出
            var gain = 1.0
            if (relSample >= 0 && relSample <= age) gain = exp(-(age - relSample) / (SAMPLE_RATE * RELEASE_SEC))
            val attack = min(1.0, t / ATTACK_SEC)
            var s = 0.0
            for (h in HARMONIC_AMPS.indices) {
                val order = h + 1
                val amp = HARMONIC_AMPS[h]
                if (amp < 0.004f) continue
                val tau = TAU_BASE_SEC / (1.0 + 0.45 * h) / (1.0 + (v.midi - 60) / 48.0)
                val env = exp(-t / tau)
                if (env < 0.0008) continue
                s += amp * env * sin(2.0 * PI * order * v.freq * t)
            }
            s *= attack * gain * v.velocity
            buf[n] += s.toFloat()
            if (t < 12.0 && gain * exp(-t / TAU_BASE_SEC) * v.velocity > 0.0008) audible = true
        }
        return audible
    }

    companion object {
        const val SAMPLE_RATE = 44100
        private const val BLOCK = 1024
        private const val ATTACK_SEC = 0.004
        private const val RELEASE_SEC = 0.12
        private const val TAU_BASE_SEC = 1.9
        private const val MASTER_GAIN = 0.85f
        private val AUTO_RELEASE_SAMPLES = (SAMPLE_RATE * 1.8).toLong()

        /** 各次谐波幅度（钢琴式亮起暗落） */
        private val HARMONIC_AMPS = floatArrayOf(1f, 0.55f, 0.30f, 0.18f, 0.12f, 0.08f, 0.05f, 0.03f)
    }
}
