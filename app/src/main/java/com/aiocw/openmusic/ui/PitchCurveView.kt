package com.aiocw.openmusic.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.aiocw.openmusic.R
import com.aiocw.openmusic.music.Note
import kotlin.math.ceil

/**
 * 实时音高曲线图：横轴 = 时间（最近 12 秒滚动），纵轴 = 音高。
 * 纵轴按半音拉长（每个 C–B 八度分 7 条自然音线并标注音名），
 * 整体高度随音域变长，配合外层 VerticalScrollView 上下滑动。
 */
class PitchCurveView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private data class Pt(val t: Long, val midi: Double) // midi = NaN 表示断点

    private val samples = ArrayDeque<Pt>()
    private var rangeStart = 21.0
    private var rangeEnd = 108.0

    private val windowMs = 12_000L

    /** 每半音高度（dp） */
    private val pxPerSemitone = 13f * resources.displayMetrics.density

    private val bgPaint = Paint().apply {
        color = ContextCompat.getColor(context, R.color.gauge_bg)
    }
    private val gridPaint = Paint().apply {
        color = 0x3380868B
        strokeWidth = 1.5f
    }
    private val cGridPaint = Paint().apply {
        color = 0x6680868B
        strokeWidth = 2.5f
    }
    private val labelText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_hint)
        textSize = 10f * resources.displayMetrics.density
    }
    private val curvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.ok_green)
        style = Paint.Style.STROKE
        strokeWidth = 3f * resources.displayMetrics.density / 2f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.accent_text)
    }
    private val path = Path()

    fun setRange(start: Int, end: Int) {
        rangeStart = minOf(start, end).toDouble()
        rangeEnd = maxOf(start, end).toDouble()
        if (rangeEnd - rangeStart < 1) rangeEnd = rangeStart + 1
        requestLayout()
        invalidate()
    }

    fun addPoint(midi: Double) {
        samples.addLast(Pt(System.currentTimeMillis(), midi))
        prune()
        invalidate()
    }

    fun addGap() {
        samples.addLast(Pt(System.currentTimeMillis(), Double.NaN))
        prune()
        invalidate()
    }

    fun reset() {
        samples.clear()
        invalidate()
    }

    /** 某音高在本 View 内的 Y 坐标（供外层滚动定位） */
    fun yOf(midi: Double): Float {
        val frac = ((midi - rangeStart) / (rangeEnd - rangeStart)).coerceIn(0.0, 1.0)
        return (contentHeight() * (1 - frac)).toFloat()
    }

    private fun contentHeight(): Int =
        (((rangeEnd - rangeStart) * pxPerSemitone).toInt() + paddingTopBottom * 2)
            .coerceAtLeast((200 * resources.displayMetrics.density).toInt())

    private val paddingTopBottom =
        (18 * resources.displayMetrics.density).toInt()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, contentHeight())
    }

    private fun prune() {
        val cutoff = System.currentTimeMillis() - windowMs
        while (samples.isNotEmpty() && samples.first().t < cutoff) samples.removeFirst()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        // 纵轴：每个自然音一条线（每八度 do re mi fa sol la si 共 7 条），C 线加重
        var m = rangeStart.toInt()
        val first = m
        while (m <= rangeEnd.toInt()) {
            val y = yOf(m.toDouble())
            val isC = Note.pitchClassOf(m) == 0
            canvas.drawLine(0f, y, w, y, if (isC) cGridPaint else gridPaint)
            canvas.drawText(Note.nameOf(m), 6f, y - 4f, labelText)
            m++
        }
        // 顶部边界
        canvas.drawText(Note.nameOf(rangeEnd.toInt()), 6f, yOf(rangeEnd) - 4f, labelText)

        // 横轴：时间刻度（每 2 秒）
        val now = System.currentTimeMillis()
        var sec = 0
        while (sec <= windowMs / 1000) {
            val x = w * (1 - sec * 1000.0 / windowMs).toFloat()
            canvas.drawLine(x, 0f, x, h, gridPaint)
            val label = if (sec == 0) "现在" else "-${sec}s"
            canvas.drawText(label, x + 4f, labelText.textSize + 4f, labelText)
            sec += 2
        }

        // 曲线（NaN 断点分段）
        path.reset()
        var started = false
        var lastX = 0f
        var lastY = 0f
        for (pt in samples) {
            if (pt.midi.isNaN()) {
                started = false
                continue
            }
            val x = w * (1 - (now - pt.t).toDouble() / windowMs).toFloat()
            val y = yOf(pt.midi)
            if (!started) {
                path.moveTo(x, y)
                started = true
            } else {
                path.lineTo(x, y)
            }
            lastX = x
            lastY = y
        }
        canvas.drawPath(path, curvePaint)

        // 当前点
        if (samples.any { !it.midi.isNaN() }) {
            canvas.drawCircle(lastX, lastY, 5f * resources.displayMetrics.density, pointPaint)
        }
    }
}
