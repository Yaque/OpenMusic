package com.aiocw.openmusic.music

import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.roundToInt

/**
 * 音乐基础换算：频率 ↔ MIDI ↔ 音名/唱名/简谱。
 *
 * 约定：科学音高记法，中央 C = C4 = MIDI 60 = 261.63Hz。
 * 纯 Kotlin 实现，无 Android 依赖，可直接 JVM 单测。
 */
object Note {

    /** 半音音名（含升号） */
    val NOTE_NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

    /** 唱名（固定调，C 大调） */
    val SOLFEGE = arrayOf("do", "do#", "re", "re#", "mi", "fa", "fa#", "sol", "sol#", "la", "la#", "si")

    /** 简谱数字（含升号） */
    val JIANPU = arrayOf("1", "1#", "2", "2#", "3", "4", "4#", "5", "5#", "6", "6#", "7")

    /** C 大调自然音级相对 C 的半音数：do re mi fa sol la si */
    val MAJOR_SCALE_OFFSETS = intArrayOf(0, 2, 4, 5, 7, 9, 11)

    fun freqToMidi(freq: Double): Double = 69.0 + 12.0 * log2(freq / 440.0)

    fun midiToFreq(midi: Int): Double = 440.0 * 2.0.pow((midi - 69) / 12.0)

    fun midiToFreq(midi: Double): Double = 440.0 * 2.0.pow((midi - 69) / 12.0)

    /** 音名，如 C4 / A#4 */
    fun nameOf(midi: Int): String {
        val pc = ((midi % 12) + 12) % 12
        val octave = midi / 12 - 1
        return NOTE_NAMES[pc] + octave
    }

    /** 唱名，如 do / mi */
    fun solfegeOf(midi: Int): String = SOLFEGE[((midi % 12) + 12) % 12]

    /** 简谱，如 1 / 3 */
    fun jianpuOf(midi: Int): String = JIANPU[((midi % 12) + 12) % 12]

    /** 距离最近的音级（四舍五入） */
    fun nearestMidi(freq: Double): Int = freqToMidi(freq).roundToInt()

    /** 音名序号（不含八度），0=C … 11=B */
    fun pitchClassOf(midi: Int): Int = ((midi % 12) + 12) % 12

    /** 同音名（忽略八度）：男声低八度唱 C4 → C3 也算唱对了音 */
    fun samePitchClass(a: Int, b: Int): Boolean = pitchClassOf(a) == pitchClassOf(b)

    /** 演唱相对目标音的八度差：0 = 同八度，-1 = 低八度，+1 = 高八度 */
    fun octaveShiftOf(sungMidi: Int, targetMidi: Int): Int =
        round((sungMidi - targetMidi) / 12.0).toInt()

    /** 八度无关的音准：到「目标音的最近八度」的音分偏差（男声低八度唱也判准） */
    fun centsToNearestOctave(freq: Double, targetMidi: Int): Double {
        val fTarget = midiToFreq(targetMidi)
        val octaves = round(log2(freq / fTarget)).toInt()
        return 1200.0 * log2(freq / (fTarget * 2.0.pow(octaves)))
    }

    /** 两个频率之间的音分差：正数 = fSung 偏高 */
    fun cents(fSung: Double, fTarget: Double): Double = 1200.0 * log2(fSung / fTarget)

    /** 某频率相对目标音级的音分偏差 */
    fun centsToTarget(freq: Double, targetMidi: Int): Double = cents(freq, midiToFreq(targetMidi))

    /** 是否为 C 大调自然音 */
    fun isNatural(midi: Int): Boolean {
        val pc = ((midi % 12) + 12) % 12
        return pc in MAJOR_SCALE_OFFSETS
    }

    /** C 大调音级序号 0..6（do..si），非自然音返回 -1 */
    fun degreeOf(midi: Int): Int {
        val pc = ((midi % 12) + 12) % 12
        return MAJOR_SCALE_OFFSETS.indexOf(pc)
    }

    /** 音级序号 0..6 对应的 C4 音级 MIDI 值 */
    fun midiOfDegree(degree: Int, octaveShift: Int = 0): Int =
        60 + MAJOR_SCALE_OFFSETS[degree.coerceIn(0, 6)] + 12 * octaveShift

    /** 标准音高串，如 "A4 · la · 6" */
    fun label(midi: Int): String = "${nameOf(midi)} · ${solfegeOf(midi)} · ${jianpuOf(midi)}"

    /** 八度数（科学音高：C4 = 4，A0 = 0） */
    fun octaveOf(midi: Int): Int = midi / 12 - 1

    /** 简谱八度记号：正数 = 数字上方加点，负数 = 下方加点，0 = 中音区不加 */
    fun jianpuDots(midi: Int): Int = octaveOf(midi) - 4

    /** 唱名带八度，如 do4 / re5 / la0 */
    fun solfegeWithOctave(midi: Int): String = solfegeOf(midi) + octaveOf(midi)

    /** 音准判定：|cents| <= 25 很准，<= 50 基本准，其余偏高/偏低 */
    fun verdict(centsValue: Double): Verdict = when {
        abs(centsValue) <= 25.0 -> Verdict.ON_PITCH
        abs(centsValue) <= 50.0 -> Verdict.CLOSE
        centsValue > 0 -> Verdict.SHARP
        else -> Verdict.FLAT
    }

    enum class Verdict(val text: String) {
        ON_PITCH("很准 ✓"),
        CLOSE("基本准"),
        SHARP("偏高 ↑"),
        FLAT("偏低 ↓"),
    }

    /** 由平均 |cents| 得到 0–100 分：25 cents 内接近满分，100 cents 为 0 分 */
    fun scoreOf(avgAbsCents: Double): Int =
        (100.0 - avgAbsCents * 1.0).coerceIn(0.0, 100.0).roundToInt()
}
