package com.aiocw.openmusic.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.aiocw.openmusic.music.AppSettings
import com.aiocw.openmusic.music.Note
import kotlin.math.min

/**
 * 钢琴键盘 View：默认全域 88 键（A0–C8），可 setRange() 限定可弹范围。
 * 键宽可自定义；每个键标注自己的音名/唱名/简谱（跟随全局设置）。
 * 键位总宽超出屏幕时放入 HorizontalScrollView 左右滑动；支持多点触控。
 */
class PianoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    interface Listener {
        fun onKeyDown(midi: Int)
        fun onKeyUp(midi: Int) {}
    }

    var listener: Listener? = null

    private var rangeStart = 21   // A0
    private var rangeEnd = 108    // C8

    private var labelMode = AppSettings.LABEL_SOLFEGE
    private var keyWidthDp = 34f
    private var keyHeightDp = 120f

    private val whiteKeys = ArrayList<Int>()
    private val blackKeys = ArrayList<Pair<Int, Int>>() // (midi, 左侧白键序号)

    private val pressed = HashSet<Int>()
    private val pointerMap = HashMap<Int, Int>() // pointerId -> midi

    private val whitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val whitePressedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFFB3D4FC.toInt()
    }
    private val blackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFF1D1D22.toInt()
    }
    private val blackPressedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFF4A78C4.toInt()
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = 0xFF9AA0A6.toInt()
    }
    // 白键上的文字（白键恒为浅色，文字用深色；与主题无关）
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF5F6368.toInt()
        textAlign = Paint.Align.CENTER
    }
    // 黑键上的文字（黑键恒为深色，文字用浅色）
    private val blackTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFBDC1C6.toInt()
        textAlign = Paint.Align.CENTER
    }
    private val rect = RectF()

    init {
        isClickable = true
        rebuild()
        applySettings()
    }

    /** 读取全局设置（标签显示方式 / 键宽 / 键长） */
    fun applySettings() {
        labelMode = AppSettings.labelMode(context)
        keyWidthDp = AppSettings.keyWidthDp(context)
        keyHeightDp = AppSettings.keyHeightDp(context)
        requestLayout()
        invalidate()
    }

    /** 设置可弹范围（midi，含端点） */
    fun setRange(start: Int, end: Int) {
        rangeStart = min(start, end)
        rangeEnd = if (start <= end) end else start
        rebuild()
        pressed.clear()
        pointerMap.clear()
        requestLayout()
        invalidate()
    }

    private fun rebuild() {
        whiteKeys.clear()
        blackKeys.clear()
        for (m in rangeStart..rangeEnd) {
            if (Note.isNatural(m)) whiteKeys.add(m)
        }
        if (whiteKeys.isEmpty()) whiteKeys.add(60)
        for (i in 0 until whiteKeys.size - 1) {
            if (whiteKeys[i + 1] - whiteKeys[i] == 2) {
                blackKeys.add(whiteKeys[i] + 1 to i)
            }
        }
    }

    /** 白键宽：跟随用户设置 */
    private fun whiteWidth(): Float = keyWidthDp * resources.displayMetrics.density

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = (whiteKeys.size.coerceAtLeast(1) * whiteWidth()).toInt() +
            (blackKeysOverflow().coerceAtLeast(0f)).toInt()
        val h = (keyHeightDp * resources.displayMetrics.density).toInt()
        setMeasuredDimension(w, h)
    }

    /** 最后一个黑键可能超出最末白键右缘的宽度 */
    private fun blackKeysOverflow(): Float {
        if (blackKeys.isEmpty()) return 0f
        val w = whiteWidth()
        val lastBlackRight = (blackKeys.last().second + 1) * w + w * 0.3f
        return lastBlackRight - whiteKeys.size * w
    }

    override fun onDraw(canvas: Canvas) {
        val w = whiteWidth()
        val h = height.toFloat()

        // 白键（每个键标注）
        for (i in whiteKeys.indices) {
            val midi = whiteKeys[i]
            rect.set(i * w + 2f, 0f, (i + 1) * w - 2f, h - 4f)
            canvas.drawRoundRect(rect, 10f, 10f, if (pressed.contains(midi)) whitePressedPaint else whitePaint)
            canvas.drawRoundRect(rect, 10f, 10f, borderPaint)
            val size = min(w * 0.30f, h * 0.13f)
            drawKeyLabel(canvas, midi, rect.centerX(), h - h * 0.10f, size, textPaint)
        }

        // 黑键（每个键标注）
        val bh = h * 0.60f
        val bw = w * 0.60f
        for ((midi, leftIdx) in blackKeys) {
            val cx = (leftIdx + 1) * w
            rect.set(cx - bw / 2, 0f, cx + bw / 2, bh)
            canvas.drawRoundRect(rect, 8f, 8f, if (pressed.contains(midi)) blackPressedPaint else blackPaint)
            val size = min(bw * 0.40f, h * 0.09f)
            drawKeyLabel(canvas, midi, cx, bh - 14f, size, blackTextPaint)
        }
    }

    /**
     * 键名标注：
     * - 数字简谱：数字 + 八度点（低音下方加点、高音上方加点）
     * - 音名：C4 / D4 式（带八度数）
     * - 唱名：do4 / re4 式（带八度数）
     * - 全部：简谱（带点）+ 唱名 + 音名三行叠加
     */
    private fun drawKeyLabel(
        canvas: Canvas, midi: Int, cx: Float, baseline: Float, size: Float, paint: Paint,
    ) {
        when (labelMode) {
            AppSettings.LABEL_JIANPU -> {
                paint.textSize = size
                drawJianpu(canvas, midi, cx, baseline, size, paint)
            }
            AppSettings.LABEL_NAMES -> {
                paint.textSize = size
                canvas.drawText(Note.nameOf(midi), cx, baseline, paint)
            }
            AppSettings.LABEL_ALL -> {
                val s = size * 0.58f
                paint.textSize = s
                canvas.drawText(Note.nameOf(midi), cx, baseline, paint)
                canvas.drawText(Note.solfegeWithOctave(midi), cx, baseline - s * 1.15f, paint)
                drawJianpu(canvas, midi, cx, baseline - s * 2.3f, s, paint)
            }
            else -> {
                paint.textSize = size
                canvas.drawText(Note.solfegeWithOctave(midi), cx, baseline, paint)
            }
        }
    }

    /** 简谱数字 + 八度点 */
    private fun drawJianpu(
        canvas: Canvas, midi: Int, cx: Float, baseline: Float, size: Float, paint: Paint,
    ) {
        canvas.drawText(Note.JIANPU[Note.pitchClassOf(midi)], cx, baseline, paint)
        val dots = Note.jianpuDots(midi)
        if (dots != 0) {
            val r = size * 0.085f
            val spacing = size * 0.30f
            for (k in 1..kotlin.math.abs(dots)) {
                val y = if (dots > 0) {
                    baseline - size * 1.05f - (k - 1) * spacing
                } else {
                    baseline + spacing * 0.9f + (k - 1) * spacing
                }
                canvas.drawCircle(cx, y, r, paint)
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = event.actionIndex
                val midi = hitTest(event.getX(idx), event.getY(idx)) ?: return true
                val pid = event.getPointerId(idx)
                if (pointerMap.put(pid, midi) == null) {
                    pressed.add(midi)
                    listener?.onKeyDown(midi)
                    invalidate()
                }
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    val pid = event.getPointerId(i)
                    val newMidi = hitTest(event.getX(i), event.getY(i))
                    val oldMidi = pointerMap[pid]
                    if (newMidi != null && newMidi != oldMidi) {
                        if (oldMidi != null) {
                            pressed.remove(oldMidi)
                            listener?.onKeyUp(oldMidi)
                        }
                        pointerMap[pid] = newMidi
                        pressed.add(newMidi)
                        listener?.onKeyDown(newMidi)
                        invalidate()
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val pid = event.getPointerId(event.actionIndex)
                pointerMap.remove(pid)?.let {
                    pressed.remove(it)
                    listener?.onKeyUp(it)
                    invalidate()
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                pointerMap.values.forEach {
                    pressed.remove(it)
                    listener?.onKeyUp(it)
                }
                pointerMap.clear()
                invalidate()
            }
        }
        return true
    }

    private fun hitTest(x: Float, y: Float): Int? {
        val w = whiteWidth()
        val bh = height * 0.60f
        val bw = w * 0.60f
        // 黑键优先
        if (y <= bh) {
            for ((midi, leftIdx) in blackKeys) {
                val cx = (leftIdx + 1) * w
                if (x >= cx - bw / 2 && x <= cx + bw / 2) return midi
            }
        }
        val idx = (x / w).toInt()
        return whiteKeys.getOrNull(idx)
    }
}
