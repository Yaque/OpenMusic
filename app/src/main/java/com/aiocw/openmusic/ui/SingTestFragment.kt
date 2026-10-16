package com.aiocw.openmusic.ui

import android.media.MediaPlayer
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.aiocw.openmusic.MainActivity
import com.aiocw.openmusic.R
import com.aiocw.openmusic.audio.MicPitchMonitor
import com.aiocw.openmusic.audio.WavIo
import com.aiocw.openmusic.music.AppSettings
import com.aiocw.openmusic.music.Note
import com.aiocw.openmusic.music.Stats
import com.google.android.material.chip.Chip
import java.io.File
import kotlin.math.abs
import kotlin.random.Random

/**
 * 模式三：模唱练习 —— 会话式（开始模唱/下一轮/结束），结束后停止计分；
 * 大字倒计时；录音可「回听」。
 */
class SingTestFragment : Fragment(R.layout.fragment_sing_test), MicPitchMonitor.Listener {

    private lateinit var synth: com.aiocw.openmusic.audio.PianoSynth
    private var monitor: MicPitchMonitor? = null
    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null

    private lateinit var tvStatus: TextView
    private lateinit var tvTarget: TextView
    private lateinit var tvDetected: TextView
    private lateinit var tvVerdict: TextView
    private lateinit var tvStats: TextView
    private lateinit var tvCountdown: TextView
    private lateinit var gauge: PitchGauge
    private lateinit var btnStart: Button
    private lateinit var btnEnd: Button
    private lateinit var btnPlay: Button

    private var blind = false
    private var targetMidi = 60
    private var recording = false

    /** 出题顺序：0=随机 1=正序 2=倒序 3=正序接倒序 4=跳进模进 */
    private var orderMode = 0
    private var seqIndex = 0
    private var currentSeq = listOf<Int>()

    /** 监听音调：准星升降的半音数（负=降 / 正=升）。发声不变，只移动判音准星 */
    private var transpose = 0
    private lateinit var spinTranspose: Spinner
    private var transposeShifts = listOf(0)
    private val sungFreqs = ArrayList<Double>()
    private var countdown: CountDownTimer? = null

    // 会话计分
    private var sessionActive = false
    private var sessionRounds = 0
    private var sessionSum = 0
    private var sessionBest = 0

    private var takeFile: File? = null

    // 自选音域：全音域自然音（A0–C8）任选起止，默认 C4–C5（持久保存）
    private val naturalMidis = (21..108).filter { Note.isNatural(it) }
    private var rangeStartMidi = 60
    private var rangeEndMidi = 72

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        synth = (requireActivity() as MainActivity).synth

        tvStatus = view.findViewById(R.id.tv_sing_status)
        tvTarget = view.findViewById(R.id.tv_sing_target)
        tvDetected = view.findViewById(R.id.tv_sing_detected)
        tvVerdict = view.findViewById(R.id.tv_sing_verdict)
        tvStats = view.findViewById(R.id.tv_sing_stats)
        tvCountdown = view.findViewById(R.id.tv_countdown)
        gauge = view.findViewById(R.id.sing_gauge)
        btnStart = view.findViewById(R.id.btn_sing_start)
        btnEnd = view.findViewById(R.id.btn_sing_end)
        btnPlay = view.findViewById(R.id.btn_sing_play)

        view.findViewById<Chip>(R.id.chip_blind).setOnCheckedChangeListener { _, checked ->
            blind = checked
            if (!recording) tvTarget.text = targetDisplay()
        }
        btnStart.setOnClickListener {
            if (!sessionActive) startSession() else startRound()
        }
        btnEnd.setOnClickListener { endSession() }
        btnPlay.setOnClickListener { playback() }

