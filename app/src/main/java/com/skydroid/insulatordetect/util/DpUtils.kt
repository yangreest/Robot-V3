package com.skydroid.insulatordetect.util

import android.content.Context

/**
 * dp/px 转换工具
 */
object DpUtils {

    fun dp2px(context: Context, dp: Float): Int {
        return (context.resources.displayMetrics.density * dp + 0.5f).toInt()
    }

    fun px2dp(context: Context, px: Float): Float {
        return px / context.resources.displayMetrics.density
    }
}
