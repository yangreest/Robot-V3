package com.skydroid.insulatordetect.detect

import android.util.Log
import com.skydroid.rcsdk.PipelineManager
import com.skydroid.rcsdk.comm.CommListener
import com.skydroid.rcsdk.common.Uart
import com.skydroid.rcsdk.common.error.SkyException
import com.skydroid.rcsdk.common.pipeline.Pipeline

/**
 * 机器人数传链路封装：基于 RCSDK 数传管道（UART0）与机器人主控通信。
 *
 * 真实协议帧格式由机器人侧提供后在 [parseFrame] 中接入；
 * 当前无真机数据时，界面检测数据由 [SimulatedSource] 驱动，
 * 机器人电量等信息使用演示默认值。
 */
class RobotLink {

    interface Listener {
        fun onLinkStateChanged(connected: Boolean)

        /** 机器人电量百分比 */
        fun onRobotBattery(percent: Int)

        /** 机器人上报的原始数据（调试显示） */
        fun onRobotData(text: String)
    }

    var listener: Listener? = null

    private var pipeline: Pipeline? = null

    /** 机器人电量（真实协议接入前为演示值，检测运行时由界面缓慢扣减） */
    var robotBattery = 82
        private set

    fun updateRobotBattery(percent: Int) {
        robotBattery = percent.coerceIn(0, 100)
    }

    /**
     * 创建并连接数传管道（需在 RCSDKManager onRcConnected 后调用；
     * 未连接接收机时管道连接失败，SDK 内部有断开重连机制）
     */
    fun connect() {
        if (pipeline != null) return
        val p = PipelineManager.createPipeline(Uart.UART0) ?: return
        p.onCommListener = object : CommListener {
            override fun onConnectSuccess() {
                Log.i(TAG, "数传管道 连接成功")
                listener?.onLinkStateChanged(true)
            }

            override fun onConnectFail(e: SkyException) {
                Log.w(TAG, "数传管道 连接失败: $e")
                listener?.onLinkStateChanged(false)
            }

            override fun onDisconnect() {
                Log.i(TAG, "数传管道 断开连接")
                listener?.onLinkStateChanged(false)
            }

            override fun onReadData(bytes: ByteArray) {
                parseFrame(bytes)
            }
        }
        PipelineManager.connectPipeline(p)
        pipeline = p
    }

    /** 向机器人发送指令 */
    fun send(data: ByteArray) {
        pipeline?.writeData(data)
    }

    /**
     * 协议帧解析接入点：机器人真实协议确定后在此解析
     * 阻值/电量/探针状态等字段，并通过 [listener] 分发
     */
    private fun parseFrame(bytes: ByteArray) {
        val text = runCatching { String(bytes) }.getOrDefault("")
        Log.d(TAG, "收到机器人数据 长度${bytes.size}: $text")
        listener?.onRobotData(text)
    }

    fun disconnect() {
        pipeline?.let { PipelineManager.disconnectPipeline(it) }
        pipeline = null
        listener = null
    }

    companion object {
        private const val TAG = "RobotLink"
    }
}
