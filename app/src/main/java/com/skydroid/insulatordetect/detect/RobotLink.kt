package com.skydroid.insulatordetect.detect

import android.util.Log
import com.skydroid.insulatordetect.String2ByteArrayUtils
import com.skydroid.insulatordetect.protocol.WHSDControlBoardProtocol
import com.skydroid.rcsdk.PipelineManager
import com.skydroid.rcsdk.comm.CommListener
import com.skydroid.rcsdk.common.Uart
import com.skydroid.rcsdk.common.error.SkyException
import com.skydroid.rcsdk.common.pipeline.Pipeline

/**
 * 机器人数传链路封装：基于 RCSDK 数传管道（UART0）与机器人主控通信。
 *
 * 控制模块协议帧在 [parseFrame] 中接入统一解析器与心跳应答；
 * 检测数据源当前仍由 [SimulatedSource] 驱动，机器人电量等信息使用演示默认值。
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

    @Volatile
    private var linkConnected = false

    val isConnected: Boolean
        get() = linkConnected

    /** 控制模块协议：统一组帧、粘包/半包解析与心跳应答 */
    private val protocol = WHSDControlBoardProtocol().apply {
        setSender { data -> send(data); Unit }
    }

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
        // createPipeline(UART0) 内部按机型分派：G12/G20/G30/G16 → G12G20 管道（UDP 承载），
        // H12 → 串口，H12Pro/H16/H20/H30 → UDP，因此无需在应用层区分机型
        val p = PipelineManager.createPipeline(Uart.UART0) ?: return
        p.onCommListener = object : CommListener {
            override fun onConnectSuccess() {
                Log.i(TAG, "数传管道 连接成功")
                linkConnected = true
                protocol.resetBuffer()
                listener?.onLinkStateChanged(true)
                protocol.startHeartbeat()
            }

            override fun onConnectFail(e: SkyException) {
                Log.w(TAG, "数传管道 连接失败: $e")
                linkConnected = false
                protocol.stopHeartbeat()
                listener?.onLinkStateChanged(false)
            }

            override fun onDisconnect() {
                Log.i(TAG, "数传管道 断开连接")
                linkConnected = false
                protocol.stopHeartbeat()
                protocol.resetBuffer()
                listener?.onLinkStateChanged(false)
            }

            override fun onReadData(bytes: ByteArray) {
                parseFrame(bytes)
            }
        }
        // 先保存管道再连接，避免 SDK 同步回调连接成功时心跳帧因 pipeline 仍为空而丢失
        pipeline = p
        PipelineManager.connectPipeline(p)
    }

    /** 向机器人发送指令；返回 false 表示链路不可用或 SDK 写入异常 */
    fun send(data: ByteArray): Boolean {
        val p = pipeline
        if (p == null || !linkConnected) {
            Log.w(TAG, "数传链路未连接，指令未发送")
            return false
        }
        return runCatching { p.writeData(data) }.onFailure {
            Log.e(TAG, "数传指令写入失败: $it")
        }.isSuccess
    }

    /** 协议帧解析入口：处理粘包/半包、校验、心跳及控制模块应答 */
    private fun parseFrame(bytes: ByteArray) {
        val text = runCatching { String(bytes) }.getOrDefault("")
        val hex = String2ByteArrayUtils.bytes2Hex(bytes)
        Log.d(TAG, "收到机器人数据 长度${bytes.size} HEX:$hex TEXT:$text")
        protocol.feed(bytes)
        listener?.onRobotData(text)
    }

    fun disconnect() {
        linkConnected = false
        protocol.stopHeartbeat()
        protocol.resetBuffer()
        pipeline?.let { PipelineManager.disconnectPipeline(it) }
        pipeline = null
        listener = null
    }

    companion object {
        private const val TAG = "RobotLink"
    }
}
