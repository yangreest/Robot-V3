package com.skydroid.insulatordetect.rc

import com.skydroid.rcsdk.RCSDKManager
import com.skydroid.insulatordetect.ReadRCButtonHelper

/**
 * 手柄按键/摇杆通道采集监视器：
 * 包装 [ReadRCButtonHelper]（H16 为 LISTEN 方式，其余机型 100ms 轮询 GET），
 * 将通道值转换为带名称的事件流，供界面高亮显示"按了哪个按钮"
 */
class RCInputMonitor {

    /**
     * 通道值变化事件
     * @param channels 全量通道值
     * @param changed 发生变化的通道下标集合
     */
    interface Listener {
        fun onChannels(channels: IntArray, changed: Set<Int>)
    }

    var listener: Listener? = null

    private var helper: ReadRCButtonHelper? = null
    private var lastValues: IntArray? = null

    fun start() {
        if (helper != null) return
        val h = ReadRCButtonHelper(RCSDKManager.getDeviceType())
        h.setListener { buttons -> dispatch(buttons) }
        h.start()
        helper = h
    }

    private fun dispatch(values: IntArray) {
        val last = lastValues
        val changed = if (last == null || last.size != values.size) {
            values.indices.toSet()
        } else {
            values.indices.filter { values[it] != last[it] }.toSet()
        }
        lastValues = values.copyOf()
        if (changed.isNotEmpty()) {
            listener?.onChannels(values, changed)
        }
    }

    fun stop() {
        helper?.stop()
        helper = null
        listener = null
        lastValues = null
    }

    companion object {
        /**
         * 通道名称映射（下标 = 通道号-1）：
         * CH1-CH4 摇杆，CH5-CH8 A/B/E/F 拨杆按键，CH9-CH12 C/D/G/H 滚轮按键
         * （H12Pro 按键定义参照 CustomRCButtonsActivity 注释；其他机型按通用序号显示）
         */
        val CHANNEL_NAMES = arrayOf(
            "左摇杆↔", "左摇杆↕", "右摇杆↔", "右摇杆↕",
            "A键", "B键", "E拨杆", "F拨杆",
            "C键", "D键", "G滚轮", "H滚轮",
            "CH13", "CH14", "CH15", "CH16"
        )

        fun channelName(index: Int): String =
            CHANNEL_NAMES.getOrElse(index) { "CH${index + 1}" }
    }
}
