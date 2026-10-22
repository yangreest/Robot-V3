package com.skydroid.insulatordetect.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.cos
import kotlin.math.exp

/**
 * 高压冲击响应曲线自绘控件（复刻 HTML drawCurve/firePulse）：
 * 深色底 + 网格，触发后绘制指数衰减振荡波形，1200ms 后自动回到基线
 */
class CurveView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE6040A08.toInt() }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x40789687.toInt()
        strokeWidth = 1f
    }
    private val curvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF3DDC84.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 2.4f * resources.displayMetrics.density
    }

    private var pulseStart = 0L      // 冲击触发时间（uptimeMillis）
    private var pulseKv = 50         // 冲击电压档位，决定波形幅度
    private var animator: ValueAnimator? = null

    /** 触发一次冲击波形（对应 firePulse） */
    fun firePulse(kv: Int) {
        pulseKv = kv
        pulseStart = SystemClock.uptimeMillis()
        startAnim()
    }

    private fun startAnim() {
        if (animator?.isRunning == true) return
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1300
            interpolator = LinearInterpolator()
            addUpdateListener { invalidate() }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        canvas.drawRect(0f, 0f, w, h, bgPaint)
        // 网格：横 5 分、纵 8 分
        for (i in 1..4) {
            val y = h * i / 5f
            canvas.drawLine(0f, y, w, y, gridPaint)
        }
        for (i in 1..7) {
            val x = w * i / 8f
            canvas.drawLine(x, 0f, x, h, gridPaint)
        }
        // 波形：3s 时间窗从右向左滚动，冲击后 900ms 内绘制衰减振荡
        if (pulseStart > 0L) {
            val now = SystemClock.uptimeMillis()
            val base = h * 0.72f
            val step = 3f * resources.displayMetrics.density
            var x = 0f
            var lastY = base
            var first = true
            while (x < w) {
                val tms = (now - (w - x) / w * 3000f).toLong()
                var y = base
                val dt = tms - pulseStart
                if (dt in 0..899) {
                    val amp = h * 0.62f * (pulseKv / 60f)
                    val env = exp(-dt / 160f).toFloat()
                    val osc = cos(dt / 22f).toFloat()
                    // 固定种子噪声，保证单帧内曲线连续
                    val noise = ((dt * 31 + x.toInt()) % 5 - 2) * 0.4f
                    y = base - amp * env * osc + noise
                }
                if (first) {
                    first = false
                } else {
                    canvas.drawLine(x - step, lastY, x, y, curvePaint)
                }
                lastY = y
                x += step
            }
            // 波形播完自动回到基线（对应 HTML 1200ms 清除 pulse）
            if (now - pulseStart >= 1200) {
                pulseStart = 0L
            }
        }
    }
}
