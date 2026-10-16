package com.aiocw.openmusic

import com.aiocw.openmusic.music.Note
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class NoteTest {

    @Test
    fun freqMidiRoundTrip() {
        assertEquals(440.0, Note.midiToFreq(69), 0.01)
        assertEquals(261.63, Note.midiToFreq(60), 0.01)
        assertEquals(69, Note.nearestMidi(440.0))
        assertEquals(60, Note.nearestMidi(261.63))
    }

    @Test
    fun namesAndSolfege() {
        assertEquals("C4", Note.nameOf(60))
        assertEquals("A4", Note.nameOf(69))
        assertEquals("C5", Note.nameOf(72))
        assertEquals("A#4", Note.nameOf(70))
        assertEquals("do", Note.solfegeOf(60))
        assertEquals("mi", Note.solfegeOf(64))
        assertEquals("si", Note.solfegeOf(71))
        assertEquals("1", Note.jianpuOf(60))
        assertEquals("6", Note.jianpuOf(69))
    }

    @Test
    fun centsMath() {
        assertEquals(0.0, Note.cents(440.0, 440.0), 0.001)
        assertEquals(100.0, Note.cents(466.16, 440.0), 0.5)
        assertEquals(-100.0, Note.cents(415.3, 440.0), 0.5)
        assertEquals(0.0, Note.centsToTarget(261.63, 60), 0.1)
    }

    @Test
    fun degrees() {
        assertEquals(0, Note.degreeOf(60))
        assertEquals(2, Note.degreeOf(64))
        assertEquals(6, Note.degreeOf(71))
        assertEquals(-1, Note.degreeOf(61))
        assertEquals(67, Note.midiOfDegree(4))
        assertTrue(Note.isNatural(65))
    }

    @Test
    fun octaveMarks() {
        // 八度数与简谱加点：C4 中音不加点，低音下加点，高音上加点
        assertEquals(4, Note.octaveOf(60))
        assertEquals(0, Note.jianpuDots(60))
        assertEquals(-1, Note.jianpuDots(48))   // C3 下加一点
        assertEquals(-2, Note.jianpuDots(36))   // C2 下加两点
        assertEquals(1, Note.jianpuDots(72))    // C5 上加一点
        assertEquals(2, Note.jianpuDots(84))    // C6 上加两点
        // 唱名/音名带八度
        assertEquals("do4", Note.solfegeWithOctave(60))
        assertEquals("re4", Note.solfegeWithOctave(62))
        assertEquals("C4", Note.nameOf(60))
        assertEquals("D4", Note.nameOf(62))
    }

    @Test
    fun degreeLabelsMatchSolfege() {
        // 1=do 2=re 3=mi 4=fa 5=sol 6=la 7=si（曾出现 2=do# 的索引 bug，此测试锁定）
        val solfege = listOf("do", "re", "mi", "fa", "sol", "la", "si")
        for (i in 0..6) {
            val pc = Note.MAJOR_SCALE_OFFSETS[i]
            assertEquals(solfege[i], Note.SOLFEGE[pc])
            assertEquals((i + 1).toString(), Note.JIANPU[pc])
        }
    }

    @Test
    fun octaveIndependentMatching() {
        // 男声低八度：目标 C4，唱 C3 —— 同音名，八度差 -1
        assertEquals(0, Note.pitchClassOf(48))
        assertTrue(Note.samePitchClass(48, 60))
        assertTrue(Note.samePitchClass(72, 60))
        assertFalse(Note.samePitchClass(62, 60))
        assertEquals(-1, Note.octaveShiftOf(48, 60))
        assertEquals(1, Note.octaveShiftOf(72, 60))
        assertEquals(0, Note.octaveShiftOf(60, 60))

        // 到最近八度的音分：261.63 唱低八度 130.81 应判 0 音分
        assertEquals(0.0, Note.centsToNearestOctave(130.81, 60), 0.5)
        assertEquals(0.0, Note.centsToNearestOctave(523.25, 60), 0.5)
        assertTrue(abs(Note.centsToNearestOctave(132.0, 60)) < 16.0)
    }

    @Test
    fun verdictAndScore() {
        assertEquals(Note.Verdict.ON_PITCH, Note.verdict(10.0))
        assertEquals(Note.Verdict.CLOSE, Note.verdict(-40.0))
        assertEquals(Note.Verdict.SHARP, Note.verdict(80.0))
        assertEquals(Note.Verdict.FLAT, Note.verdict(-80.0))
        assertTrue(Note.scoreOf(5.0) >= 90)
        assertTrue(Note.scoreOf(100.0) <= 10)
        assertTrue(abs(Note.scoreOf(25.0) - 75) <= 1)
    }
}
