package com.skydroid.insulatordetect

import android.animation.ObjectAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.skydroid.fpvplayer.FPVWidget
import com.skydroid.fpvplayer.PlayerType
import com.skydroid.fpvplayer.RtspTransport
import com.skydroid.rcsdk.KeyManager
import com.skydroid.rcsdk.PayloadManager
import com.skydroid.rcsdk.RCSDKManager
import com.skydroid.rcsdk.SDKManagerCallBack
import com.skydroid.rcsdk.common.button.ButtonAction
import com.skydroid.rcsdk.common.button.ButtonConfig
import com.skydroid.rcsdk.common.button.ButtonHandler
import com.skydroid.rcsdk.common.button.ButtonHandlerListener
import com.skydroid.rcsdk.common.button.ButtonHelper
import com.skydroid.rcsdk.common.button.HandleButtonMode
import com.skydroid.rcsdk.common.callback.CompletionCallback
import com.skydroid.rcsdk.common.callback.CompletionCallbackWith
import com.skydroid.rcsdk.common.callback.KeyListener
import com.skydroid.rcsdk.common.error.SkyException
import com.skydroid.rcsdk.common.payload.C10Pro
import com.skydroid.rcsdk.common.payload.PayloadType
import com.skydroid.rcsdk.key.AirLinkKey
import com.skydroid.rcsdk.utils.RCSDKUtils
import com.skydroid.insulatordetect.detect.BadRecord
import com.skydroid.insulatordetect.detect.ConfigStore
import com.skydroid.insulatordetect.detect.DetectConfig
import com.skydroid.insulatordetect.detect.DetectionEngine
import com.skydroid.insulatordetect.detect.Piece
import com.skydroid.insulatordetect.detect.RobotLink
import com.skydroid.insulatordetect.rc.RCInputMonitor
import com.skydroid.insulatordetect.ui.BatteryView
import com.skydroid.insulatordetect.ui.CurveView
import com.skydroid.insulatordetect.ui.DrawerController
import com.skydroid.insulatordetect.ui.PieceAdapter
import com.skydroid.insulatordetect.ui.ReportDialogFragment
import com.skydroid.insulatordetect.util.DpUtils
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 瓷绝缘子零值检测机器人 手持终端主界面
 * 完整参照 Robot-V3/低值零值智能研判平台.html 的布局与交互逻辑
 */
