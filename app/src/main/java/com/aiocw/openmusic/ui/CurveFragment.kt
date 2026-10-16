package com.aiocw.openmusic.ui

import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.aiocw.openmusic.MainActivity
import com.aiocw.openmusic.R
import com.aiocw.openmusic.audio.MicPitchMonitor
import com.aiocw.openmusic.music.AppSettings
import com.aiocw.openmusic.music.Note

/**
 * 模式四：音高曲线 —— 全音域实时监听，横轴时间、纵轴音高，
 * 实时提示当前唱的音（音域可自选并持久保存）。
 */
class CurveFragment : Fragment(R.layout.fragment_curve), MicPitchMonitor.Listener {

    private var monitorOrNull: MicPitchMonitor? = null

    private lateinit var curveView: PitchCurveView
    private lateinit var tvCurrent: TextView
    private lateinit var tvMonitorState: TextView
    private lateinit var btnMonitor: Button
    private lateinit var curveScroll: ScrollView
    private lateinit var cbFollow: CheckBox

    private var wantMonitor = true
    private var lastFollowMs = 0L

    // 自选音域：全 88 键任选起止（默认全域）
    private val allMidis = (21..108).toList()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        curveView = view.findViewById(R.id.curve_view)
        tvCurrent = view.findViewById(R.id.tv_curve_current)
        tvMonitorState = view.findViewById(R.id.tv_monitor_state)
        btnMonitor = view.findViewById(R.id.btn_monitor)
        curveScroll = view.findViewById(R.id.curve_scroll)
        cbFollow = view.findViewById(R.id.cb_follow)

        btnMonitor.setOnClickListener {
            if (monitorOrNull?.isRunning == true) {
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

        view.findViewById<Button>(R.id.btn_curve_reset).setOnClickListener {
            curveView.reset()
        }

        bindRangeSpinners(view)
    }

    /** 音域下拉（全音域自选，默认全域 A0–C8，持久保存） */
    private fun bindRangeSpinners(view: View) {
        val adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            allMidis.map { Note.nameOf(it) }
        )
        val saved = AppSettings.curveRange(requireContext())
        val spinFrom = view.findViewById<Spinner>(R.id.spin_from)
        val spinTo = view.findViewById<Spinner>(R.id.spin_to)
        spinFrom.adapter = adapter
        spinTo.adapter = adapter
        spinFrom.setSelection(allMidis.indexOf(saved[0]).coerceIn(0, allMidis.size - 1))
        spinTo.setSelection(
            allMidis.indexOf(saved[1]).let { if (it < 0) allMidis.size - 1 else it }
        )
        curveView.setRange(saved[0], saved[1]) // 显式恢复

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

    private fun applyRange(s: Int, e: Int) {
        curveView.setRange(s, e)
        AppSettings.setCurveRange(requireContext(), s, e)
    }

    override fun onResume() {
        super.onResume()
        if (wantMonitor) (requireActivity() as MainActivity).requestMic { granted ->
            if (granted) startMonitor()
        }
    }

    override fun onPause() {
        stopMonitor()
        super.onPause()
    }

    private fun startMonitor() {
        if (monitorOrNull?.isRunning == true) return
        val m = MicPitchMonitor(this)
        if (m.start()) {
            monitorOrNull = m
            tvMonitorState.text = getString(R.string.curve_monitor_running)
            btnMonitor.text = getString(R.string.stop_monitor)
        } else {
            tvMonitorState.text = getString(R.string.mic_denied)
        }
    }

    private fun stopMonitor() {
        monitorOrNull?.stop()
        monitorOrNull = null
        tvMonitorState.text = getString(R.string.monitor_stopped)
        btnMonitor.text = getString(R.string.start_monitor)
    }

    override fun onPitch(freq: Double, confidence: Float, rms: Float) {
        requireActivity().runOnUiThread {
            if (!isAdded) return@runOnUiThread
            if (freq > 0 && rms > 0.003f) {
                val midi = Note.freqToMidi(freq)
                curveView.addPoint(midi)
                // 自动跟随：节流滚动，避免每帧触发滑动动画
                val now = android.os.SystemClock.elapsedRealtime()
                if (cbFollow.isChecked && now - lastFollowMs > 200) {
                    lastFollowMs = now
                    val y = curveView.yOf(midi) - curveScroll.height / 2f
                    curveScroll.smoothScrollTo(0, y.toInt().coerceAtLeast(0))
                }
                val sungMidi = Note.nearestMidi(freq)
                tvCurrent.text = getString(
                    R.string.curve_current,
                    Note.label(sungMidi), "%.1f".format(freq)
                )
            } else {
                curveView.addGap()
                tvCurrent.text = getString(R.string.curve_no_voice)
            }
        }
    }
}
