package com.aiocw.openmusic.music

/** 简谱序列解析（跟唱模板用）：纯 Kotlin，可 JVM 单测 */
object MelodyParser {

    /**
     * 解析简谱串，返回音级序列（0..6 = do..si）。
     * 支持数字 1–7、0（休止符）；空格/逗号/连字符等其余字符忽略。
     * 例："1 2 3 4 5 6 7 6 5 4 3 2 1" → [0,1,2,3,4,5,6,5,4,3,2,1,0]
     */
    fun parse(text: String): List<Int?> {
        val out = mutableListOf<Int?>()
        for (ch in text) {
            when {
                ch in '1'..'7' -> out.add(ch - '1')
                ch == '0' -> out.add(null) // 休止符
                // 其余字符忽略
            }
        }
        return out
    }

    /** 音级 → MIDI（可动 do：起音为 1，按大调音阶排列） */
    fun toMidi(degree: Int, startMidi: Int): Int = startMidi + Note.MAJOR_SCALE_OFFSETS[degree]

    /** 解析并换算为 MIDI 序列（0 = 休止符 → null） */
    fun parseToMidi(text: String, startMidi: Int): List<Int?> =
        parse(text).map { d -> d?.let { toMidi(it, startMidi) } }
}
