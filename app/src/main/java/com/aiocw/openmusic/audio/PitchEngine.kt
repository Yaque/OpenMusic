package com.aiocw.openmusic.audio

import kotlin.math.abs

/**
 * YIN 基频检测算法（de Cheveigné & Kawahara, 2002）。
 *
 * 纯 Kotlin 实现，无 Android 依赖，可在 JVM 上单测。
 * 典型用法：以 44.1kHz 采集 4096 个样本调用 [detect]。
 */
class YinPitchDetector(
    val sampleRate: Int = 44100,
    private val threshold: Float = 0.12f,
    val minFreq: Double = 70.0,
    val maxFreq: Double = 1000.0,
) {
    /** freq <= 0 表示未检测到稳定基频（无声/噪声） */
    data class Result(val freq: Double, val confidence: Float) {
        val isVoiced: Boolean get() = freq > 0 && confidence >= VOICED_CONFIDENCE
    }

    private var diff = FloatArray(0)
    private var cmnd = FloatArray(0)

    fun detect(buffer: FloatArray, length: Int = buffer.size): Result {
        val half = length / 2
        val tauMin = maxOf(2, (sampleRate / maxFreq).toInt())
        val tauMax = minOf(half - 1, (sampleRate / minFreq).toInt())
        if (tauMax <= tauMin + 2) return Result(-1.0, 0f)
        if (diff.size <= tauMax) {
            diff = FloatArray(tauMax + 1)
            cmnd = FloatArray(tauMax + 1)
        }

        // 1) 差分函数 d(tau) = Σ (x[j] - x[j+tau])²
        for (tau in 1..tauMax) {
            var sum = 0f
            for (j in 0 until half) {
                val d = buffer[j] - buffer[j + tau]
                sum += d * d
            }
            diff[tau] = sum
        }

        // 2) 累积均值归一化差分 d'(tau)
        cmnd[0] = 1f
        var running = 0f
        for (tau in 1..tauMax) {
            running += diff[tau]
            cmnd[tau] = if (running > 1e-12f) diff[tau] * tau / running else 1f
        }

        // 3) 绝对阈值：找第一个低于阈值的谷底
        var tauEstimate = -1
        var tau = tauMin
        while (tau <= tauMax) {
            if (cmnd[tau] < threshold) {
                while (tau + 1 <= tauMax && cmnd[tau + 1] < cmnd[tau]) tau++
                tauEstimate = tau
                break
            }
            tau++
        }

        if (tauEstimate < 0) {
            // 未达阈值：退回全局最小值，仅当周期性尚可时接受（弱人声）
            var best = tauMin
            for (t in tauMin + 1..tauMax) if (cmnd[t] < cmnd[best]) best = t
            val conf = 1f - cmnd[best]
            return if (conf >= VOICED_CONFIDENCE) {
                Result(sampleRate / parabolic(best), conf)
            } else {
                Result(-1.0, conf)
            }
        }

        val refinedTau = parabolic(tauEstimate)
        val freq = sampleRate / refinedTau
        if (freq < minFreq * 0.9 || freq > maxFreq * 1.1) return Result(-1.0, 0f)
        return Result(freq, 1f - cmnd[tauEstimate])
    }

    /** 抛物线插值细化 tau */
    private fun parabolic(tau: Int): Double {
        if (tau <= 0 || tau >= cmnd.size - 1) return tau.toDouble()
        val s0 = cmnd[tau - 1]
        val s1 = cmnd[tau]
        val s2 = cmnd[tau + 1]
        val denom = 2f * (2f * s1 - s2 - s0)
        if (abs(denom) < 1e-12f) return tau.toDouble()
        return tau.toDouble() + ((s2 - s0) / denom).toDouble()
    }

    companion object {
        /** 置信度门限：1 - d'(tau)，人声通常 > 0.7，噪声 < 0.5 */
        const val VOICED_CONFIDENCE = 0.65f
    }
}

/** 简单中值平滑器：抑制检测抖动 */
class MedianSmoother(private val size: Int = 5) {
    private val buf = ArrayDeque<Double>(size)

    @Synchronized
    fun push(value: Double): Double {
        if (value <= 0) {
            buf.clear()
            return -1.0
        }
        buf.addLast(value)
        while (buf.size > size) buf.removeFirst()
        return buf.sorted()[buf.size / 2]
    }

    @Synchronized
    fun reset() = buf.clear()
}
