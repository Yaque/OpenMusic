package com.aiocw.openmusic.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.aiocw.openmusic.MainActivity
import com.aiocw.openmusic.R
import com.aiocw.openmusic.music.AppSettings
import com.aiocw.openmusic.music.Note
import com.aiocw.openmusic.music.Stats
import kotlin.random.Random

/**
 * 模式二：听音选择 —— 随机播放所选音域内的 C 大调自然音，
 * 回答选项按音域动态生成（每个音一个选项，显示唱名/简谱/音名）。
 * 提供「开始 / 结束」控制、「参考音 C4」，每轮重新计分。
 */
class EarQuizFragment : Fragment(R.layout.fragment_ear_quiz) {

    private lateinit var synth: com.aiocw.openmusic.audio.PianoSynth
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var tvQuestion: TextView
    private lateinit var tvFeedback: TextView
    private lateinit var tvStats: TextView
    private lateinit var answersContainer: LinearLayout
    private lateinit var btnStart: Button
    private lateinit var btnEnd: Button

    private var optionButtons = HashMap<Int, Button>() // midi -> 按钮

    private var targetMidi = 60
    private var answered = false
    private var sessionActive = false
    private var sessionAnswered = 0
    private var sessionCorrect = 0
    private var streak = 0

    // 开始前试听音域（可选，每音秒数自定义）
    private var previewRunning = false
    private var previewNotes = listOf<Int>()
    private var previewIndex = 0
    private var previewIntervalMs = 3000L
    private lateinit var cbPreview: CheckBox
    private lateinit var etPreviewSec: EditText
    private lateinit var tvPreviewNote: TextView

    // 自选音域：全音域自然音（A0–C8）任选起止，默认 C4–C5（持久保存）
    private val naturalMidis = (21..108).filter { Note.isNatural(it) }
    private var rangeStartMidi = 60
    private var rangeEndMidi = 72

    private val advance = Runnable { newQuestion() }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        synth = (requireActivity() as MainActivity).synth

        tvQuestion = view.findViewById(R.id.tv_question)
        tvFeedback = view.findViewById(R.id.tv_feedback)
        tvStats = view.findViewById(R.id.tv_ear_stats)
        answersContainer = view.findViewById(R.id.answers_container)
        btnStart = view.findViewById(R.id.btn_start)
        btnEnd = view.findViewById(R.id.btn_end)

        btnStart.setOnClickListener { startSession() }
        btnEnd.setOnClickListener { endSession() }
        cbPreview = view.findViewById(R.id.cb_preview)
        etPreviewSec = view.findViewById(R.id.et_preview_sec)
        tvPreviewNote = view.findViewById(R.id.tv_preview_note)
        view.findViewById<Button>(R.id.btn_ref).setOnClickListener {
            synth.noteOn(60) // 参考音：do·C4
        }
        view.findViewById<Button>(R.id.btn_replay).setOnClickListener {
            if (sessionActive) synth.noteOn(targetMidi)
        }