class DetectMainActivity : AppCompatActivity(),
    DetectionEngine.Listener,
    RCInputMonitor.Listener,
    RobotLink.Listener,
    DrawerController.Callback,
    ReportDialogFragment.Provider {

    companion object {
        private const val TAG = "DetectMainActivity"
    }

    /* ==================== 配置与引擎 ==================== */
    private lateinit var configStore: ConfigStore
    private lateinit var config: DetectConfig
    private lateinit var engine: DetectionEngine
    private val robotLink = RobotLink()

    /* ==================== SDK ==================== */
    private var c10Pro: C10Pro? = null
    private var customButtonHelper: ButtonHelper? = null
    private val rcMonitor = RCInputMonitor()

    /* ==================== 视频 ==================== */
    private var fpvWidget: FPVWidget? = null

    /* ==================== UI ==================== */
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var adapter: PieceAdapter

    // 顶部状态栏
    private lateinit var statusBadge: LinearLayout
    private lateinit var statusDot: View
    private lateinit var tvStatus: TextView
    private lateinit var tvTopLine: TextView
    private lateinit var tvTopTower: TextView
    private lateinit var tvTopMode: TextView
    private lateinit var tvTopDone: TextView
    private lateinit var tvTopBad: TextView
    private lateinit var tvSigLine1: TextView
    private lateinit var tvSigLine2: TextView
    private lateinit var batRobot: BatteryView
    private lateinit var batAndroid: BatteryView
    private lateinit var tvBatRobot: TextView
    private lateinit var tvBatAndroid: TextView
    private lateinit var tvWatermark: TextView

    // 左侧面板
    private lateinit var tvPhase: TextView
    private lateinit var tvStrInfo: TextView
    private lateinit var tvOkBad: TextView
    private lateinit var rvPieces: RecyclerView

    // 右上信息卡
    private lateinit var tvEnv: TextView
    private lateinit var tvKvTh: TextView
    private lateinit var tvProbe: TextView

    // 曲线
    private lateinit var curveView: CurveView

    // 底部控制
    private lateinit var btnMode: View
    private lateinit var ivMode: ImageView
    private lateinit var tvMode: TextView
    private lateinit var btnStart: View
    private lateinit var ivStart: ImageView
    private lateinit var tvStart: TextView
    private lateinit var btnRec: View
    private lateinit var ivRec: ImageView
    private lateinit var tvRec: TextView
    private lateinit var btnEstop: View

    // 录制计时器
    private lateinit var recTimer: LinearLayout
    private lateinit var tvRecTime: TextView

    // 报警条 / Toast / 闪光
    private lateinit var alarmBar: LinearLayout
    private lateinit var tvAlarm: TextView
    private lateinit var customToast: TextView
    private lateinit var flashView: View

    // 手柄通道条
    private lateinit var rcStrip: LinearLayout
    private var channelChips = emptyArray<TextView>()

    // 抽屉
    private lateinit var drawerController: DrawerController

    /* ==================== 动画/状态 ==================== */
    private var dotAnimator: ObjectAnimator? = null
    private var toastHideRunnable: Runnable? = null
    private var alarmHideRunnable: Runnable? = null
    private var isRecording = false
    private var recordStartTime = 0L
    private var signalQuality = 0

    /* ==================== 定时器 ==================== */
    private val clockTicker = object : Runnable {
        override fun run() {
            val t = SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(Date())
            tvWatermark.text = "CAM1 · 1080P · $t"
            handler.postDelayed(this, 1000)
        }
    }
    private val recordTicker = object : Runnable {
        override fun run() {
            if (!isRecording) return
            val s = (System.currentTimeMillis() - recordStartTime) / 1000
            tvRecTime.text = String.format(Locale.CHINA, "%02d:%02d", s / 60, s % 60)
            handler.postDelayed(this, 1000)
        }
    }

    /* ==================== 电量广播 ==================== */
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            if (level >= 0 && scale > 0) {
                val pct = level * 100 / scale
                batAndroid.percent = pct
                tvBatAndroid.text = "$pct%"
            }
        }
    }

    /* ==================== 信号监听 ==================== */
    private val signalListener = KeyListener<Int> { _, newValue ->
        signalQuality = newValue
        runOnUiThread { updateSignalDisplay() }
    }

    private val buttonHandlerListener = object : ButtonHandlerListener {
        override fun onButtonActionResult(buttonAction: ButtonAction, success: Boolean) {
            if (buttonAction == ButtonAction.GIMBAL_YAW || buttonAction == ButtonAction.GIMBAL_PITCH) return
            runOnUiThread { showToast("按键动作 ${buttonAction.name} 执行${if (success) "成功" else "失败"}") }
        }
    }

    // =========================================================================
    //  onCreate
    // =========================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_detect_main)

        configStore = ConfigStore(this)
        config = configStore.load()
        engine = DetectionEngine(config)
        engine.listener = this

        bindViews()
        initRecyclerView()
        initChannelChips()
        initDrawer()
        initFpv()
        initSdk()
        bindControls()
        updateTopInfo()
        updateModeButton()
        updateProbeText(false)

        // 电量广播
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        // 时钟水印
        handler.post(clockTicker)
    }

    // =========================================================================
    //  初始化
    // =========================================================================

    private fun bindViews() {
        statusBadge = findViewById(R.id.statusBadge)
        statusDot = findViewById(R.id.statusDot)
        tvStatus = findViewById(R.id.tvStatus)
        tvTopLine = findViewById(R.id.tvTopLine)
        tvTopTower = findViewById(R.id.tvTopTower)
        tvTopMode = findViewById(R.id.tvTopMode)
        tvTopDone = findViewById(R.id.tvTopDone)
        tvTopBad = findViewById(R.id.tvTopBad)
        tvSigLine1 = findViewById(R.id.tvSigLine1)
        tvSigLine2 = findViewById(R.id.tvSigLine2)
        batRobot = findViewById(R.id.batRobot)
        batAndroid = findViewById(R.id.batAndroid)
        tvBatRobot = findViewById(R.id.tvBatRobot)
        tvBatAndroid = findViewById(R.id.tvBatAndroid)
        tvWatermark = findViewById(R.id.tvWatermark)
        tvPhase = findViewById(R.id.tvPhase)
        tvStrInfo = findViewById(R.id.tvStrInfo)
        tvOkBad = findViewById(R.id.tvOkBad)
        rvPieces = findViewById(R.id.rvPieces)
        tvEnv = findViewById(R.id.tvEnv)
        tvKvTh = findViewById(R.id.tvKvTh)
        tvProbe = findViewById(R.id.tvProbe)
        curveView = findViewById(R.id.curveView)
        btnMode = findViewById(R.id.btnMode)
        ivMode = findViewById(R.id.ivMode)
        tvMode = findViewById(R.id.tvMode)
        btnStart = findViewById(R.id.btnStart)
        ivStart = findViewById(R.id.ivStart)
        tvStart = findViewById(R.id.tvStart)
        btnRec = findViewById(R.id.btnRec)
        ivRec = findViewById(R.id.ivRec)
        tvRec = findViewById(R.id.tvRec)
        btnEstop = findViewById(R.id.btnEstop)
        recTimer = findViewById(R.id.recTimer)
        tvRecTime = findViewById(R.id.tvRecTime)
        alarmBar = findViewById(R.id.alarmBar)
        tvAlarm = findViewById(R.id.tvAlarm)
        customToast = findViewById(R.id.customToast)
        flashView = findViewById(R.id.flashView)
        rcStrip = findViewById(R.id.rcStrip)

        // 机器人电量（演示默认值）
        batRobot.percent = robotLink.robotBattery
        tvBatRobot.text = "${robotLink.robotBattery}%"
    }

    private fun initRecyclerView() {
        adapter = PieceAdapter(engine.pieces, config.isDual) { index -> engine.jumpTo(index) }
        rvPieces.layoutManager =
            if (config.isDual) GridLayoutManager(this, 3) else LinearLayoutManager(this)
        rvPieces.adapter = adapter
        engine.resetPieces()
    }

    /** 动态生成手柄通道 chip（需求3：按了哪个按钮，界面有显示） */
    private fun initChannelChips() {
        rcStrip.removeAllViews()
        val names = RCInputMonitor.CHANNEL_NAMES
        channelChips = Array(names.size) { i ->
            TextView(this).apply {
                text = names[i]
                textSize = 10f
                setTextColor(0xFF9FB3A8.toInt())
                setBackgroundResource(R.drawable.bg_ch_chip)
                val pad = dp(6)
                setPadding(pad, dp(3), pad, dp(3))
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.marginEnd = dp(4)
                layoutParams = lp
            }.also { rcStrip.addView(it) }
        }
    }

    private fun initDrawer() {
        val drawerView = findViewById<View>(R.id.drawer)
        drawerController = DrawerController(drawerView.parent as View, this)
        findViewById<View>(R.id.btnMenu).setOnClickListener { drawerController.open() }
    }

    /** 装配 FPVWidget（对应 HTML #videoCanvas，配置同 fpv-player-demo） */
    private fun initFpv() {
        fpvWidget = findViewById<FPVWidget>(R.id.fpvWidget).apply {
            usingMediaCodec = true
            url = config.rtspUrl()
            playerType = PlayerType.ONLY_SKY
            rtspTranstype = RtspTransport.AUTO
            try {
                rcType = RCSDKUtils.getDeviceType().value
            } catch (_: Exception) { /* SDK 未初始化时忽略 */ }
            start()
        }
    }

    /** 重建视频链路（摄像头 IP 修改后调用） */
    private fun rebuildFpv(url: String) {
        fpvWidget?.stop()
        fpvWidget?.url = url
        fpvWidget?.start()
    }

    /** SDK 初始化（参照 HomeActivity 模式） */
    private fun initSdk() {
        try {
            RCSDKManager.initSDK(this, object : SDKManagerCallBack {
                override fun onRcConnected() {
                    Log.i(TAG, "遥控器已连接")
                    robotLink.listener = this@DetectMainActivity
                    robotLink.connect()
                    rcMonitor.listener = this@DetectMainActivity
                    rcMonitor.start()
                    initButtonHelper()
                    // 信号质量监听（H12 为 GET 方式，其余为 LISTEN）
                    try {
                        KeyManager.cancelListen(signalListener)
                        KeyManager.listen(AirLinkKey.KeySignalQuality, signalListener)
                    } catch (e: Exception) {
                        Log.w(TAG, "信号监听注册失败: $e")
                    }
                    runOnUiThread {
                        drawerController.setSysInfo("已连接", RCSDKUtils.getDeviceType()?.name)
                        showToast("遥控器已连接，数传链路就绪")
                    }
                }

                override fun onRcConnectFail(e: SkyException?) {
                    Log.w(TAG, "遥控器连接失败: $e")
                    runOnUiThread { showToast("遥控器连接失败，进入模拟演示模式") }
                }

                override fun onRcDisconnect() {
                    Log.i(TAG, "遥控器断开")
                    runOnUiThread {
                        drawerController.setSysInfo("已断开", null)
                        showToast("遥控器已断开")
                    }
                }
            })
            RCSDKManager.setMainThreadCallBack(true)
            RCSDKManager.connectToRC()

            // C10Pro 相机控制（拍照/录像）
            c10Pro = (PayloadManager.getUDPPayload(
                PayloadType.C10PRO, 5000, config.camIp, 5000
            ) as? C10Pro)?.also {
                PayloadManager.connectPayload(it)
            }
        } catch (e: Exception) {
            Log.e(TAG, "SDK 初始化异常: $e")
            showToast("SDK 初始化失败，进入模拟演示模式")
        }
    }

    /** 自定义按键 ButtonHelper（CUSTOM_0=A键, CUSTOM_1=F拨杆） */
    private fun initButtonHelper() {
        val helper = ButtonHelper()
        try {
            helper.addListener(buttonHandlerListener)
            helper.start()
            val configs = mutableListOf(
                ButtonConfig(4, ButtonAction.CUSTOM_0, HandleButtonMode.CHANGE),
                ButtonConfig(7, ButtonAction.CUSTOM_1, HandleButtonMode.CHANGE)
            )
            helper.setConfig(configs, object : ButtonHandler {
                override fun onHandleButton(
                    action: ButtonAction, oldValue: Int, newValue: Int,
                    buttonValues: IntArray, callback: CompletionCallback
                ) {
                    runOnUiThread {
                        showToast("自定义按键 ${action.name}: $oldValue → $newValue")
                        callback.onResult(null)
                    }
                }
            })
            customButtonHelper = helper
        } catch (e: Exception) {
            Log.w(TAG, "ButtonHelper 初始化失败: $e")
            try {
                helper.removeListener(buttonHandlerListener)
                helper.stop()
            } catch (_: Exception) {}
        }
    }

    private fun bindControls() {
        btnMode.setOnClickListener { toggleMode() }
        btnStart.setOnClickListener { engine.onMainButton() }
        btnRec.setOnClickListener { toggleRecord() }
        btnEstop.setOnClickListener { engine.emergencyStop() }
        findViewById<View>(R.id.btnShot).setOnClickListener { takePicture() }
    }

    // =========================================================================
    //  DetectionEngine.Listener
    // =========================================================================

    override fun onPiecesReset() {
        runOnUiThread {
            adapter.setDualMode(config.isDual)
            rvPieces.layoutManager =
                if (config.isDual) GridLayoutManager(this, 3) else LinearLayoutManager(this)
            adapter.notifyDataSetChanged()
        }
    }

    override fun onPiecesChanged(indices: List<Int>) {
        runOnUiThread {
            indices.forEach { i ->
                val pos = if (config.isDual) (i / 2) * 3 + (i % 2) + 1 else i
                adapter.notifyItemChanged(pos)
            }
        }
    }

    override fun onCurrentChanged(index: Int) {
        runOnUiThread {
            if (index < 0 || index >= engine.pieces.size) return@runOnUiThread
            val pos = if (config.isDual) (index / 2) * 3 + (index % 2) + 1 else index
            rvPieces.smoothScrollToPosition(pos)
        }
    }

    override fun onStats(total: Int, done: Int, ok: Int, bad: Int) {
        runOnUiThread {
            tvTopDone.text = "已测 $done/$total"
            tvTopBad.text = "劣化 $bad"
            tvOkBad.text = "合格 $ok · 异常 $bad"
        }
    }

    override fun onStatusChanged(status: DetectionEngine.Status) {
        runOnUiThread {
            when (status) {
                DetectionEngine.Status.IDLE -> {
                    statusBadge.setBackgroundResource(R.drawable.bg_badge_idle)
                    tvStatus.text = "非检测"
                    tvStatus.setTextColor(0xFF9FB3A8.toInt())
                    stopDotBlink()
                }
                DetectionEngine.Status.RUN -> {
                    statusBadge.setBackgroundResource(R.drawable.bg_badge_run)
                    tvStatus.text = "检测中"
                    tvStatus.setTextColor(0xFF3DDC84.toInt())
                    startDotBlink(false)
                }
                DetectionEngine.Status.ALARM -> {
                    statusBadge.setBackgroundResource(R.drawable.bg_badge_alarm)
                    tvStatus.text = "异常报警"
                    tvStatus.setTextColor(0xFFFF3B30.toInt())
                    startDotBlink(true)
                }
            }
        }
    }

    override fun onAlarm(message: String) {
        runOnUiThread {
            tvAlarm.text = message
            alarmBar.translationY = -dp(90).toFloat()
            alarmBar.animate().translationY(0f).setDuration(350).start()
            alarmHideRunnable?.let { handler.removeCallbacks(it) }
            val hide = Runnable {
                alarmBar.animate().translationY(-dp(90).toFloat()).setDuration(350).start()
            }
            alarmHideRunnable = hide
            handler.postDelayed(hide, DetectionEngine.ALARM_DURATION_MS)
        }
    }

    override fun onToast(message: String) {
        runOnUiThread { showToast(message) }
    }

    override fun onPulse(kv: Int) {
        runOnUiThread { curveView.firePulse(kv) }
    }

    override fun onProbeState(extending: Boolean) {
        runOnUiThread { updateProbeText(extending) }
    }

    override fun onRunStateChanged(running: Boolean) {
        runOnUiThread {
            if (running) {
                ivStart.setImageResource(R.drawable.ic_pause)
                tvStart.text = "停止检测"
                tvStart.setTextColor(0xFFFF3B30.toInt())
            } else {
                ivStart.setImageResource(R.drawable.ic_play)
                tvStart.text = if (config.isManual) "检测当前片" else "开始检测"
                tvStart.setTextColor(0xFF3DDC84.toInt())
            }
        }
    }

    // =========================================================================
    //  RCInputMonitor.Listener（手柄按键显示）
    // =========================================================================

    override fun onChannels(channels: IntArray, changed: Set<Int>) {
        runOnUiThread {
            // 喂给 ButtonHelper 处理自定义按键
            try { customButtonHelper?.receiveButtonData(channels) } catch (_: Exception) {}
            // 更新 chip 显示值 + 高亮变化通道
            channels.forEachIndexed { idx, value ->
                if (idx >= channelChips.size) return@forEachIndexed
                val chip = channelChips[idx]
                chip.text = "${RCInputMonitor.channelName(idx)} $value"
                if (idx in changed) {
                    chip.isActivated = true
                    handler.postDelayed({ chip.isActivated = false }, 700)
                }
            }
        }
    }

    // =========================================================================
    //  RobotLink.Listener
    // =========================================================================

    override fun onLinkStateChanged(connected: Boolean) {
        runOnUiThread {
            drawerController.setSysInfo(if (connected) "已连接" else "已断开", null)
        }
    }

    override fun onRobotBattery(percent: Int) {
        runOnUiThread {
            batRobot.percent = percent
            tvBatRobot.text = "$percent%"
        }
    }

    override fun onRobotData(text: String) {
        Log.d(TAG, "机器人数据: $text")
    }

    // =========================================================================
    //  DrawerController.Callback
    // =========================================================================

    override fun currentConfig(): DetectConfig = config

    override fun onConfigSaved(cfg: DetectConfig, resetPieces: Boolean) {
        config = cfg
        configStore.save(cfg)
        engine.updateConfig(cfg)
        updateTopInfo()
        updateModeButton()
        if (resetPieces) adapter.notifyDataSetChanged()
    }

    override fun onCamSourceChanged(url: String) {
        config.camIp = url.removePrefix("rtsp://").removeSuffix(":554/stream=0")
        configStore.save(config)
        rebuildFpv(url)
    }

    override fun onRobotIpChanged(ip: String) {
        config.rbtIp = ip
        configStore.save(config)
    }

    override fun showToast(msg: String) {
        toastHideRunnable?.let { handler.removeCallbacks(it) }
        customToast.text = msg
        customToast.animate().alpha(1f).setDuration(200).start()
        val hide = Runnable { customToast.animate().alpha(0f).setDuration(300).start() }
        toastHideRunnable = hide
        handler.postDelayed(hide, 2200)
    }

    override fun openReport() {
        ReportDialogFragment().show(supportFragmentManager, ReportDialogFragment.TAG)
    }

    override fun onOtaFinished(module: String, version: String) {
        Log.i(TAG, "OTA 完成: $module → $version")
    }

    override fun onSdkDebugClick() {
        startActivity(Intent(this, HomeActivity::class.java))
    }

    // =========================================================================
    //  ReportDialogFragment.Provider
    // =========================================================================

    override fun reportSubtitle(): String =
        "${config.lineName} #${config.towerNo} · ${config.phase} · " +
                (if (config.isDual) "双联 ${config.side}/${config.oppSide}" else config.side) +
                " · ${ReportDialogFragment.formatNow()}"

    override fun reportTotal(): Int = engine.pieces.size
    override fun reportOk(): Int = engine.pieces.count { it.state == Piece.STATE_OK }
    override fun reportBadCount(): Int = engine.badList.size
    override fun reportBadList(): List<BadRecord> = engine.badList.toList()
    override fun reportConfig(): DetectConfig = config
    override fun onReportToast(msg: String) { showToast(msg) }

    // =========================================================================
    //  控制按钮动作
    // =========================================================================

    /** 模式切换（对应 HTML modeBtn：自动 ↔ 手动） */
    private fun toggleMode() {
        if (engine.running) {
            showToast("检测运行中，请先停止再切换模式")
            return
        }
        config.mode = if (config.isManual) DetectConfig.MODE_AUTO else DetectConfig.MODE_MANUAL
        configStore.save(config)
        updateModeButton()
        tvTopMode.text = "模式 ${if (config.isManual) "手动" else "自动"}"
        showToast(if (config.isManual) "已切换至手动模式：逐片点选检测" else "已切换至自动模式：1.5s/片 连续推进")
    }

    private fun updateModeButton() {
        if (config.isManual) {
            tvMode.text = "手动"
            tvMode.setTextColor(0xFFFFB340.toInt())
            btnMode.setBackgroundResource(R.drawable.bg_ctl_btn_manual)
        } else {
            tvMode.text = "自动"
            tvMode.setTextColor(0xFF9FB3A8.toInt())
            btnMode.setBackgroundResource(R.drawable.bg_ctl_btn)
        }
        // 同步开始按钮文案
        if (!engine.running) {
            tvStart.text = if (config.isManual) "检测当前片" else "开始检测"
        }
    }

    /** 录制切换（C10Pro 板载录像） */
    private fun toggleRecord() {
        val cam = c10Pro
        if (cam == null) {
            showToast("相机未连接，无法录制")
            return
        }
        if (!isRecording) {
            cam.startRecordVideo { e ->
                runOnUiThread {
                    if (e == null) {
                        isRecording = true
                        recordStartTime = System.currentTimeMillis()
                        btnRec.setBackgroundResource(R.drawable.bg_ctl_btn_rec_on)
                        tvRec.text = "停止"
                        tvRec.setTextColor(0xFFFF3B30.toInt())
                        recTimer.visibility = View.VISIBLE
                        handler.post(recordTicker)
                        showToast("开始录像（C10Pro 板载存储）")
                    } else {
                        showToast("开始录像失败: $e")
                    }
                }
            }
        } else {
            cam.stopRecordVideo { e ->
                runOnUiThread {
                    isRecording = false
                    handler.removeCallbacks(recordTicker)
                    btnRec.setBackgroundResource(R.drawable.bg_ctl_btn)
                    tvRec.text = "录制"
                    tvRec.setTextColor(0xFF9FB3A8.toInt())
                    recTimer.visibility = View.GONE
                    showToast(if (e == null) "录像已停止并保存" else "停止录像失败: $e")
                }
            }
        }
    }

    /** 拍照（C10Pro takePicture + 白闪动画） */
    private fun takePicture() {
        // 白闪动画（对应 HTML .flash）
        flashView.animate().alpha(0.85f).setDuration(80).withEndAction {
            flashView.animate().alpha(0f).setDuration(150).start()
        }.start()
        val cam = c10Pro
        if (cam == null) {
            showToast("相机未连接，拍照指令未送达（已模拟快门）")
            return
        }
        cam.takePicture { e ->
            runOnUiThread {
                val name = "IMG_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA).format(Date())}.jpg"
                showToast(if (e == null) "已拍照：$name（保存于 C10Pro 板载存储）" else "拍照失败: $e")
            }
        }
    }

    // =========================================================================
    //  UI 刷新辅助
    // =========================================================================

    private fun updateTopInfo() {
        tvTopLine.text = "线路 ${config.voltageLevel} ${config.lineName}"
        tvTopTower.text = "杆塔 #${config.towerNo}"
        tvTopMode.text = "模式 ${if (config.isManual) "手动" else "自动"}"
        tvPhase.text = config.phase
        tvStrInfo.text = if (config.isDual) {
            "${config.phase} · 双联 ${config.side}/${config.oppSide}"
        } else {
            "${config.phase} · ${config.side}"
        }
        tvEnv.text = "环境温度 ${config.temp}℃　湿度 ${config.humi}%RH　风速 ${config.wind}m/s"
        tvKvTh.text = "电压档位 ${config.kv}kV　阈值 ${config.th}MΩ"
    }

    private fun updateProbeText(extending: Boolean) {
        if (extending) {
            tvProbe.text = "探针状态 伸出·接触"
            tvProbe.setTextColor(0xFFFFB340.toInt())
        } else {
            tvProbe.text = "探针状态 复位"
            tvProbe.setTextColor(0xFF9FB3A8.toInt())
        }
    }

    private fun updateSignalDisplay() {
        // 信号质量 0-100%，延迟与 dBm 为估算值（SDK 仅提供质量百分比）
        val delay = (120 - signalQuality).coerceIn(5, 200)
        val dbm = signalQuality / 2 - 100
        tvSigLine1.text = "信号延迟 ${delay}ms　信号质量 $signalQuality%"
        tvSigLine2.text = "信号强度 ${dbm}dBm"
    }

    private fun startDotBlink(fast: Boolean) {
        dotAnimator?.cancel()
        dotAnimator = ObjectAnimator.ofFloat(statusDot, "alpha", 1f, 0.15f, 1f).apply {
            duration = if (fast) 600 else 1200
            repeatCount = ObjectAnimator.INFINITE
            start()
        }
    }

    private fun stopDotBlink() {
        dotAnimator?.cancel()
        dotAnimator = null
        statusDot.alpha = 1f
    }

    private fun dp(v: Int): Int = DpUtils.dp2px(this, v.toFloat())

    // =========================================================================
    //  返回键：先关抽屉
    // =========================================================================

    @Deprecated("Use OnBackPressedDispatcher")
    override fun onBackPressed() {
        if (drawerController.isOpen()) {
            drawerController.close()
        } else {
            super.onBackPressed()
        }
    }

    // =========================================================================
    //  生命周期
    // =========================================================================

    override fun onDestroy() {
        super.onDestroy()
        // 取消无限动画（ObjectAnimator 走 Choreographer 通道，Handler.removeCallbacks 无法清除）
        dotAnimator?.cancel()
        dotAnimator = null
        handler.removeCallbacksAndMessages(null)
        try { unregisterReceiver(batteryReceiver) } catch (_: Exception) {}

        // 引擎与数据源
        engine.destroy()

        // 抽屉
        drawerController.destroy()

        // RC 按键采集
        rcMonitor.stop()
        customButtonHelper?.let {
            it.removeListener(buttonHandlerListener)
            it.stop()
        }

        // 信号监听
        try { KeyManager.cancelListen(signalListener) } catch (_: Exception) {}

        // 数传管道
        robotLink.disconnect()

        // C10Pro
        c10Pro?.let {
            try { PayloadManager.disconnectPayload(it) } catch (_: Exception) {}
        }

        // 视频
        fpvWidget?.stop()

        // 遥控器
        try { RCSDKManager.disconnectRC() } catch (_: Exception) {}
    }
}
