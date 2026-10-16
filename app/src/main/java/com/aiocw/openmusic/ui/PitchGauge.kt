package com.aiocw.openmusic.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.aiocw.openmusic.R

/**
 * 音准刻度条：显示演唱音高相对目标音的音分偏差（±50 cents）。
 */
class PitchGauge @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** NaN = 无信号 */
    var cents: Float = Float.NaN
        set(value) {
            field = value
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.text_hint)
        textAlign = Paint.Align.CENTER
    }
    private val bgColor = ContextCompat.getColor(context, R.color.gauge_bg)
    private val lineColor = ContextCompat.getColor(context, R.color.gauge_line)
    private val okColor = ContextCompat.getColor(context, R.color.ok_green)
    private val warnColor = ContextCompat.getColor(context, R.color.warn_amber)
    private val badColor = ContextCompat.getColor(context, R.color.bad_red)
    private val rect = RectF()

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        val w = width.toFloat()
        val pad = h * 0.18f

        // 背景条
        rect.set(0f, pad, w, h - pad)
        paint.color = bgColor
        canvas.drawRoundRect(rect, h * 0.25f, h * 0.25f, paint)

        val cx = w / 2f
        val half = w / 2f
        // 分区：±25 绿，25–50 黄，外侧红
        paint.color = 0x5534A853.toInt()
        rect.set(cx - half * 0.5f, pad, cx + half * 0.5f, h - pad)
        canvas.drawRoundRect(rect, h * 0.25f, h * 0.25f, paint)
        paint.color = 0x55F9AB00.toInt()
        rect.set(cx - half, pad, cx - half * 0.5f, h - pad)
        canvas.drawRect(rect, paint)
        rect.set(cx + half * 0.5f, pad, cx + half, h - pad)
        canvas.drawRect(rect, paint)

        // 中线
        paint.color = lineColor
        paint.strokeWidth = 3f
        canvas.drawLine(cx, pad, cx, h - pad, paint)

        // 指针
        if (!cents.isNaN()) {
            val clamped = cents.coerceIn(-50f, 50f)
            val x = cx + (clamped / 50f) * half
            paint.color = when {
                kotlin.math.abs(cents) <= 25f -> okColor
                kotlin.math.abs(cents) <= 50f -> warnColor
                else -> badColor
            }
            paint.strokeWidth = h * 0.22f
            paint.strokeCap = Paint.Cap.ROUND
            canvas.drawLine(x, pad * 0.6f, x, h - pad * 0.6f, paint)
        }

        // 刻度文字
        textPaint.textSize = h * 0.30f
        canvas.drawText("-50", cx - half * 0.86f, h - pad * 0.15f, textPaint)
        canvas.drawText("0", cx, h - pad * 0.15f, textPaint)
        canvas.drawText("+50", cx + half * 0.86f, h - pad * 0.15f, textPaint)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = (40 * resources.displayMetrics.density).toInt()
        setMeasuredDimension(w, h)
    }
}