        bindRangeSpinners(view)
        refreshStats()
        setIdleState()
    }

    /** 按音域动态生成回答选项（唱名/简谱/音名） */
    private fun rebuildOptions() {
        answersContainer.removeAllViews()
        optionButtons.clear()
        val notes = (rangeStartMidi..rangeEndMidi).filter { Note.isNatural(it) }
        var row: LinearLayout? = null
        notes.forEachIndexed { i, midi ->
            if (i % 4 == 0) {
                val newRow = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                }
                answersContainer.addView(newRow)
                row = newRow
            }
            val b = Button(requireContext()).apply {
                text = getString(
                    R.string.answer_option,
                    Note.solfegeWithOctave(midi),
                    Note.jianpuOf(midi),
                    Note.nameOf(midi)
                )
                textSize = 12f
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                ).apply { setMargins(4, 4, 4, 4) }
                isEnabled = false
                setOnClickListener { onAnswer(midi) }
            }
            row?.addView(b)
            optionButtons[midi] = b
        }
    }

    private fun setIdleState() {
        sessionActive = false
        previewRunning = false
        btnStart.isEnabled = true
        btnEnd.isEnabled = false
        tvPreviewNote.visibility = View.GONE
        optionButtons.values.forEach { it.isEnabled = false }
        tvQuestion.text = getString(R.string.ear_idle)
    }

    private fun startSession() {
        if (cbPreview.isChecked) startPreview() else beginQuiz()
    }

    /** 开始前先试听音域全部音（每音秒数自定义），听完自动进入答题 */
    private fun startPreview() {
        previewNotes = (rangeStartMidi..rangeEndMidi).filter { Note.isNatural(it) }
        if (previewNotes.isEmpty()) {
            beginQuiz()
            return
        }
        previewIntervalMs = ((etPreviewSec.text.toString().toFloatOrNull() ?: 3f) * 1000)
            .toLong().coerceIn(500, 30000)
        previewRunning = true
        previewIndex = 0
        btnStart.isEnabled = false
        btnEnd.isEnabled = true
        tvFeedback.text = ""
        tvPreviewNote.visibility = View.VISIBLE
        playPreviewNote()
    }

    private val previewStep = Runnable { playPreviewNote() }

    private fun playPreviewNote() {
        if (!previewRunning) return
        if (previewIndex >= previewNotes.size) {
            previewRunning = false
            beginQuiz()
            return
        }
        val midi = previewNotes[previewIndex]
        tvQuestion.text = getString(
            R.string.ear_preview_progress, previewIndex + 1, previewNotes.size, Note.label(midi)
        )
        // 大字显示当前音的唱名·简谱
        tvPreviewNote.text = getString(
            R.string.ear_preview_note, Note.solfegeWithOctave(midi), Note.jianpuOf(midi)
        )
        synth.noteOn(midi)
        previewIndex++
        handler.postDelayed(previewStep, previewIntervalMs)
    }

    private fun beginQuiz() {
        sessionActive = true
        sessionAnswered = 0
        sessionCorrect = 0
        streak = 0
        btnStart.isEnabled = false
        btnEnd.isEnabled = true
        tvPreviewNote.visibility = View.GONE
        optionButtons.values.forEach { it.isEnabled = true }
        newQuestion()
    }

    private fun endSession() {
        handler.removeCallbacks(advance)
        handler.removeCallbacks(previewStep)
        if (previewRunning) {
            // 试听中：结束即取消试听，回到待机
            setIdleState()
            return
        }
        val acc = if (sessionAnswered == 0) 0 else sessionCorrect * 100 / sessionAnswered
        setIdleState()
        // 下次开始重新计分
        tvQuestion.text = getString(
            R.string.ear_session_summary, sessionAnswered, sessionCorrect, acc
        )
        tvFeedback.text = ""
        refreshStats()
    }

    /** 绑定音域下拉框（全音域自选） */
    private fun bindRangeSpinners(view: View) {
        val adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            naturalMidis.map { Note.nameOf(it) }
        )
        val saved = AppSettings.earRange(requireContext())
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
        rebuildOptions() // 显式恢复选项，不依赖下拉框回调
        setIdleState()
        spinFrom.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                rangeStartMidi = naturalMidis[pos]
                if (rangeStartMidi > rangeEndMidi) spinTo.setSelection(pos) else onRangeChanged()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        spinTo.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                rangeEndMidi = naturalMidis[pos]
                if (rangeEndMidi < rangeStartMidi) spinFrom.setSelection(pos) else onRangeChanged()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun onRangeChanged() {
        AppSettings.setEarRange(requireContext(), rangeStartMidi, rangeEndMidi)
        rebuildOptions()
        if (sessionActive) {
            optionButtons.values.forEach { it.isEnabled = true }
            newQuestion()
        } else {
            setIdleState()
        }
    }

    private fun newQuestion() {
        if (!sessionActive) return
        handler.removeCallbacks(advance)
        answered = false
        val pool = (rangeStartMidi..rangeEndMidi).filter { Note.isNatural(it) }
        targetMidi = pool[Random.nextInt(pool.size)]
        tvQuestion.text = getString(R.string.ear_question_no, sessionAnswered + 1)
        tvFeedback.text = ""
        optionButtons.values.forEach {
            it.alpha = 1f
            it.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.btn_face))
        }
        synth.noteOn(targetMidi)
    }

    private fun onAnswer(midi: Int) {
        if (!sessionActive || answered) return
        answered = true
        sessionAnswered++
        val correct = midi == targetMidi
        val ctx = requireContext()
        if (correct) {
            sessionCorrect++
            streak++
            tvFeedback.text = getString(R.string.answer_correct, Note.label(targetMidi))
            tvFeedback.setTextColor(ContextCompat.getColor(ctx, R.color.ok_green))
            optionButtons[midi]?.setBackgroundColor(ContextCompat.getColor(ctx, R.color.ok_green))
        } else {
            streak = 0
            tvFeedback.text = getString(R.string.answer_wrong, Note.label(targetMidi))
            tvFeedback.setTextColor(ContextCompat.getColor(ctx, R.color.bad_red))
            optionButtons[midi]?.setBackgroundColor(ContextCompat.getColor(ctx, R.color.bad_red))
            optionButtons[targetMidi]?.setBackgroundColor(ContextCompat.getColor(ctx, R.color.ok_green))
        }
        optionButtons[midi]?.alpha = 0.85f
        Stats.recordEarAnswer(ctx, correct, streak)
        refreshStats()
        handler.postDelayed(advance, 1600)
    }

    private fun refreshStats() {
        val total = Stats.earTotal(requireContext())
        val correct = Stats.earCorrect(requireContext())
        val acc = if (total == 0) 0 else correct * 100 / total
        tvStats.text = getString(
            R.string.ear_stats, total, acc, streak, Stats.earBestStreak(requireContext())
        )
    }

    override fun onDestroyView() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroyView()
    }
}
