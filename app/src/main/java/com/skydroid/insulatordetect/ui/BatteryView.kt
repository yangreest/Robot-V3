package com.skydroid.insulatordetect.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * 电池图标自绘控件（复刻 HTML .bat .shell/.fill）：
 * 白色外壳 + 顶部凸起 + 底部液位；≤30% 琥珀色，≤15% 红色
 */
class BatteryView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private val shellPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.6f * density
        color = 0xFFFFFFFF.toInt()
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }

    /** 电量百分比 0-100 */
    var percent: Int = 100
        set(value) {
            val v = value.coerceIn(0, 100)
            if (field != v) {
                field = v
                invalidate()
            }
        }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        // 外壳（HTML: 13x20，顶部 5x3 凸起）
        val capW = 5f * density
        val capH = 3f * density
        val shellTop = capH
        val shellH = h - capH
        val radius = 2.5f * density
        // 顶部凸起
        fillPaint.color = 0xFFFFFFFF.toInt()
        canvas.drawRoundRect(
            (w - capW) / 2f, 0f, (w + capW) / 2f, capH + radius,
            radius, radius, fillPaint
        )
        // 外壳描边
        shellPaint.color = 0xFFFFFFFF.toInt()
        canvas.drawRoundRect(0f, shellTop, w, h, radius, radius, shellPaint)
        // 液位
        val pad = 2f * density
        val innerH = shellH - pad * 2f
        val fillH = innerH * percent / 100f
        fillPaint.color = when {
            percent <= 15 -> 0xFFFF3B30.toInt()
            percent <= 30 -> 0xFFFFB340.toInt()
            else -> 0xFFFFFFFF.toInt()
        }
        val innerR = 1f * density
        canvas.drawRoundRect(
            pad, shellTop + pad + (innerH - fillH), w - pad, h - pad,
            innerR, innerR, fillPaint
        )
    }
}
