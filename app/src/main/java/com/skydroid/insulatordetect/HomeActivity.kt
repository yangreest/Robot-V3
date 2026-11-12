package com.skydroid.insulatordetect

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.MutableLiveData
import com.skydroid.rcsdk.*
import com.skydroid.rcsdk.comm.CommListener
import com.skydroid.rcsdk.common.DeviceType
import com.skydroid.rcsdk.common.Uart
import com.skydroid.rcsdk.common.airlink.NetworkConfig
import com.skydroid.rcsdk.common.callback.CompletionCallbackWith
import com.skydroid.rcsdk.common.callback.KeyListener
import com.skydroid.rcsdk.common.error.SkyException
import com.skydroid.rcsdk.common.payload.*
import com.skydroid.rcsdk.common.pipeline.Pipeline
import com.skydroid.rcsdk.common.remotecontroller.ChannelSettings
import com.skydroid.rcsdk.common.remotecontroller.ControlMode
import com.skydroid.rcsdk.common.remotecontroller.H12ChannelSettings
import com.skydroid.rcsdk.key.AirLinkKey
import com.skydroid.rcsdk.key.RemoteControllerKey
import com.skydroid.rcsdk.utils.RCSDKUtils
import com.skydroid.insulatordetect.other.AppUtils
import com.skydroid.insulatordetect.other.EnumInfoKey
import com.skydroid.insulatordetect.other.ReceiveInfo
import java.util.*

/**
 * @author 咔一下
 * @date 2023/5/31 14:24
 * @email 1501020210@qq.com
 * @describe
 * <p>
 * 加入C10Pro 新旧固件 相机控制;UI重新整理; by ljb on 2024.06.13.
 */
class HomeActivity: AppCompatActivity() {

    val TAG = "HomeActivity"
    private val mReceiveInfo = ReceiveInfo()
    private val keySignalQualityListener =
        KeyListener<Int> { oldValue, newValue ->
            printInfo(EnumInfoKey.Signal, "信号强度:$newValue %")
        }

    private val keyH16ChannelsListener: KeyListener<IntArray> =
        KeyListener { oldValue, newValue ->
            printInfo(EnumInfoKey.H16Channels, Arrays.toString(newValue))
        }

    private val infoLiveData = MutableLiveData<String>()
    private var tvInfo: TextView? = null
    private var etData: EditText? = null

    private var pipeline: Pipeline? = null
    private var c10Pro: C10Pro? = null// 适用于0.2.7及以上固件 相机控制 + 全版本的云台控制
    private var c10ProCamera: C10ProCamera? = null// 适用于0.2.7以下固件 相机控制
    private var isDataHex = false

    /* ==================== 数传 / UDP 测试 ==================== */
    private var rgLink: RadioGroup? = null
    private var tvLinkState: TextView? = null
    private var tvLinkStats: TextView? = null
    private var tvLinkLog: TextView? = null
    private var svLinkLog: ScrollView? = null
    private var etLocalPort: EditText? = null
    private var etRemoteIp: EditText? = null
    private var etRemotePort: EditText? = null
    private var etLoopInterval: EditText? = null
    private var cbUdpFilter: CheckBox? = null
    private var btnLoopSend: Button? = null

    /** 追加式收发日志（SDK 回调线程与主线程均会写入，需加锁） */
    private val linkLog = StringBuilder()
    private var linkConnected = false
    private var txFrames = 0
    private var txBytes = 0L
    private var rxFrames = 0
    private var rxBytes = 0L
    private var looping = false
    private val loopHandler = Handler(Looper.getMainLooper())
    private val loopRunnable = object : Runnable {
        override fun run() {
            if (!looping) return
            sendCurrent()
            loopHandler.postDelayed(this, loopInterval())
        }
    }
    // TODO 注意:
    // TODO 使用时,请确保其他应用(包含助手、地面站)处于停止关闭状态,避免端口占用导致数据链路失败;
    // TODO 获取摇杆杆量值,无法主动上报,请求一次获取一次,推荐至少100ms读取一次;
    // TODO 数传管道,未连接 接收机 时,数传管道 连接失败;
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)
        tvInfo = findViewById(R.id.tv_info)
        etData = findViewById(R.id.et_data)
        bindLinkViews()
        infoLiveData.observe(this) { str -> tvInfo?.text = str }
        // TODO 初始化SDK,初始化一次即可;
        RCSDKManager.initSDK(this, object : SDKManagerCallBack {
            override fun onRcConnected() {
                //创建通讯管道(内部有断开重连机制，只需要调用一次连接即可)
                // 数传管道,未连接 接收机 时,数传管道 连接失败;
                // G12/G20/G30/G16 由 SDK 自动分派到 G12G20 管道（底层 UDP 承载）
                appendLog("SYS", "遥控器已连接，机型 ${deviceName()}")
                connectLink()
            }

            override fun onRcConnectFail(e: SkyException?) {}
            override fun onRcDisconnect() {}
        })
        RCSDKManager.setMainThreadCallBack(true) //设置在主线程回调
        //连接到遥控器
        RCSDKManager.connectToRC()

        //旧三体相机网口版
