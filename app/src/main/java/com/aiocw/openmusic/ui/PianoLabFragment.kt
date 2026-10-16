package com.aiocw.openmusic.ui

import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.aiocw.openmusic.MainActivity
import com.aiocw.openmusic.R
import com.aiocw.openmusic.audio.MicPitchMonitor
import com.aiocw.openmusic.music.AppSettings
import com.aiocw.openmusic.music.Note
import com.aiocw.openmusic.music.Stats
import kotlin.math.abs

/**
 * 模式一：钢琴实验室 —— 弹一个键出一个声，同时监听并识别你唱的音。
 */
class PianoLabFragment : Fragment(R.layout.fragment_piano_lab),
    MicPitchMonitor.Listener, PianoView.Listener {

    private lateinit var synth: com.aiocw.openmusic.audio.PianoSynth
    private var monitor: MicPitchMonitor? = null

    private lateinit var tvTargetNote: TextView
    private lateinit var tvTargetSub: TextView
    private lateinit var tvSungNote: TextView
    private lateinit var tvSungSub: TextView
    private lateinit var tvVerdict: TextView
    private lateinit var tvRoundScore: TextView
    private lateinit var tvStats: TextView
    private lateinit var tvMonitorState: TextView
    private lateinit var gauge: PitchGauge
    private lateinit var btnMonitor: Button
    private lateinit var piano: PianoView
    private lateinit var pianoScroll: HorizontalScrollView

    private var targetMidi: Int? = null
    private var wantMonitor = true

    /** 监听音调：准星升降的半音数（负=降 / 正=升）。发声不变，只移动判音准星 */
    private var transpose = 0
    private lateinit var spinTranspose: Spinner
    private var transposeShifts = listOf(0)
    private var rangeStartMidi = 60
    private var rangeEndMidi = 72

    // 一轮演唱的音分样本
    private val roundCents = ArrayList<Double>()
    private var unvoicedFrames = 0
    private var voicedFrames = 0

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        synth = (requireActivity() as MainActivity).synth

        tvTargetNote = view.findViewById(R.id.tv_target_note)
        tvTargetSub = view.findViewById(R.id.tv_target_sub)
        tvSungNote = view.findViewById(R.id.tv_sung_note)
        tvSungSub = view.findViewById(R.id.tv_sung_sub)
        tvVerdict = view.findViewById(R.id.tv_verdict)
        tvRoundScore = view.findViewById(R.id.tv_round_score)
        tvStats = view.findViewById(R.id.tv_piano_stats)
        tvMonitorState = view.findViewById(R.id.tv_monitor_state)
        gauge = view.findViewById(R.id.pitch_gauge)
        btnMonitor = view.findViewById(R.id.btn_monitor)

        piano = view.findViewById(R.id.piano_view)
        piano.listener = this
        pianoScroll = view.findViewById(R.id.piano_scroll)

        bindTransposeSpinner(view)
        bindRangeSpinners(view)
        piano.applySettings()

        btnMonitor.setOnClickListener {
            if (monitor?.isRunning == true) {
                wantMonitor = false
                stopMonitor()
            } else {
                wantMonitor = true
                (requireActivity() as MainActivity).requestMic { granted ->
                    if (granted) startMonitor() else {
                        tvMonitorState.text = getString(R.string.mic_denied)
                        btnMonitor.text = getString(R.string.start_monitor)
                    }
                }
            }
        }
        refreshStats()
    }

    /** 监听音调：升降幅度由用户决定，系统按当前音域自动给出可选范围 */
    private fun bindTransposeSpinner(view: View) {
        spinTranspose = view.findViewById(R.id.spin_transpose)
        spinTranspose.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                transpose = transposeShifts.getOrElse(pos) { 0 }
                AppSettings.setPianoShift(requireContext(), transpose)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        rebuildTransposeOptions()
    }

    private fun rebuildTransposeOptions() {
        val start = rangeStartMidi
        val maxDown = (start - 21).coerceAtLeast(0)        // 准星不低于 A0
        val maxUp = (108 - rangeEndMidi).coerceAtLeast(0)  // 准星不高于 C8
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
        var sel = transposeShifts.indexOf(AppSettings.pianoShift(requireContext()))
        if (sel < 0) {
            sel = transposeShifts.indexOf(0)
            transpose = 0
            AppSettings.setPianoShift(requireContext(), 0)
        }
        transpose = transposeShifts[sel]
        spinTranspose.setSelection(sel)
    }

    /** 可弹范围：全音域自定义起止（A0–C8 逐键可选） */
    private fun bindRangeSpinners(view: View) {
        val allMidis = (21..108).toList()
        val adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            allMidis.map { Note.nameOf(it) }
        )
        val spinFrom = view.findViewById<Spinner>(R.id.spin_from)
        val spinTo = view.findViewById<Spinner>(R.id.spin_to)
        spinFrom.adapter = adapter
        spinTo.adapter = adapter
        val saved = AppSettings.pianoRange(requireContext())
        val fromIdx = allMidis.indexOf(saved[0]).coerceIn(0, allMidis.size - 1)
        val toIdx = allMidis.indexOf(saved[1]).let { if (it < 0) allMidis.size - 1 else it }
        spinFrom.setSelection(fromIdx)
        spinTo.setSelection(toIdx)
        applyRange(saved[0], saved[1]) // 显式恢复，不依赖下拉框回调
        spinFrom.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val s = allMidis[pos]
                val e = allMidis[spinTo.selectedItemPosition]
                if (s > e) spinTo.setSelection(pos) else applyRange(s, e)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        spinTo.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val s = allMidis[spinFrom.selectedItemPosition]
                val e = allMidis[pos]
                if (e < s) spinFrom.setSelection(pos) else applyRange(s, e)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun applyRange(start: Int, end: Int) {
        rangeStartMidi = start
        rangeEndMidi = end
        piano.setRange(start, end)
        pianoScroll.scrollTo(0, 0)
        AppSettings.setPianoRange(requireContext(), start, end)
        rebuildTransposeOptions() // 音域变了，重新给出可调整范围
    }

    override fun onResume() {
        super.onResume()
        piano.applySettings()
        if (wantMonitor) (requireActivity() as MainActivity).requestMic { granted ->
            if (granted) startMonitor()
        }
    }

    override fun onPause() {
        stopMonitor()
        super.onPause()
    }

    private fun startMonitor() {
        if (monitor?.isRunning == true) return
        val m = MicPitchMonitor(this)
        if (m.start()) {
            monitor = m
            tvMonitorState.text = getString(R.string.monitor_running)
            btnMonitor.text = getString(R.string.stop_monitor)
        } else {
            tvMonitorState.text = getString(R.string.mic_denied)
        }
    }

    private fun stopMonitor() {
        monitor?.stop()
        monitor = null
        tvMonitorState.text = getString(R.string.monitor_stopped)
        btnMonitor.text = getString(R.string.start_monitor)
    }

    override fun onKeyDown(midi: Int) {
        // 发声不变：弹 C4 出 C4；降调只影响判音准星（C3）
        synth.noteOn(midi)
        if (targetMidi != midi) finishRound()
        targetMidi = midi
        unvoicedFrames = 0
        tvTargetNote.text = Note.nameOf(midi)
        tvTargetSub.text = if (transpose == 0) {
            getString(R.string.target_sub, Note.solfegeOf(midi), Note.jianpuOf(midi))
        } else {
            getString(
                R.string.target_sub_r, Note.solfegeOf(midi), Note.jianpuOf(midi),
                Note.nameOf(midi + transpose)
            )
        }
        tvRoundScore.text = ""
        tvVerdict.text = ""
    }

    override fun onKeyUp(midi: Int) {
        synth.release(midi)
    }

    /** 来自采集线程 */
    override fun onPitch(freq: Double, confidence: Float, rms: Float) {
        requireActivity().runOnUiThread {
            if (!isAdded) return@runOnUiThread
            if (freq > 0 && rms > 0.003f) {
                val sungMidi = Note.nearestMidi(freq)
                val target = targetMidi
                val refMidi = target?.plus(transpose)   // 准星：监听音调移动后的位置
                val strict = transpose != 0
                val centsVal = when {
                    refMidi == null -> 0.0
                    // 降调开：严格以准星（如 C3）为准
                    strict -> Note.cents(freq, Note.midiToFreq(refMidi))
                    // 降调关：同音名跨八度算唱对
                    else -> Note.centsToNearestOctave(freq, refMidi)
                }
                tvSungNote.text = Note.nameOf(sungMidi)
                tvSungSub.text = getString(
                    R.string.sung_sub,
                    Note.solfegeOf(sungMidi), Note.jianpuOf(sungMidi),
                    "%.1f".format(freq), "%.0f".format(centsVal)
                )
                when {
                    refMidi == null -> {
                        gauge.cents = Float.NaN
                        tvVerdict.text = ""
                    }
                    strict -> {
                        // 严格对准星判定（针头以准星为 0 点）
                        gauge.cents = centsVal.toFloat()
                        val v = Note.verdict(centsVal)
                        tvVerdict.text = v.text
                        tvVerdict.setTextColor(
                            ContextCompat.getColor(
                                requireContext(),
                                when (v) {
                                    Note.Verdict.ON_PITCH -> R.color.ok_green
                                    Note.Verdict.CLOSE -> R.color.warn_amber
                                    else -> R.color.bad_red
                                }
                            )
                        )
                        roundCents.add(abs(centsVal))
                    }
                    // 同音名（含八度差）即算唱对：男声低八度唱 C4 → C3 也是对的
                    Note.samePitchClass(sungMidi, refMidi) -> {
                        gauge.cents = centsVal.toFloat()
                        val v = Note.verdict(centsVal)
                        tvVerdict.text = when (val shift = Note.octaveShiftOf(sungMidi, refMidi)) {
                            0 -> v.text
                            -1 -> getString(R.string.verdict_octave, v.text, getString(R.string.octave_low))
                            1 -> getString(R.string.verdict_octave, v.text, getString(R.string.octave_high))
                            else -> getString(R.string.verdict_octave, v.text, shift.toString())
                        }
                        tvVerdict.setTextColor(
                            ContextCompat.getColor(
                                requireContext(),
                                when (v) {
                                    Note.Verdict.ON_PITCH -> R.color.ok_green
                                    Note.Verdict.CLOSE -> R.color.warn_amber
                                    else -> R.color.bad_red
                                }
                            )
                        )
                        roundCents.add(abs(centsVal))
                    }
                    else -> {
                        gauge.cents = Float.NaN
                        tvVerdict.text = getString(
                            R.string.verdict_wrong_note, Note.nameOf(sungMidi), Note.nameOf(refMidi)
                        )
                        tvVerdict.setTextColor(ContextCompat.getColor(requireContext(), R.color.bad_red))
                        roundCents.add(100.0)
                    }
                }
                voicedFrames++
                unvoicedFrames = 0
            } else {
                unvoicedFrames++
                if (unvoicedFrames == 1) {
                    tvVerdict.text = ""
                    gauge.cents = Float.NaN
                }
                if (unvoicedFrames > 12) finishRound()
            }
        }
    }

    private fun finishRound() {
        if (roundCents.size >= 8) {
            val avg = roundCents.average().coerceAtMost(120.0)
            val score = Note.scoreOf(avg)
            tvRoundScore.text = getString(R.string.round_score, score)
            Stats.recordPianoRound(requireContext(), score)
            refreshStats()
        }
        roundCents.clear()
    }

    private fun refreshStats() {
        tvStats.text = getString(
            R.string.piano_stats,
            Stats.pianoRounds(requireContext()),
            Stats.pianoAvg(requireContext()),
            Stats.pianoBest(requireContext())
        )
    }
}
