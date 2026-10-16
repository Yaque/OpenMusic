package com.aiocw.openmusic.ui

import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.aiocw.openmusic.MainActivity
import com.aiocw.openmusic.R
import com.aiocw.openmusic.audio.MicPitchMonitor
import com.aiocw.openmusic.audio.WavIo
import com.aiocw.openmusic.music.MelodyParser
import com.aiocw.openmusic.music.Note
import com.aiocw.openmusic.music.Stats
import java.io.File
import kotlin.math.abs

/**
 * 模式五：跟唱 —— 自定义简谱序列 + 每音秒数，自动弹奏并检测跟唱情况；
 * 弹出的音与歌声混合录制，完成后可回听。默认模板：C4 起 · 1234567654321 · 3 秒/音。
 */
class MelodyFragment : Fragment(R.layout.fragment_melody), MicPitchMonitor.Listener {

    private val synth by lazy { (requireActivity() as MainActivity).synth }
    private var monitor: MicPitchMonitor? = null
    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null

    private lateinit var etMelody: EditText
    private lateinit var etInterval: EditText
    private lateinit var tvCountdown: TextView
    private lateinit var tvProgress: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvResults: TextView
    private lateinit var btnStart: Button
    private lateinit var btnEnd: Button
    private lateinit var btnPlay: Button
    private lateinit var melodyCurve: PitchCurveView
    private lateinit var melodyScroll: ScrollView
    private lateinit var cbFollow: CheckBox

    private val allMidis = (21..108).toList()
    private var startMidi = 60

    private var notes: List<Int?> = emptyList()
    private var intervalMs = 3000L
    private var playing = false
    private var currentIndex = 0
    private val currentSung = ArrayList<Double>()

    private var okCount = 0
    private var judgedCount = 0
    private var takeFile: File? = null
    private var lastFollowMs = 0L

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        etMelody = view.findViewById(R.id.et_melody)
        etInterval = view.findViewById(R.id.et_interval)
        tvCountdown = view.findViewById(R.id.tv_countdown)
        tvProgress = view.findViewById(R.id.tv_progress)
        tvStatus = view.findViewById(R.id.tv_status)
        tvResults = view.findViewById(R.id.tv_results)
        btnStart = view.findViewById(R.id.btn_melody_start)
        btnEnd = view.findViewById(R.id.btn_melody_end)
        btnPlay = view.findViewById(R.id.btn_melody_play)
        melodyCurve = view.findViewById(R.id.melody_curve)
        melodyScroll = view.findViewById(R.id.melody_scroll)
        cbFollow = view.findViewById(R.id.cb_follow)