//        val threeBodyCamera2 = PayloadManager.getTCPPayload(PayloadType.THREE_BODY_CAMERA2, "192.168.144.108", 5001) as ThreeBodyCamera2?
        //旧三体相机串口版
//        val threeBodyCamera = PayloadManager.getSerialPortPayload(PayloadType.THREE_BODY_CAMERA, "/dev/ttyHS0", 4000000) as ThreeBodyCamera?
        //C20相机
        val c20Camera = PayloadManager.getTCPPayload(PayloadType.C20_CAMERA, "192.168.144.108", 8100) as C20Camera?
        //C20云台
//        val c20Gimbal = PayloadManager.getTCPPayload(PayloadType.C20_GIMBAL, "192.168.144.108", 5000) as C20Gimbal?

        //C10Pro相机控制（或新三体相机网口版）
        c10Pro = PayloadManager.getUDPPayload(PayloadType.C10PRO, 5000, "192.168.144.108", 5000) as C10Pro?
        //内部已经实现重连机制，无需再实现
        c10Pro?.let {
            it.setCommListener(getCommListener(1, "C10Pro"))
            PayloadManager.connectPayload(it)
        }
        c10ProCamera = PayloadManager.getUDPPayload(PayloadType.C10PRO_CAMERA, 12580, "192.168.144.108", 12580) as C10ProCamera?
        //内部已经实现重连机制，无需再实现
        c10ProCamera?.let {
            it.setCommListener(getCommListener(2, "C10pCamera"))
            PayloadManager.connectPayload(it)
        }
        initTestView()
        setTitle("RCSDK_Demo_V${RCSDKUtils.getVersion()}  Device:${RCSDKUtils.getDeviceType()}")
        val rg_data = findViewById<RadioGroup>(R.id.rg_data)
        rg_data?.setOnCheckedChangeListener { group, checkedId ->
            when(checkedId){
                R.id.rb_data_txt -> {
                    isDataHex = false
                }
                R.id.rb_data_hex -> {
                    isDataHex = true
                }
            }
        }
        rg_data?.check(R.id.rb_data_txt)
    }

    private fun getCommListener(type: Int, tag: String): CommListener {
        return object : CommListener {
            override fun onConnectSuccess() {
                log("$tag 连接成功")
                if (type == 0) {
                    linkConnected = true
                    appendLog("SYS", "$tag 连接成功")
                    updateLinkState()
                }
            }

            override fun onConnectFail(e: SkyException) {
                log("$tag  连接失败$e")
                if (type == 0) {
                    linkConnected = false
                    appendLog("ERR", "$tag 连接失败：$e")
                    updateLinkState()
                }
            }

            override fun onDisconnect() {
                log("$tag 断开连接")
                if (type == 0) {
                    linkConnected = false
                    appendLog("SYS", "$tag 断开连接")
                    updateLinkState()
                }
            }

            override fun onReadData(bytes: ByteArray) {
                if(type == 0){
                    log("$tag 收到长度${bytes.size},,, 数据 "+ String(bytes))
                    // 数传管道
                    printInfo(EnumInfoKey.DataTransmission, "数传：${if (isDataHex) String2ByteArrayUtils.bytes2Hex(bytes) else String(bytes)}")
                    onLinkData(bytes)
                }
            }
        }
    }

    private fun initTestView() {
        findViewById<View>(R.id.btn_pairing).setOnClickListener {
            KeyManager.action(RemoteControllerKey.KeyRequestPairing) { e ->
                printInfo(EnumInfoKey.Other, AppUtils.getSkyExceptionInfo("对频", e, ""));
            }
        }
        findViewById<View>(R.id.btn_set_control_mode).setOnClickListener {
            KeyManager.set(RemoteControllerKey.KeyControlMode, ControlMode.USA) { e ->
                printInfo(EnumInfoKey.SetControlMode, AppUtils.getSkyExceptionInfo("设置摇杆模式", e, ""));
            }
        }
        findViewById<View>(R.id.btn_get_control_mode).setOnClickListener { //获取遥控器手型模式
            KeyManager.get(RemoteControllerKey.KeyControlMode, object : CompletionCallbackWith<ControlMode> {
                override fun onSuccess(controlMode: ControlMode) {
                    printInfo(EnumInfoKey.GetControlMode, "获取摇杆模式：" + controlMode.name)
                }

                override fun onFailure(e: SkyException) {
                    printInfo(EnumInfoKey.GetControlMode, "获取摇杆模式失败：$e")
                }
            })
        }
        findViewById<View>(R.id.btn_get_channels).setOnClickListener {
            //获取摇杆杆量
            when (RCSDKManager.getDeviceType()) {
                DeviceType.H16 -> {
                    //防止反复监听
                    KeyManager.cancelListen(keyH16ChannelsListener)
                    //H16/H16Pro的摇杆杆量为LISTEN方式,设置监听器后，会一直回调，直到取消监听
                    KeyManager.listen(RemoteControllerKey.KeyH16Channels, keyH16ChannelsListener)
                }
                //H12/H12Pro/H30摇杆杆量为GET方式，需要主动请求，请求一次获取一次
                else ->{
                    KeyManager.get(RemoteControllerKey.KeyChannels,object : CompletionCallbackWith<IntArray> {
                        override fun onSuccess(value: IntArray?) {
                            printInfo(EnumInfoKey.Channels, "获取摇杆杆量：" + Arrays.toString(value))
                        }

                        override fun onFailure(e: SkyException) {
                            printInfo(EnumInfoKey.Channels, "获取摇杆失败：$e")
                        }
                    })
                }

            }
        }
        findViewById<View>(R.id.btn_get_channels_settings).setOnClickListener {

            when (RCSDKManager.getDeviceType()) {
                DeviceType.H12 ->
                    KeyManager.get(RemoteControllerKey.KeyH12ChannelSettings, object : CompletionCallbackWith<H12ChannelSettings> {
                        override fun onSuccess(settings: H12ChannelSettings) {
                            printInfo(EnumInfoKey.Other, "H12通道设置：${settings.channels.contentToString()}")
                        }

                        override fun onFailure(e: SkyException) {
                            printInfo(EnumInfoKey.Other, "H12通道设置：$e")
                        }
                    })
                else -> {
                    KeyManager.get(RemoteControllerKey.KeyChannelSettings,object : CompletionCallbackWith<ChannelSettings> {
                        override fun onSuccess(settings: ChannelSettings?) {
                            printInfo(EnumInfoKey.Other, "通道设置：${settings?.channels.contentToString()}")
                        }

                        override fun onFailure(e: SkyException) {
                            printInfo(EnumInfoKey.Other, "通道设置：$e")
                        }
                    })
                }
            }
        }
        // 信号强度 取值范围: 0-100%
        findViewById<View>(R.id.btn_get_signal).setOnClickListener {
            when (RCSDKManager.getDeviceType()) {
                DeviceType.H12 ->                         //H12的信号强度为GET方式，需要主动请求，请求一次获取一次
                    KeyManager.get(AirLinkKey.KeyH12SignalQuality, object : CompletionCallbackWith<Int> {
                        override fun onSuccess(integer: Int) {
                            printInfo(EnumInfoKey.Signal, "H12信号强度：$integer %")
                        }

                        override fun onFailure(e: SkyException) {
                            printInfo(EnumInfoKey.Signal, "H12信号强度获取失败：$e")
                        }
                    })
                else -> {
                    //防止反复监听
                    KeyManager.cancelListen(keySignalQualityListener)
                    //除了H12,其他遥控器的信号强度为LISTEN方式,设置监听器后，会一直回调，直到取消监听
                    KeyManager.listen(
                            AirLinkKey.KeySignalQuality,
                            keySignalQualityListener
                    )
                }
            }
        }
        findViewById<View>(R.id.btn_akey).setOnClickListener {
            c10pCameraControl(false)
        }
        findViewById<View>(R.id.btn_akey_027).setOnClickListener {
            c10pCameraControl(true)
        }
        findViewById<View>(R.id.btn_rc_buttons).setOnClickListener {
            startActivity(Intent(this,CustomRCButtonsActivity::class.java))
        }
        findViewById<View>(R.id.btn_clear).setOnClickListener {
            mReceiveInfo.cleatInfo()
            tvInfo?.text = ""
            clearLinkLog()
        }
        findViewById<View>(R.id.btn_send).setOnClickListener { sendCurrent() }
        initLinkTestView()
        findViewById<View>(R.id.btn_led).setOnClickListener {
            ledCameraControl(false)
        }
        findViewById<View>(R.id.btn_led_027).setOnClickListener {
            ledCameraControl(true)
        }
    }

    /**
     * 新网口三体相机控制LED灯
     */
    private fun ledCameraControl(isCameraVer027AndAbove: Boolean){
        AppUtils.showLEDCameraControlDialog(this){
            _, p1 ->
            when(p1){
                0 -> {
                    if (isCameraVer027AndAbove){
                        c10Pro?.setLed(true){
                            printInfo(EnumInfoKey.LED, AppUtils.getSkyExceptionInfo("LED开灯", it, "新固件"));
                        }
                    }else{
                        c10ProCamera?.setLED(true){
                            printInfo(EnumInfoKey.LED, AppUtils.getSkyExceptionInfo("LED开灯", it, "旧固件"));
                        }
                    }
                }
                1 -> {
                    if (isCameraVer027AndAbove){
                        c10Pro?.setLed(false){
                            printInfo(EnumInfoKey.LED, AppUtils.getSkyExceptionInfo("LED关灯", it, "新固件"));
                        }
                    }else{
                        c10ProCamera?.setLED(false){
                            printInfo(EnumInfoKey.LED, AppUtils.getSkyExceptionInfo("LED关灯", it, "旧固件"));
                        }
                    }
                }
            }
        }
    }

    /**
     * 云台控制_相机控制
     */
    private fun c10pCameraControl(isCameraVer027AndAbove: Boolean) {
        AppUtils.showC10pCameraControlDialog(this@HomeActivity) { _, p1 ->
            when (p1) {
                0 -> {
                    // 方法一 接口
                    c10ProCamera?.getVersion(object : CompletionCallbackWith<String> {
                        override fun onSuccess(version: String?) {
                            printInfo(EnumInfoKey.CameraVersion, "获取到相机版本号:${version}")
                        }

                        override fun onFailure(p0: SkyException?) {
                            printInfo(EnumInfoKey.CameraVersion, "获取到相机版本号:${p0}")
                        }
                    })
                    // 方法二 协议
                    //c10ProCamera?.writeData("AT+INFO\r\n".toByteArray())
                }
                1 -> {
                    c10Pro?.akey(AKey.DOWN)
                }
                2 -> {
                    c10Pro?.akey(AKey.MID)
                }
                3 -> {
                    c10Pro?.akey(AKey.TOP)
                }
                4 -> {
                    if (isCameraVer027AndAbove) {
                        // 方法一 接口
                        c10Pro?.takePicture {
                            printInfo(EnumInfoKey.TakePicture, AppUtils.getSkyExceptionInfo("拍照", it, "新固件"));
                        }
                        // 方法二 协议
                        //c10Pro?.writeData("#TPUD2wCAP013E".toByteArray())
                    } else {
                        // 方法一 接口
                        c10ProCamera?.takePicture {
                            printInfo(EnumInfoKey.TakePicture, AppUtils.getSkyExceptionInfo("拍照", it, "旧固件"));
                        }
                        // 方法二 协议
                        //c10ProCamera?.writeData("AT+AZ -p2\r\n".toByteArray())
                    }
                }
                5 -> {
                    if (isCameraVer027AndAbove) {
                        // 方法一 接口
                        c10Pro?.startRecordVideo {
                            printInfo(EnumInfoKey.RecordVideo, AppUtils.getSkyExceptionInfo("开始录像", it, "新固件"));
                        }
                        // 方法二 协议
                        // c10Pro?.writeData("#TPUD2wREC0144".toByteArray())
                    } else {
                        // 方法一 接口
                        c10ProCamera?.startRecordVideo {
                            printInfo(EnumInfoKey.RecordVideo, AppUtils.getSkyExceptionInfo("开始录像", it, "旧固件"));
                        }
                        // 方法二 协议
                        // c10ProCamera?.writeData("AT+AZ -p0\r\n".toByteArray())
                    }
                }
                6 -> {
                    if (isCameraVer027AndAbove) {
                        // 方法一 接口
                        c10Pro?.stopRecordVideo {
                            printInfo(EnumInfoKey.RecordVideo, AppUtils.getSkyExceptionInfo("停止录像", it, "新固件"));
                        }
                        // 方法二 协议
                        // c10Pro?.writeData("#TPUD2wREC0043".toByteArray())
                    } else {
                        // 方法一 接口
                        c10ProCamera?.stopRecordVideo {
                            printInfo(EnumInfoKey.RecordVideo, AppUtils.getSkyExceptionInfo("停止录像", it, "旧固件"));
                        }
                        // 方法二 协议
                        // c10ProCamera?.writeData("AT+AZ -p1\r\n".toByteArray())
                    }
                }
                7 -> {
                    if (isCameraVer027AndAbove) {
                        c10Pro?.setTime(System.currentTimeMillis()) {
                            printInfo(EnumInfoKey.CameraTime, AppUtils.getSkyExceptionInfo("时间设置", it, "新固件"));
                        }
                    } else {
                        c10ProCamera?.setTime(System.currentTimeMillis()) {
                            printInfo(EnumInfoKey.CameraTime, AppUtils.getSkyExceptionInfo("时间设置", it, "旧固件"));
                        }
                    }
                }
                8 -> {// 航向命令，右，速度30
                    // c10Pro?.controlYaw(3f)// 方法一 接口
                    // c10Pro?.writeData("#TPUG2wGSY1E75".toByteArray())// 方法二 协议

                    c10Pro?.writeData("#TPUG2wGSY6469".toByteArray())// 方法二 协议 速度 100
                }
                9 -> {// 航向命令，左，速度-30
                    // c10Pro?.controlYaw(-3f)// 方法一 接口
                    //  c10Pro?.writeData("#TPUG2wGSYE276".toByteArray())// 方法二 协议
                    c10Pro?.writeData("#TPUG2wGSY9C7B".toByteArray())// 方法二 协议 速度 100
                }
                10 -> {// 俯仰命令，上，速度30
                    // c10Pro?.controlPitch(3f)// 方法一 接口
                    // c10Pro?.writeData("#TPUG2wGSP1E6C".toByteArray())// 方法二 协议
                    c10Pro?.writeData("#TPUG2wGSP6460".toByteArray())// 方法二 协议 速度 100
                }
                11 -> {// 俯仰命令，下，速度-30
                    // c10Pro?.controlPitch(-3f)// 方法一 接口
                    // c10Pro?.writeData("#TPUG2wGSPE26D".toByteArray())// 方法二 协议
                    c10Pro?.writeData("#TPUG2wGSP9C72".toByteArray())// 方法二 协议 速度 100
                }
            }
        }

    }

    // =========================================================================
    //  数传 / UDP 测试
    // =========================================================================

    private fun bindLinkViews() {
        rgLink = findViewById(R.id.rg_link)
        tvLinkState = findViewById(R.id.tv_link_state)
        tvLinkStats = findViewById(R.id.tv_link_stats)
        tvLinkLog = findViewById(R.id.tv_link_log)
        svLinkLog = findViewById(R.id.sv_link_log)
        etLocalPort = findViewById(R.id.et_local_port)
        etRemoteIp = findViewById(R.id.et_remote_ip)
        etRemotePort = findViewById(R.id.et_remote_port)
        etLoopInterval = findViewById(R.id.et_loop_interval)
        cbUdpFilter = findViewById(R.id.cb_udp_filter)
        btnLoopSend = findViewById(R.id.btn_loop_send)
        updateChannelUi()
        updateLinkState()
        updateStats()
    }

    private fun initLinkTestView() {
        findViewById<View>(R.id.btn_link_connect).setOnClickListener { connectLink() }
        findViewById<View>(R.id.btn_link_disconnect).setOnClickListener { disconnectLink() }
        findViewById<View>(R.id.btn_link_reset_stat).setOnClickListener { resetStats() }
        btnLoopSend?.setOnClickListener { toggleLoop() }
        rgLink?.setOnCheckedChangeListener { _, _ ->
            updateChannelUi()
            // 已建管道时切换通道立即重连，便于对比 G12G20 与自定义 UDP
            if (pipeline != null) connectLink()
        }
    }

    /** UDP 参数仅在「自定义UDP」通道下可编辑 */
    private fun updateChannelUi() {
        val udp = rgLink?.checkedRadioButtonId == R.id.rb_link_udp
        etLocalPort?.isEnabled = udp
        etRemoteIp?.isEnabled = udp
        etRemotePort?.isEnabled = udp
        cbUdpFilter?.isEnabled = udp
    }

    /**
     * 按所选通道创建并连接管道。
     * SDK数传：createPipeline(UART0)，SDK 内部按机型分派（G20 → G12G20Pipeline）
     */
    private fun connectLink() {
        disconnectLink()
        val p: Pipeline? = when (rgLink?.checkedRadioButtonId) {
            R.id.rb_link_g12g20 -> PipelineManager.createG12G20Pipeline()
            R.id.rb_link_udp -> createUdpPipeline()
            else -> PipelineManager.createPipeline(Uart.UART0)
        }
        if (p == null) {
            appendLog("ERR", "管道创建失败：请确认遥控器已连接、UDP 参数填写正确")
            updateLinkState()
            return
        }
        p.onCommListener = getCommListener(0, "数传管道")
        pipeline = p
        appendLog("SYS", "创建管道 ${describe(p)}，发起连接…")
        PipelineManager.connectPipeline(p)
        updateLinkState()
    }

    /** 自定义 UDP：本地端口 / 对端 IP / 对端端口，可选关闭远端 IP 过滤 */
    private fun createUdpPipeline(): Pipeline? {
        val local = etLocalPort?.text?.toString()?.trim()?.toIntOrNull() ?: 0
        val remote = etRemotePort?.text?.toString()?.trim()?.toIntOrNull() ?: 0
        val ip = etRemoteIp?.text?.toString()?.trim().orEmpty()
        if (ip.isEmpty()) { toast("请填写对端 IP"); return null }
        if (local !in 1..65535 || remote !in 1..65535) { toast("端口范围 1~65535"); return null }
        return if (cbUdpFilter?.isChecked != false) {
            PipelineManager.createUDPPipeline(local, ip, remote)
        } else {
            // 关闭远端 IP/端口过滤：对端源地址与配置不一致时也能收到
            PipelineManager.createUDPPipeline(local, ip, remote, false, false)
        }
    }

    private fun disconnectLink() {
        stopLoop()
        val p = pipeline ?: return
        pipeline = null
        linkConnected = false
        runCatching { PipelineManager.disconnectPipeline(p) }
        appendLog("SYS", "管道已断开")
        updateLinkState()
    }

    /** 管道实际类型：G20 上应为 G12G20Pipeline[G12G20]，自定义 UDP 为 UDPPipeline[UDP] */
    private fun describe(p: Pipeline): String {
        val type = runCatching { p.getPipelineType().name }.getOrNull() ?: "?"
        return "${p.javaClass.simpleName}[$type]"
    }

    private fun deviceName(): String =
        runCatching { RCSDKUtils.getDeviceType()?.name }.getOrNull() ?: "未知"

    private fun onLinkData(bytes: ByteArray) {
        rxFrames++
        rxBytes += bytes.size
        appendLog("RX", "${bytes.size}B  ${formatData(bytes)}")
        updateStats()
    }

    /** 按当前 TXT/HEX 开关发送输入框内容 */
    private fun sendCurrent() {
        val p = pipeline
        if (p == null) { toast("请先连接数传管道"); stopLoop(); return }
        val text = etData?.text?.toString().orEmpty()
        if (text.isEmpty()) { toast("请输入要发送的内容"); stopLoop(); return }
        val bytes: ByteArray = if (isDataHex) {
            val hex = text.replace(" ", "")
            if (hex.length % 2 != 0 || !hex.all { it in "0123456789abcdefABCDEF" }) {
                toast("HEX 格式错误：需偶数个 0-9A-F 字符")
                stopLoop()
                return
            }
            String2ByteArrayUtils.hex2Bytes(hex) ?: byteArrayOf()
        } else {
            text.toByteArray()
        }
        if (bytes.isEmpty()) { toast("发送内容为空"); stopLoop(); return }
        runCatching { p.writeData(bytes) }
            .onSuccess {
                txFrames++
                txBytes += bytes.size
                appendLog("TX", "${bytes.size}B  ${formatData(bytes)}")
                updateStats()
            }
            .onFailure { appendLog("ERR", "发送失败：$it") }
    }

    private fun formatData(bytes: ByteArray): String =
        if (isDataHex) String2ByteArrayUtils.bytes2Hex(bytes).orEmpty() else String(bytes)

    private fun loopInterval(): Long =
        (etLoopInterval?.text?.toString()?.trim()?.toLongOrNull() ?: 1000L).coerceAtLeast(20L)

    private fun toggleLoop() {
        if (looping) { stopLoop(); return }
        if (pipeline == null) { toast("请先连接数传管道"); return }
        looping = true
        btnLoopSend?.text = "停止发送"
        appendLog("SYS", "开始连续发送，周期 ${loopInterval()}ms")
        loopHandler.post(loopRunnable)
    }

    private fun stopLoop() {
        if (!looping) return
        looping = false
        loopHandler.removeCallbacks(loopRunnable)
        btnLoopSend?.text = "连续发送"
        appendLog("SYS", "停止连续发送")
    }

    private fun resetStats() {
        txFrames = 0
        txBytes = 0
        rxFrames = 0
        rxBytes = 0
        updateStats()
        appendLog("SYS", "收发统计已清零")
    }

    private fun updateStats() {
        runOnUiThread {
            tvLinkStats?.text = "TX $txFrames 帧 / $txBytes B    RX $rxFrames 帧 / $rxBytes B"
        }
    }

    private fun updateLinkState() {
        val desc = pipeline?.let { describe(it) } ?: "未创建"
        val state = if (linkConnected) "已连接" else "未连接"
        runOnUiThread { tvLinkState?.text = "机型:${deviceName()}  管道:$desc  状态:$state" }
    }

    @Synchronized
    private fun appendLog(dir: String, msg: String) {
        linkLog.append(AppUtils.getTimeStamp()).append(" [").append(dir).append("] ").append(msg).append('\n')
        // 只保留最近 20000 字符，避免长时间连续发送撑爆内存
        if (linkLog.length > 20000) linkLog.delete(0, linkLog.length - 20000)
        val snapshot = linkLog.toString()
        log("[$dir] $msg")
        runOnUiThread {
            tvLinkLog?.text = snapshot
            svLinkLog?.post { svLinkLog?.fullScroll(View.FOCUS_DOWN) }
        }
    }

    @Synchronized
    private fun clearLinkLog() {
        linkLog.setLength(0)
        runOnUiThread { tvLinkLog?.text = "" }
    }

    private fun toast(msg: String) {
        runOnUiThread { Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show() }
    }

    private fun printInfo(key: EnumInfoKey, obj: Any?) {
        obj ?: return
        val sb = mReceiveInfo.updateInfo(key, obj)
        sb?.let {
            infoLiveData.postValue(sb)
        }
        log("printInfo -------key $key,,,obj $obj")
    }

    private fun log(obj: Any?) {
        if (obj == null) {
            return
        }
        Log.e(TAG, obj.toString())
    }

    override fun onDestroy() {
        super.onDestroy()
        infoLiveData.removeObservers(this)
        stopLoop()
        loopHandler.removeCallbacksAndMessages(null)
        KeyManager.cancelListen(keySignalQualityListener)
        // 只释放本页自己创建的管道；遥控器连接由 DetectMainActivity.onDestroy 统一断开，
        // 否则从调试页返回主界面会连带断掉 RobotLink 的数传链路
        val p = pipeline
        if (p != null) {
            pipeline = null
            runCatching { PipelineManager.disconnectPipeline(p) }
        }
        val localC10p = c10Pro
        if (localC10p != null) {
            PayloadManager.disconnectPayload(localC10p)
        }
        val localCamera = c10ProCamera
        if (localCamera != null) {
            PayloadManager.disconnectPayload(localCamera)
        }
    }
}