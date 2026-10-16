package com.aiocw.openmusic

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import com.aiocw.openmusic.audio.PianoSynth
import com.aiocw.openmusic.music.AppSettings
import com.aiocw.openmusic.music.Stats
import com.aiocw.openmusic.ui.CurveFragment
import com.aiocw.openmusic.ui.EarQuizFragment
import com.aiocw.openmusic.ui.MelodyFragment
import com.aiocw.openmusic.ui.PianoLabFragment
import com.aiocw.openmusic.ui.SingTestFragment
import com.google.android.material.bottomnavigation.BottomNavigationView

class MainActivity : AppCompatActivity() {

    /** 全局共享合成器（三个页面都用） */
    val synth: PianoSynth by lazy { PianoSynth() }

    private var micCallback: ((Boolean) -> Unit)? = null

    /** 当前所在标签页（重新计分按当前页生效） */
    private var currentTab = R.id.nav_piano

    private val micPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            micCallback?.invoke(granted)
            micCallback = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppSettings.migrateOnce(this)
        applyThemeMode()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        enableEdgeToEdge()
        synth.start()

        val nav = findViewById<BottomNavigationView>(R.id.bottom_nav)
        nav.setOnItemSelectedListener { item ->
            currentTab = item.itemId
            val f: Fragment = when (item.itemId) {
                R.id.nav_piano -> PianoLabFragment()
                R.id.nav_ear -> EarQuizFragment()
                R.id.nav_sing -> SingTestFragment()
                R.id.nav_curve -> CurveFragment()
                else -> MelodyFragment()
            }
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, f)
                .commit()
            true
        }
        if (savedInstanceState == null) {
            nav.selectedItemId = R.id.nav_piano
        }

        findViewById<Button>(R.id.btn_settings).setOnClickListener { showSettings() }
        findViewById<Button>(R.id.btn_about).setOnClickListener { showAbout() }
        findViewById<Button>(R.id.btn_records).setOnClickListener { showScoreRecords() }
        findViewById<Button>(R.id.btn_score_reset).setOnClickListener { confirmResetScore() }
    }

    /** 重新计分：清零当前页的计分记录（带确认） */
    private fun confirmResetScore() {
        val label = when (currentTab) {
            R.id.nav_piano -> "钢琴"
            R.id.nav_ear -> "听音"
            R.id.nav_sing -> "模唱"
            R.id.nav_melody -> "跟唱"
            else -> null // 曲线页无计分
        } ?: return
        AlertDialog.Builder(this)
            .setTitle(R.string.score_reset)
            .setMessage(getString(R.string.reset_confirm, label))
            .setPositiveButton(R.string.reset_ok) { _, _ ->
                when (currentTab) {
                    R.id.nav_piano -> Stats.resetPiano(this)
                    R.id.nav_ear -> Stats.resetEar(this)
                    R.id.nav_sing -> Stats.resetSing(this)
                    R.id.nav_melody -> Stats.resetMelody(this)
                }
                recreate()
            }
            .setNegativeButton(R.string.about_close, null)
            .show()
    }

    /** 外观配色：跟随系统 / 浅色 / 深色 */
    private fun applyThemeMode() {
        AppCompatDelegate.setDefaultNightMode(
            when (AppSettings.themeMode(this)) {
                AppSettings.THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                AppSettings.THEME_DARK -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
    }

    /** 设置：键盘标签显示 / 外观配色 / 琴键宽长 */
    private fun showSettings() {
        val v = layoutInflater.inflate(R.layout.dialog_settings, null)
        val rgLabel = v.findViewById<RadioGroup>(R.id.rg_label)
        val rgTheme = v.findViewById<RadioGroup>(R.id.rg_theme)
        val seekW = v.findViewById<SeekBar>(R.id.seek_key_width)
        val seekH = v.findViewById<SeekBar>(R.id.seek_key_height)
        val tvWidth = v.findViewById<TextView>(R.id.tv_key_width)
        val tvHeight = v.findViewById<TextView>(R.id.tv_key_height)

        when (AppSettings.labelMode(this)) {
            AppSettings.LABEL_JIANPU -> rgLabel.check(R.id.rb_jianpu)
            AppSettings.LABEL_NAMES -> rgLabel.check(R.id.rb_names)
            AppSettings.LABEL_ALL -> rgLabel.check(R.id.rb_label_all)
            else -> rgLabel.check(R.id.rb_solfege)
        }
        when (AppSettings.themeMode(this)) {
            AppSettings.THEME_LIGHT -> rgTheme.check(R.id.rb_theme_light)
            AppSettings.THEME_DARK -> rgTheme.check(R.id.rb_theme_dark)
            else -> rgTheme.check(R.id.rb_theme_system)
        }
        val widthDp = AppSettings.keyWidthDp(this).toInt()
        val heightDp = AppSettings.keyHeightDp(this).toInt()
        seekW.progress = widthDp - 20
        seekH.progress = heightDp - 20
        tvWidth.text = getString(R.string.settings_key_width, widthDp)
        tvHeight.text = getString(R.string.settings_key_height, heightDp)
        seekW.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                tvWidth.text = getString(R.string.settings_key_width, progress + 20)
            }

            override fun onStartTrackingTouch(sb: SeekBar?) = Unit
            override fun onStopTrackingTouch(sb: SeekBar?) = Unit
        })
        seekH.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                tvHeight.text = getString(R.string.settings_key_height, progress + 20)
            }

            override fun onStartTrackingTouch(sb: SeekBar?) = Unit
            override fun onStopTrackingTouch(sb: SeekBar?) = Unit
        })

        AlertDialog.Builder(this)
            .setTitle(R.string.settings_title)
            .setView(v)
            .setPositiveButton(R.string.settings_done) { _, _ ->
                AppSettings.setLabelMode(
                    this,
                    when (rgLabel.checkedRadioButtonId) {
                        R.id.rb_jianpu -> AppSettings.LABEL_JIANPU
                        R.id.rb_names -> AppSettings.LABEL_NAMES
                        R.id.rb_label_all -> AppSettings.LABEL_ALL
                        else -> AppSettings.LABEL_SOLFEGE
                    }
                )
                AppSettings.setThemeMode(
                    this,
                    when (rgTheme.checkedRadioButtonId) {
                        R.id.rb_theme_light -> AppSettings.THEME_LIGHT
                        R.id.rb_theme_dark -> AppSettings.THEME_DARK
                        else -> AppSettings.THEME_SYSTEM
                    }
                )
                AppSettings.setKeyWidthDp(this, seekW.progress + 20f)
                AppSettings.setKeyHeightDp(this, seekH.progress + 20f)
                applyThemeMode()
                recreate()
            }
            .setNegativeButton(R.string.about_close, null)
            .show()
    }

    /** 关于：功能介绍 + 开源地址 */
    private fun showAbout() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.about_title, getString(R.string.app_name)))
            .setMessage(R.string.about_body)
            .setPositiveButton(R.string.about_open) { _, _ ->
                startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/yaque/openmusic"))
                )
            }
            .setNegativeButton(R.string.about_close, null)
            .show()
    }

    /** 统一计分记录（钢琴/听音/模唱/跟唱） */
    fun showScoreRecords() {
        val earTotal = Stats.earTotal(this)
        val earAcc = if (earTotal == 0) 0 else Stats.earCorrect(this) * 100 / earTotal
        val msg = listOf(
            getString(
                R.string.records_piano,
                Stats.pianoRounds(this), Stats.pianoAvg(this), Stats.pianoBest(this)
            ),
            getString(
                R.string.records_ear,
                earTotal, earAcc, Stats.earBestStreak(this)
            ),
            getString(
                R.string.records_sing,
                Stats.singRounds(this), Stats.singAvg(this), Stats.singBest(this)
            ),
            getString(
                R.string.records_melody,
                Stats.melodyRounds(this), Stats.melodyAvg(this), Stats.melodyBest(this)
            ),
        ).joinToString("\n\n")
        AlertDialog.Builder(this)
            .setTitle(R.string.records_title)
            .setMessage(msg)
            .setPositiveButton(R.string.about_close, null)
            .show()
    }

    private fun enableEdgeToEdge() {
        val root = findViewById<android.view.View>(R.id.main_root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }

    /** 请求麦克风权限（带回调） */
    fun requestMic(onResult: (Boolean) -> Unit) {
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            onResult(true)
        } else {
            micCallback = onResult
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    override fun onDestroy() {
        synth.stop()
        super.onDestroy()
    }
}