        bindStartSpinner(view)
        btnStart.setOnClickListener { startTake() }
        btnEnd.setOnClickListener { endTake(finished = false) }
        btnPlay.setOnClickListener { playback() }
    }

    private fun bindStartSpinner(view: View) {
        val adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            allMidis.map { Note.nameOf(it) }
        )
        val spin = view.findViewById<Spinner>(R.id.spin_start)
        spin.adapter = adapter
        spin.setSelection(allMidis.indexOf(60)) // 默认 C4
        spin.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                startMidi = allMidis[pos]
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun startTake() {
        val parsed = MelodyParser.parseToMidi(etMelody.text.toString(), startMidi)
        if (parsed.isEmpty()) {
            tvStatus.text = getString(R.string.melody_empty)
            return
        }
        val sec = etInterval.text.toString().toFloatOrNull() ?: 3f
        intervalMs = (sec * 1000).toLong().coerceIn(500, 30000)
        notes = parsed
        okCount = 0
        judgedCount = 0
        tvResults.text = ""

        // 跟唱曲线：范围覆盖整段旋律（上下各留余量）
        val pitches = parsed.filterNotNull()
        val lo = (pitches.minOrNull() ?: startMidi) - 3
        val hi = (pitches.maxOrNull() ?: startMidi + 11) + 3
        melodyCurve.reset()
        melodyCurve.setRange(lo, hi)

        (requireActivity() as MainActivity).requestMic { granted ->
            if (!granted) {
                tvStatus.text = getString(R.string.mic_denied)
                return@requestMic
            }
            val m = MicPitchMonitor(this)
            if (!m.start()) {
                tvStatus.text = getString(R.string.mic_denied)
                return@requestMic
            }
            m.captureEnabled = true
            m.clearCapture()
            monitor = m
            synth.captureEnabled = true
            synth.clearCapture()

            playing = true
            btnStart.isEnabled = false
            btnEnd.isEnabled = true
            btnPlay.isEnabled = false
            tvStatus.text = getString(R.string.melody_recording)
            startCountdown()
        }
    }

    private fun startCountdown() {
        var n = 3
        tvCountdown.visibility = View.VISIBLE
        tvCountdown.text = "3"
        val tick = object : Runnable {
            override fun run() {
                if (!playing) return
                n--
                if (n > 0) {
                    tvCountdown.text = n.toString()
                    handler.postDelayed(this, 1000)
                } else {
                    tvCountdown.text = getString(R.string.melody_countdown_go)
                    handler.postDelayed({
                        tvCountdown.visibility = View.GONE
                        scheduleNote(0)
                    }, 600)
                }
            }
        }
        handler.postDelayed(tick, 1000)
    }

    private fun scheduleNote(i: Int) {
        if (!playing) return
        if (i >= notes.size) {
            endTake(finished = true)
            return
        }
        currentIndex = i
        currentSung.clear()
        val midi = notes[i]
        tvProgress.text = getString(
            R.string.melody_progress,
            i + 1,
            notes.size,
            if (midi == null) "休止" else Note.label(midi)
        )
        if (midi != null) synth.noteOn(midi)
        handler.postDelayed({
            judgeNote(i)
            scheduleNote(i + 1)
        }, intervalMs)
    }

    private fun judgeNote(i: Int) {
        val midi = notes[i]
        val line = when {
            midi == null -> getString(R.string.melody_rest, i + 1)
            currentSung.size < 3 -> {
                judgedCount++
                getString(R.string.melody_note_miss, i + 1, Note.label(midi))
            }
            else -> {
                judgedCount++
                val snapshot = synchronized(currentSung) { currentSung.sorted() }
                val median = snapshot[snapshot.size / 2]
                val sungMidi = Note.nearestMidi(median)
                val centsVal = Note.centsToNearestOctave(median, midi)
                val verdict = Note.verdict(centsVal)
                val correctNote = Note.samePitchClass(sungMidi, midi)
                val ok = correctNote && verdict == Note.Verdict.ON_PITCH
                if (ok) okCount++
                when {
                    !correctNote -> getString(
                        R.string.melody_note_off, i + 1, Note.label(midi),
                        "唱的是 ${Note.nameOf(sungMidi)}"
                    )
                    ok -> getString(R.string.melody_note_ok, i + 1, Note.label(midi))
                    verdict == Note.Verdict.CLOSE -> getString(
                        R.string.melody_note_close, i + 1, Note.label(midi),
                        "%.0f".format(centsVal) + " 音分"
                    )
                    else -> getString(
                        R.string.melody_note_off, i + 1, Note.label(midi), verdict.text
                    )
                }
            }
        }
        tvResults.text = tvResults.text.toString() + line + "\n"
    }

    /** 结束：保存「弹出的音 + 歌声」混合录音 */
    private fun endTake(finished: Boolean) {
        playing = false
        handler.removeCallbacksAndMessages(null)
        synth.captureEnabled = false
        monitor?.let { it.captureEnabled = false }

        val mic = monitor?.drainCapture() ?: ShortArray(0)
        val piano = synth.drainCapture()
        val mixed = WavIo.mix(mic, piano)
        if (mixed.isNotEmpty()) {
            val file = File(requireContext().cacheDir, "melody_take.wav")
            WavIo.writePcm16(file, mixed, MicPitchMonitor.SAMPLE_RATE)
            takeFile = file
        }
        monitor?.stop()
        monitor = null

        btnStart.isEnabled = true
        btnEnd.isEnabled = false
        btnPlay.isEnabled = takeFile != null
        tvCountdown.visibility = View.GONE

        if (finished) {
            val score = if (judgedCount == 0) 0 else okCount * 100 / judgedCount
            tvStatus.text = getString(R.string.melody_summary, score, okCount, judgedCount)
            Stats.recordMelodyRound(requireContext(), score)
        } else {
            tvStatus.text = getString(R.string.melody_stopped)
        }
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

    override fun onPitch(freq: Double, confidence: Float, rms: Float) {
        // 注意：本回调在采集线程上执行，UI 操作必须切回主线程
        val voiced = playing && freq > 0 && rms > 0.003f
        if (playing && currentIndex < notes.size && notes[currentIndex] != null && voiced) {
            synchronized(currentSung) { currentSung.add(freq) }
        }
        if (!playing) return
        val midi = if (voiced) Note.freqToMidi(freq) else Double.NaN
        requireActivity().runOnUiThread {
            if (!isAdded) return@runOnUiThread
            if (!voiced) {
                melodyCurve.addGap()
                return@runOnUiThread
            }
            melodyCurve.addPoint(midi)
            // 自动跟随：节流滚动，避免每帧触发滑动动画
            val now = android.os.SystemClock.elapsedRealtime()
            if (cbFollow.isChecked && now - lastFollowMs > 200) {
                lastFollowMs = now
                val y = melodyCurve.yOf(midi) - melodyScroll.height / 2f
                melodyScroll.smoothScrollTo(0, y.toInt().coerceAtLeast(0))
            }
        }
    }

    override fun onDestroyView() {
        handler.removeCallbacksAndMessages(null)
        playing = false
        monitor?.stop()
        monitor = null
        player?.release()
        player = null
        super.onDestroyView()
    }
}