        bindTransposeSpinner(view)
        bindOrderSpinner(view)
        bindRangeSpinners(view)
        refreshStats()
        tvStatus.text = getString(R.string.sing_idle)
        btnStart.text = getString(R.string.sing_start)
    }

    /** 监听音调：升降幅度由用户决定，系统按当前音域自动给出可选范围 */
    private fun bindTransposeSpinner(view: View) {
        spinTranspose = view.findViewById(R.id.spin_transpose)
        spinTranspose.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                transpose = transposeShifts.getOrElse(pos) { 0 }
                AppSettings.setSingShift(requireContext(), transpose)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        rebuildTransposeOptions()
    }

    private fun rebuildTransposeOptions() {
        val start = rangeStartMidi
        val maxDown = (start - 36).coerceAtLeast(0)       // 准星不低于 C2（演唱实用下限）
        val maxUp = (84 - rangeEndMidi).coerceAtLeast(0)  // 准星不高于 C6
        transposeShifts = (-maxDown..maxUp).toList()
        val labels = transposeShifts.map { k ->
            when {
                k == 0 -> getString(R.string.transpose_none)
                k < 0 -> getString(R.string.transpose_n, -k, Note.nameOf(start), Note.nameOf(start + k))
                else -> getString(R.string.transpose_up, k, Note.nameOf(start), Note.nameOf(start + k))
            }
        }
        spinTranspose.adapter = ArrayAdapter(
            requireContext(), android.R.layout.simple_spinner_dropdown_item, labels
        )
        var sel = transposeShifts.indexOf(AppSettings.singShift(requireContext()))
        if (sel < 0) {
            sel = transposeShifts.indexOf(0)
            transpose = 0
            AppSettings.setSingShift(requireContext(), 0)
        }
        transpose = transposeShifts[sel]
        spinTranspose.setSelection(sel)
    }

    /** 目标显示：发声音 + 准星 */
    private fun targetDisplay(): String = when {
        blind -> "???"
        transpose == 0 -> Note.label(targetMidi)
        else -> getString(
            R.string.sing_target_t, Note.label(targetMidi), Note.nameOf(targetMidi + transpose)
        )
    }

    /** 模唱顺序：随机 / 正序 / 倒序 / 正序接倒序（持久保存） */
    private fun bindOrderSpinner(view: View) {
        val spinner = view.findViewById<Spinner>(R.id.spin_order)
        val labels = listOf(
            getString(R.string.order_random),
            getString(R.string.order_asc),
            getString(R.string.order_desc),
            getString(R.string.order_asc_desc),
            getString(R.string.order_leap)
        )
        spinner.adapter = ArrayAdapter(
            requireContext(), android.R.layout.simple_spinner_dropdown_item, labels
        )
        orderMode = AppSettings.singOrder(requireContext()).coerceIn(0, 4)
        spinner.setSelection(orderMode)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                orderMode = pos
                AppSettings.setSingOrder(requireContext(), pos)
                seqIndex = 0
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun startSession() {
        sessionActive = true
        sessionRounds = 0
        sessionSum = 0
        sessionBest = 0
        seqIndex = 0
        btnEnd.isEnabled = true
        btnStart.text = getString(R.string.sing_next)
        startRound()
    }

    /** 结束会话：停止计分 */
    private fun endSession() {
        sessionActive = false
        recording = false
        countdown?.cancel()
        handler.removeCallbacksAndMessages(null)
        monitor?.let { it.captureEnabled = false }
        monitor?.stop()
        monitor = null
        tvCountdown.visibility = View.GONE
        btnEnd.isEnabled = false
        btnStart.isEnabled = true
        btnStart.text = getString(R.string.sing_start)
        tvStatus.text = getString(
            R.string.sing_session_summary,
            sessionRounds,
            if (sessionRounds == 0) 0 else sessionSum / sessionRounds,
            sessionBest
        )
    }

    private fun startRound() {
        btnStart.isEnabled = false
        sungFreqs.clear()
        tvVerdict.text = ""
        gauge.cents = Float.NaN

        // 目标音：自选音域内的 C 大调自然音（按所选顺序）
        val pool = (rangeStartMidi..rangeEndMidi).filter { Note.isNatural(it) }
        currentSeq = when (orderMode) {
            1 -> pool                                // 正序：低→高
            2 -> pool.reversed()                     // 倒序：高→低
            3 -> pool + pool.reversed().drop(1)      // 正序接倒序：上行后下行
            4 -> buildLeapSequence(pool)             // 跳进模进：do-mi-sol-si / re-fa-la-do …
            else -> emptyList()
        }
        targetMidi = if (orderMode == 0 || currentSeq.isEmpty()) {
            pool.randomOrNull() ?: 60 // 随机
        } else {
            currentSeq[seqIndex % currentSeq.size].also { seqIndex++ }
        }
        tvTarget.text = targetDisplay()
        tvStatus.text = getString(R.string.sing_playing)
        synth.noteOn(targetMidi)

        handler.postDelayed({
            tvStatus.text = getString(R.string.sing_get_ready)
        }, 1200)

        // 大字倒计时：3 2 1 唱！
        countdown?.cancel()
        tvCountdown.visibility = View.VISIBLE
        countdown = object : CountDownTimer(3200, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val n = (millisUntilFinished / 1000 + 1).toInt().coerceIn(1, 3)
                tvCountdown.text = n.toString()
            }

            override fun onFinish() {
                tvCountdown.text = getString(R.string.sing_countdown_go)
                handler.postDelayed({
                    tvCountdown.visibility = View.GONE
                    beginRecording()
                }, 500)
            }
        }.also { it.start() }
    }

    private fun beginRecording() {
        (requireActivity() as MainActivity).requestMic { granted ->
            if (!granted) {
                tvStatus.text = getString(R.string.mic_denied)
                btnStart.isEnabled = true
                return@requestMic
            }
            val m = MicPitchMonitor(this)
            if (!m.start()) {
                tvStatus.text = getString(R.string.mic_denied)
                btnStart.isEnabled = true
                return@requestMic
            }
            m.captureEnabled = true // 录下歌声供回听
            m.clearCapture()
            monitor = m
            recording = true
            tvStatus.text = getString(R.string.sing_now)
            handler.postDelayed({ endRecording() }, 5000)
        }
    }

    private fun endRecording() {
        recording = false
        monitor?.let { it.captureEnabled = false }
        // 保存录音供回听
        val samples = monitor?.drainCapture() ?: ShortArray(0)
        if (samples.isNotEmpty()) {
            val file = File(requireContext().cacheDir, "sing_take.wav")
            WavIo.writePcm16(file, samples, MicPitchMonitor.SAMPLE_RATE)
            takeFile = file
            btnPlay.isEnabled = true
        }
        monitor?.stop()
        monitor = null

        if (sungFreqs.size < 6) {
            tvStatus.text = getString(R.string.sing_no_voice)
            tvVerdict.text = ""
            btnStart.isEnabled = true
            return
        }
        val sorted = sungFreqs.sorted()
        val median = sorted[sorted.size / 2]
        val sungMidi = Note.nearestMidi(median)
        val refMidi = targetMidi + transpose // 准星：监听音调移动后的位置

        tvTarget.text = targetDisplay()
        tvDetected.text = getString(
            R.string.sing_detected, Note.label(sungMidi), "%.1f".format(median)
        )

        if (transpose != 0) {
            // 降调开：严格以准星（如 C3）为准 —— 发声仍是 C4
            val centsVal = Note.cents(median, Note.midiToFreq(refMidi))
            val avgAbs = sungFreqs.map { abs(Note.cents(it, Note.midiToFreq(refMidi))) }.average()
            val score = Note.scoreOf(avgAbs.coerceAtMost(120.0))
            gauge.cents = centsVal.toFloat()
            val verdict = Note.verdict(centsVal)
            tvVerdict.text = if (verdict == Note.Verdict.ON_PITCH) {
                getString(R.string.sing_perfect, score)
            } else {
                getString(R.string.sing_result, score, verdict.text)
            }
            tvVerdict.setTextColor(
                ContextCompat.getColor(
                    requireContext(),
                    when (verdict) {
                        Note.Verdict.ON_PITCH -> R.color.ok_green
                        Note.Verdict.CLOSE -> R.color.warn_amber
                        else -> R.color.bad_red
                    }
                )
            )
            recordScore(score)
        } else if (Note.samePitchClass(sungMidi, refMidi)) {
            // 同音名即算唱对（男声低八度唱 C4 → C3 也对）
            val centsVal = Note.centsToNearestOctave(median, refMidi)
            val avgAbs = sungFreqs.map { abs(Note.centsToNearestOctave(it, refMidi)) }.average()
            val score = Note.scoreOf(avgAbs.coerceAtMost(120.0))
            gauge.cents = centsVal.toFloat()
            val verdict = Note.verdict(centsVal)
            val shift = Note.octaveShiftOf(sungMidi, refMidi)
            tvVerdict.text = when {
                shift != 0 -> getString(R.string.sing_octave_ok, octaveLabel(shift), score)
                verdict == Note.Verdict.ON_PITCH -> getString(R.string.sing_perfect, score)
                else -> getString(R.string.sing_result, score, verdict.text)
            }
            tvVerdict.setTextColor(
                ContextCompat.getColor(
                    requireContext(),
                    when (verdict) {
                        Note.Verdict.ON_PITCH -> R.color.ok_green
                        Note.Verdict.CLOSE -> R.color.warn_amber
                        else -> R.color.bad_red
                    }
                )
            )
            recordScore(score)
        } else {
            gauge.cents = Float.NaN
            tvVerdict.text = getString(
                R.string.sing_wrong_note, Note.nameOf(sungMidi), Note.nameOf(refMidi)
            )
            tvVerdict.setTextColor(ContextCompat.getColor(requireContext(), R.color.bad_red))
            recordScore(0)
        }
        tvStatus.text = getString(R.string.sing_done) + progressSuffix()
        refreshStats()
        btnStart.isEnabled = true
    }

    /** 跳进模进：do-mi-sol-si → re-fa-la-do → …（三度叠进，逐级模进） */
    private fun buildLeapSequence(pool: List<Int>): List<Int> {
        val leaps = mutableListOf<Int>()
        var i = 0
        while (i + 6 < pool.size) {
            leaps += listOf(pool[i], pool[i + 2], pool[i + 4], pool[i + 6])
            i++
        }
        return if (leaps.isEmpty()) pool else leaps
    }

    /** 每轮结束的序列进度（如 3/13） */
    private fun progressSuffix(): String {
        if (orderMode == 0 || currentSeq.isEmpty()) {
            return getString(R.string.sing_round_no, sessionRounds)
        }
        val pos = ((seqIndex - 1).coerceAtLeast(0) % currentSeq.size) + 1
        return getString(R.string.sing_seq_progress, pos, currentSeq.size)
    }

    private fun recordScore(score: Int) {
        Stats.recordSingRound(requireContext(), score)
        sessionRounds++
        sessionSum += score
        sessionBest = maxOf(sessionBest, score)
    }

    private fun playback() {
        val file = takeFile ?: return
        try {
            player?.release()
            player = MediaPlayer().apply {
                setDataSource(file.path)
                setOnPreparedListener { it.start() }
                setOnCompletionListener { it.release() }
                prepareAsync()
            }
        } catch (_: Exception) {
        }
    }

    private fun octaveLabel(shift: Int): String = when (shift) {
        -1 -> getString(R.string.octave_low)
        1 -> getString(R.string.octave_high)
        else -> "${shift} 个八度"
    }

    /** 绑定音域下拉框（全音域自选） */
    private fun bindRangeSpinners(view: View) {
        val adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            naturalMidis.map { Note.nameOf(it) }
        )
        val saved = AppSettings.singRange(requireContext())
        rangeStartMidi = saved[0]
        rangeEndMidi = saved[1]
        val spinFrom = view.findViewById<Spinner>(R.id.spin_from)
        val spinTo = view.findViewById<Spinner>(R.id.spin_to)
        spinFrom.adapter = adapter
        spinTo.adapter = adapter
        spinFrom.setSelection(naturalMidis.indexOf(saved[0]).coerceIn(0, naturalMidis.size - 1))
        spinTo.setSelection(
            naturalMidis.indexOf(saved[1]).let { if (it < 0) naturalMidis.size - 1 else it }
        )
        rebuildTransposeOptions()
        spinFrom.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                rangeStartMidi = naturalMidis[pos]
                if (rangeStartMidi > rangeEndMidi) spinTo.setSelection(pos) else saveRange()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        spinTo.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                rangeEndMidi = naturalMidis[pos]
                if (rangeEndMidi < rangeStartMidi) spinFrom.setSelection(pos) else saveRange()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun saveRange() {
        AppSettings.setSingRange(requireContext(), rangeStartMidi, rangeEndMidi)
        rebuildTransposeOptions() // 音域变了，重新给出可调整范围
    }

    override fun onPitch(freq: Double, confidence: Float, rms: Float) {
        if (recording && freq > 0 && rms > 0.003f) {
            synchronized(sungFreqs) { sungFreqs.add(freq) }
            requireActivity().runOnUiThread {
                if (!isAdded) return@runOnUiThread
                val centsVal = if (transpose != 0) {
                    Note.cents(freq, Note.midiToFreq(targetMidi + transpose))
                } else {
                    Note.centsToNearestOctave(freq, targetMidi)
                }
                gauge.cents = centsVal.toFloat()
                tvDetected.text = getString(
                    R.string.sing_detecting, Note.label(Note.nearestMidi(freq))
                )
            }
        }
    }

    private fun refreshStats() {
        tvStats.text = getString(
            R.string.sing_stats,
            Stats.singRounds(requireContext()),
            Stats.singAvg(requireContext()),
            Stats.singBest(requireContext())
        )
    }

    override fun onDestroyView() {
        handler.removeCallbacksAndMessages(null)
        countdown?.cancel()
        recording = false
        monitor?.stop()
        monitor = null
        player?.release()
        player = null
        super.onDestroyView()
    }
}
