package com.skydroid.insulatordetect.detect

/**
 * 绝缘子片模型（对应 HTML pieces 数组元素 {s,v,label,row}）
 */
data class Piece(
    /** 0未测 / 1检测中 / 2合格 / 3异常，对应 HTML data-s */
    var state: Int = STATE_IDLE,
    /** 实测阻值 MΩ，null 表示未出值 */
    var value: Int? = null,
    /** 显示标签：单联为序号，双联为 "左n"/"右n" */
    var label: String = "",
    /** 所在行号（双联时左右两列同行） */
    var row: Int = 0
) {
    companion object {
        const val STATE_IDLE = 0
        const val STATE_MEASURING = 1
        const val STATE_OK = 2
        const val STATE_BAD = 3
    }
}

/** 异常片明细（对应 HTML badList 元素） */
data class BadRecord(val name: String, val value: Int)
