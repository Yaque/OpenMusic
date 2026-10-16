package com.aiocw.openmusic

import com.aiocw.openmusic.audio.MedianSmoother
import com.aiocw.openmusic.audio.YinPitchDetector
import com.aiocw.openmusic.music.MelodyParser
import com.aiocw.openmusic.music.Note
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

class PitchEngineTest {

    private val sr = 44100
    private val detector = YinPitchDetector(sr)

    @Test
    fun melodyParserBasics() {
        // 默认模板：1234567654321
        val notes = MelodyParser.parse("1 2 3 4 5 6 7 6 5 4 3 2 1")
        assertEquals(13, notes.size)
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6, 5, 4, 3, 2, 1, 0), notes)
        // 0 = 休止，其他字符忽略
        assertEquals(listOf<Int?>(0, null, 2), MelodyParser.parse("1 0 3x"))
        // 可动 do：起音 C4
        assertEquals(60, MelodyParser.toMidi(0, 60))
        assertEquals(67, MelodyParser.toMidi(4, 60))
        assertEquals(71, MelodyParser.toMidi(6, 60))
        assertEquals(
            listOf(60, 62, 64),
            MelodyParser.parseToMidi("1 2 3", 60)
        )
    }

    /** 合成带谐波的准钢琴波形 */
    private fun synth(freq: Double, amp: Double = 0.5, noise: Double = 0.0): FloatArray {
        val buf = FloatArray(4096)
        val rnd = Random(42)
        for (i in buf.indices) {
            val t = i.toDouble() / sr
            var s = sin(2 * PI * freq * t) +
                0.5 * sin(2 * PI * 2 * freq * t) +
                0.28 * sin(2 * PI * 3 * freq * t) +
                0.15 * sin(2 * PI * 4 * freq * t)
            s = s / 1.9 * amp
            if (noise > 0) s += (rnd.nextDouble() * 2 - 1) * noise
            buf[i] = s.toFloat()
        }
        return buf
    }

    @Test
    fun detectsCleanPianoTones() {
        for (midi in intArrayOf(48, 55, 60, 64, 69, 72, 76, 81)) {
            val f = Note.midiToFreq(midi)
            val r = detector.detect(synth(f))
            assertTrue("midi $midi should be voiced", r.isVoiced)
            val cents = Note.cents(r.freq, f)
            assertTrue("midi $midi cents=$cents", abs(cents) < 10.0)
        }
    }

    @Test
    fun detectsLowAndHighEnds() {
        for (f in doubleArrayOf(80.0, 110.0, 220.0, 523.25, 987.77)) {
            val r = detector.detect(synth(f))
            assertTrue("freq $f voiced", r.isVoiced)
            assertTrue("freq $f err=${Note.cents(r.freq, f)}", abs(Note.cents(r.freq, f)) < 12.0)
        }
    }

    @Test
    fun silentBufferIsUnvoiced() {
        val r = detector.detect(FloatArray(4096))
        assertFalse(r.isVoiced)
    }

    @Test
    fun noiseIsUnvoiced() {
        val rnd = Random(7)
        val buf = FloatArray(4096) { (rnd.nextFloat() * 2 - 1) * 0.4f }
        val r = detector.detect(buf)
        assertFalse("noise should not be voiced, got ${r.freq} / ${r.confidence}", r.isVoiced)
    }

    @Test
    fun quietNoiseOnToneStillDetected() {
        val f = 329.63
        val r = detector.detect(synth(f, amp = 0.5, noise = 0.02))
        assertTrue(r.isVoiced)
        assertTrue(abs(Note.cents(r.freq, f)) < 15.0)
    }

    @Test
    fun medianSmootherFiltersJitter() {
        val s = MedianSmoother(5)
        assertEquals(-1.0, s.push(-1.0), 0.001)
        s.push(440.0)
        s.push(442.0)
        s.push(441.0)
        val m = s.push(439.0)
        assertTrue(abs(m - 440.0) < 2.0)
    }
}
