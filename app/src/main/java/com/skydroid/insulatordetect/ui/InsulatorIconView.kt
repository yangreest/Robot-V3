package com.skydroid.insulatordetect.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.skydroid.insulatordetect.detect.Piece

/**
 * 绝缘子图标自绘控件（复刻 HTML insSvg：三椭圆伞裙 + 中心竖线，按检测状态着色）
 */
class InsulatorIconView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
    }

    /** 0未测灰 / 1检测中蓝 / 2合格绿 / 3异常红 */
    var state: Int = Piece.STATE_IDLE
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    private fun colorOf(s: Int): Int = when (s) {
        Piece.STATE_MEASURING -> 0xFF4DA3FF.toInt()
        Piece.STATE_OK -> 0xFF3DDC84.toInt()
        Piece.STATE_BAD -> 0xFFFF3B30.toInt()
        else -> 0xFF8FA39A.toInt()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        paint.color = colorOf(state)
        // 以 40x40 viewBox 等比映射：椭圆 rx=10 ry=4，cy 分别 12/20/28，竖线 x=20 y4~36
        val sx = w / 40f
        val sy = h / 40f
        val cx = 20f * sx
        val rx = 10f * sx
        val ry = 4f * sy
        val half = paint.strokeWidth / 2f
        canvas.drawOval(cx - rx + half, 12f * sy - ry + half, cx + rx - half, 12f * sy + ry - half, paint)
        canvas.drawOval(cx - rx + half, 20f * sy - ry + half, cx + rx - half, 20f * sy + ry - half, paint)
        canvas.drawOval(cx - rx + half, 28f * sy - ry + half, cx + rx - half, 28f * sy + ry - half, paint)
        canvas.drawLine(cx, 4f * sy + half, cx, 36f * sy - half, paint)
    }
}
