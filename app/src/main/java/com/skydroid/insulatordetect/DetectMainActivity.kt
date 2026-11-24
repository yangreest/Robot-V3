package com.skydroid.insulatordetect

import android.Manifest
import android.animation.ObjectAnimator
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import android.provider.MediaStore
import android.util.Log
import android.view.TextureView
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.skydroid.rcsdk.KeyManager
import com.skydroid.rcsdk.RCSDKManager
import com.skydroid.rcsdk.SDKManagerCallBack
import com.skydroid.rcsdk.common.callback.KeyListener
import com.skydroid.rcsdk.common.error.SkyException
import com.skydroid.rcsdk.key.AirLinkKey
import com.skydroid.rcsdk.utils.RCSDKUtils
import com.skydroid.insulatordetect.detect.BadRecord
import com.skydroid.insulatordetect.detect.ConfigStore
import com.skydroid.insulatordetect.detect.DetectConfig
import com.skydroid.insulatordetect.detect.DetectionEngine
import com.skydroid.insulatordetect.detect.Piece
import com.skydroid.insulatordetect.detect.RobotLink
import com.skydroid.insulatordetect.protocol.WHSDControlBoardProtocol
import com.skydroid.insulatordetect.rc.RCInputMonitor
import com.skydroid.insulatordetect.ui.BatteryView
import com.skydroid.insulatordetect.ui.CurveView
import com.skydroid.insulatordetect.ui.DrawerController
import com.skydroid.insulatordetect.ui.PieceAdapter
import com.skydroid.insulatordetect.ui.ReportDialogFragment
import com.skydroid.insulatordetect.util.DpUtils
import org.easydarwin.video.EasyPlayerClient
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

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

        private const val RC_PWM_CENTER = 1500
        private const val RC_PWM_DEAD_ZONE = 120
        private const val HORIZONTAL_MOTOR_ENABLE = 0x01
        private const val SERVO_MOTOR_ENABLE = 0x01

        // 协议只规定 0~180°，以下为机械位置标定值，真机调试时按实际限位调整
        private const val SERVO_ANGLE_INNER = 90
        private const val SERVO_ANGLE_OUTER = 180
        private const val SERVO_ANGLE_RESET = 0

        private const val MEDIA_DIRECTORY = "InsulatorRobot"
        private const val ACTION_CAPTURE = 1
        private const val ACTION_RECORD = 2
    }

    /* ==================== 配置与引擎 ==================== */
    private lateinit var configStore: ConfigStore
    private lateinit var config: DetectConfig
    private lateinit var engine: DetectionEngine
    private val robotLink = RobotLink()

    /** 遥控器连接状态（由 SDK 回调维护） */
    private var rcConnected = false

    /** 进入 SDK 调试页前释放了机器人链路，返回后需重建 */
    private var linkReleasedForDebug = false

    /* ==================== SDK / RC 控制状态 ==================== */
    private val rcMonitor = RCInputMonitor()
    private var hasRcSample = false
    private var rcButtonStates = BooleanArray(RCInputMonitor.CHANNEL_NAMES.size)
    private var lastRcManualMode: Boolean? = null
    private var rcAutoWalkEnabled = false
    private var horizontalAction: Int? = null
    private var horizontalSpeed = WHSDControlBoardProtocol.SPEED_MID

    /* ==================== 视频 / 媒体保存 ==================== */
    private var rtspPlayer: EasyPlayerClient? = null
    private lateinit var rtspTexture: TextureView
    private var rtspFrameReady = false
    private var recordingFile: File? = null
    private val mediaExecutor = Executors.newSingleThreadExecutor()
    private var pendingStorageAction = 0
    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val action = pendingStorageAction
        pendingStorageAction = 0
        if (granted) {
            when (action) {
                ACTION_CAPTURE -> captureRtspFrame()
                ACTION_RECORD -> startRtspRecording()
            }
        } else {
            showToast("未授予存储权限，媒体文件未保存")
        }
    }

    /** 播放事件回调（code：1连接中 3已连接 4失败 6断流 7重连中 9超时 10退出；900分辨率 902首帧） */
    private val playerReceiver = object : ResultReceiver(Handler(Looper.getMainLooper())) {
        override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
            val code = resultData?.getInt("code") ?: resultCode
            val msg = resultData?.getString("msg") ?: ""
            when (code) {
                3 -> Log.i(TAG, "摄像头流已连接: $msg")
                4, 6, 9, 10 -> {
                    rtspFrameReady = false
                    Log.w(TAG, "摄像头流异常(code=$code): $msg")
                }
                902 -> {
                    rtspFrameReady = true
                    Log.i(TAG, "视频首帧: $msg")
                }
                EasyPlayerClient.RESULT_RECORD_END -> {
                    val endedPath = resultData?.getString("recordPath")
                    if (endedPath.isNullOrEmpty() || endedPath == recordingFile?.absolutePath) {
                        finishRecording(true)
                    }
                }
                else -> Log.d(TAG, "播放事件 code=$code: $msg")
            }
        }
    }

    /* ==================== UI ==================== */
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var adapter: PieceAdapter

    // 顶部状态栏
    private lateinit var statusBadge: LinearLayout
    private lateinit var statusDot: View
    private lateinit var tvStatus: TextView
    private lateinit var tvTopLine: TextView
    private lateinit var tvTopTower: TextView
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
        initRtsp()
        initSdk()
        bindControls()
        updateTopInfo()
        updateModeButton()
        updateProbeText(false)

        // 电量广播
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        // 时钟水印
        handler.post(clockTicker)
        applyImmersiveMode()
    }

    override fun onResume() {
        super.onResume()
        applyImmersiveMode()
        // 从 SDK 调试页返回：等调试页释放通道后重建机器人数据链路
        if (linkReleasedForDebug) {
            linkReleasedForDebug = false
            handler.postDelayed({
                if (rcConnected) {
                    robotLink.listener = this
                    robotLink.connect()
                }
            }, 500)
        }
    }

    override fun onPause() {
        // 进入后台、调试页或系统权限弹窗时先停止检测和机器人动作
        hasRcSample = false
        stopDetectionAndRobot()
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // 弹窗关闭 / 切回本界面时重新收起系统栏
        if (hasFocus) applyImmersiveMode()
    }

    /**
     * 沉浸式全屏：状态栏与导航栏默认隐藏，布局铺满 640×400dp；
     * 从屏幕边缘上滑可临时唤出 back/home/overview，松手后系统自动收回（IMMERSIVE_STICKY）。
     */
    private fun applyImmersiveMode() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
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
        applyDualLayout()
        rvPieces.adapter = adapter
        engine.resetPieces()
    }

    /**
     * 双联网格布局：100 span 细分宽度（双联面板 210dp 下 rv 内容宽 190dp，1 span = 1.9dp）。
     * 行序号列 12 span ≈ 23dp（11sp 下刚好容纳 3 位数）；
     * 左/右串片格各 42 span ≈ 80dp，列间显式留 2 span ≈ 4dp 间隔
     * （span 排布：序号 0..12 | 隔 2 | 左 14..56 | 隔 2 | 右 58..100）。
     */
    private fun newDualLayoutManager(): GridLayoutManager {
        return GridLayoutManager(this, 100).apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int =
                    if (position % 3 == 0) 12 else 42

                override fun getSpanIndex(position: Int, spanCount: Int): Int =
                    when (position % 3) {
                        0 -> 0
                        1 -> 14
                        else -> 58
                    }
            }
        }
    }

    /** 双联时面板收窄至 210dp（数据列约 80dp），单联保持 240dp */
    private fun applyDualLayout() {
        val dual = config.isDual
        rvPieces.layoutManager =
            if (dual) newDualLayoutManager() else LinearLayoutManager(this)
        val panel = findViewById<View>(R.id.stringPanel)
        panel.layoutParams = panel.layoutParams.apply { width = dp(if (dual) 200 else 150) }
    }

    /**
     * 动态生成手柄通道 chip（需求3：按了哪个按钮，界面有显示）。
     * 每个通道对应一个 TextView 胶囊，初始只显示通道名；
     * 通道值与高亮由 onChannels() 回调实时刷新（见 RCInputMonitor.Listener）。
     */
    private fun initChannelChips() {
        // 清空旧 chip，保证重复调用时不叠加
        rcStrip.removeAllViews()
        val names = RCInputMonitor.CHANNEL_NAMES
        // 按通道名数组批量创建 chip 并缓存，索引与通道号一一对应
        channelChips = Array(names.size) { i ->
            TextView(this).apply {
                text = names[i]
                textSize = 10f
                setTextColor(0xFF9FB3A8.toInt())
                // 胶囊背景（含 activated 高亮态，通道值变化时短暂点亮）
                setBackgroundResource(R.drawable.bg_ch_chip)
                // 水平 6dp / 垂直 3dp 内边距，chip 间留 4dp 间隔
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

    /** 装配 EasyPlayerClient（对应 HTML #videoCanvas，TextureView 渲染，硬解失败自动降级软解） */
    private fun initRtsp() {
        rtspTexture = findViewById(R.id.rtspTexture)
        startRtsp(config.rtspUrl())
    }

    /** 启动播放；EasyPlayerClient 内部绑定 Activity 生命周期，自动 pause/resume/stop 与断流重连 */
    private fun startRtsp(url: String) {
        if (isRecording) finishRecording(true)
        rtspFrameReady = false
        rtspPlayer?.stop()
        rtspPlayer = EasyPlayerClient(this, rtspTexture, false, playerReceiver).apply {
            play(url)
        }
    }

    /** 重建视频链路（摄像头地址修改后调用） */
    private fun rebuildRtsp(url: String) {
        startRtsp(url)
    }

    /** SDK 初始化（参照 HomeActivity 模式） */
    private fun initSdk() {
        try {
            RCSDKManager.initSDK(this, object : SDKManagerCallBack {
                override fun onRcConnected() {
                    Log.i(TAG, "遥控器已连接")
                    rcConnected = true
                    robotLink.listener = this@DetectMainActivity
                    robotLink.connect()
                    rcMonitor.listener = this@DetectMainActivity
                    rcMonitor.start()
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
                    rcConnected = false
                    rcMonitor.stop()
                    rcMonitor.listener = this@DetectMainActivity
                    robotLink.disconnect()
                    hasRcSample = false
                    rcAutoWalkEnabled = false
                    horizontalAction = null
                    runOnUiThread {
                        drawerController.setSysInfo("已断开", null)
                        showToast("遥控器已断开")
                    }
                }
            })
            RCSDKManager.setMainThreadCallBack(true)
            RCSDKManager.connectToRC()

        } catch (e: Exception) {
            Log.e(TAG, "SDK 初始化异常: $e")
            showToast("SDK 初始化失败，进入模拟演示模式")
        }
    }

    private fun bindControls() {
        btnMode.setOnClickListener { toggleMode() }
        btnStart.setOnClickListener { triggerMeasurement() }
        btnRec.setOnClickListener { toggleRecord() }
        btnEstop.setOnClickListener { emergencyStopAll() }
        findViewById<View>(R.id.btnShot).setOnClickListener { takePicture() }
    }

    // =========================================================================
    //  DetectionEngine.Listener
    // =========================================================================

    override fun onPiecesReset() {
        runOnUiThread {
            adapter.setDualMode(config.isDual)
            applyDualLayout()
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
            // 劣化数已由 tvTopBad 展示，此处只报合格数，避免同一指标重复
            tvOkBad.text = "合格 $ok"
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
                btnStart.setBackgroundResource(R.drawable.bg_ctl_pill_stop)
            } else {
                ivStart.setImageResource(R.drawable.ic_play)
                tvStart.text = if (config.isManual) "检测当前片" else "开始检测"
                tvStart.setTextColor(0xFF3DDC84.toInt())
                btnStart.setBackgroundResource(R.drawable.bg_ctl_pill_main)
            }
        }
    }

    // =========================================================================
    //  RCInputMonitor.Listener（手柄显示与机器人动作映射）
    // =========================================================================

    /**
     * 通道值变化回调（仅在有通道变化时触发，H16 LISTEN / 其余机型 100ms 轮询）。
     * @param channels 全量通道值；@param changed 本次发生变化的通道下标集合
     */
    override fun onChannels(channels: IntArray, changed: Set<Int>) {
        // 回调可能来自 SDK 采集线程，统一切到主线程处理
        runOnUiThread {
            channels.forEachIndexed { idx, value ->
                if (idx >= channelChips.size) return@forEachIndexed
                val chip = channelChips[idx]
                chip.text = "${RCInputMonitor.channelName(idx)} $value"
                if (idx in changed) {
                    chip.isActivated = true
                    handler.postDelayed({ chip.isActivated = false }, 700)
                }
            }
            handleRcControls(channels)
        }
    }

    /** 将 RC 通道值转换为动作；数字量 0/1 与 PWM 1000/1500/2000 均兼容 */
    private fun handleRcControls(channels: IntArray) {
        val pressed = BooleanArray(rcButtonStates.size) { index ->
            isRcButtonPressed(rcChannelValue(channels, index))
        }
        val wasPressed = rcButtonStates
        val firstSample = !hasRcSample
        rcButtonStates = pressed
        hasRcSample = true

        // 急停按住期间屏蔽其他动作；首次采集到急停按下时也立即执行全停
        if (pressed[RCInputMonitor.CH_ESTOP]) {
            if (firstSample || !wasPressed[RCInputMonitor.CH_ESTOP]) emergencyStopAll()
            return
        }

        // 首次连接只采集状态并下发横移停止，避免连接瞬间执行拍照、测量或舵机动作
        if (firstSample) {
            lastRcManualMode = parseRcManualMode(rcChannelValue(channels, RCInputMonitor.CH_MODE_SWITCH))
            horizontalSpeed = parseRcSpeed(rcChannelValue(channels, RCInputMonitor.CH_WHEEL))
            updateHorizontalMotor(WHSDControlBoardProtocol.MOTOR_ACTION_STOP)
            return
        }

        if (wasPressed[RCInputMonitor.CH_ESTOP]) {
            // 急停释放后不自动恢复自动行走，必须重新切换模式或推动摇杆
            horizontalAction = null
            showToast("急停已释放，等待新的动作指令")
        }

        val manualMode = parseRcManualMode(rcChannelValue(channels, RCInputMonitor.CH_MODE_SWITCH))
        if (manualMode != null && manualMode != lastRcManualMode) {
            lastRcManualMode = manualMode
            applyDetectionMode(manualMode, fromRc = true)
        }

        val speed = parseRcSpeed(rcChannelValue(channels, RCInputMonitor.CH_WHEEL))
        if (speed != horizontalSpeed) {
            horizontalSpeed = speed
            horizontalAction?.let { updateHorizontalMotor(it, force = true) }
        }

        val direction = parseHorizontalDirection(rcChannelValue(channels, RCInputMonitor.CH_LEFT_STICK_X))
        val action = when {
            direction != 0 -> if (direction > 0) {
                WHSDControlBoardProtocol.MOTOR_ACTION_FORWARD
            } else {
                WHSDControlBoardProtocol.MOTOR_ACTION_BACKWARD
            }
            config.isManual || !rcAutoWalkEnabled -> WHSDControlBoardProtocol.MOTOR_ACTION_STOP
            else -> WHSDControlBoardProtocol.MOTOR_ACTION_AUTO_WALK
        }
        updateHorizontalMotor(action)

        if (newlyPressed(RCInputMonitor.CH_L1, pressed, wasPressed)) {
            moveServo(SERVO_ANGLE_INNER, "内测")
        }
        if (newlyPressed(RCInputMonitor.CH_L2, pressed, wasPressed)) {
            moveServo(SERVO_ANGLE_OUTER, "外侧")
        }
        if (newlyPressed(RCInputMonitor.CH_R1, pressed, wasPressed)) {
            moveServo(SERVO_ANGLE_RESET, "复位")
        }
        if (newlyPressed(RCInputMonitor.CH_CAMERA, pressed, wasPressed)) takePicture()
        if (newlyPressed(RCInputMonitor.CH_MEASURE, pressed, wasPressed)) triggerMeasurement()
    }

    private fun newlyPressed(channel: Int, pressed: BooleanArray, wasPressed: BooleanArray): Boolean =
        channel in pressed.indices && pressed[channel] && !wasPressed[channel]

    private fun rcChannelValue(channels: IntArray, channel: Int): Int =
        channels.getOrElse(channel) {
            if (channel == RCInputMonitor.CH_LEFT_STICK_X ||
                channel == RCInputMonitor.CH_MODE_SWITCH ||
                channel == RCInputMonitor.CH_WHEEL
            ) RC_PWM_CENTER else 0
        }

    private fun isRcButtonPressed(value: Int): Boolean = when {
        value == 0 || value == 1 -> value == 1
        value in 900..2100 -> value >= 1800
        else -> false
    }

    /** 低档=手动，高档=自动，中位保持当前模式 */
    private fun parseRcManualMode(value: Int): Boolean? = when {
        value == 0 || value == 1 -> value == 0
        value < RC_PWM_CENTER - 200 -> true
        value > RC_PWM_CENTER + 200 -> false
        else -> null
    }

    /** 返回 -1 左移、0 回中、1 右移 */
    private fun parseHorizontalDirection(value: Int): Int = when {
        value == 0 || value == 1 -> value
        value > RC_PWM_CENTER + RC_PWM_DEAD_ZONE -> 1
        value < RC_PWM_CENTER - RC_PWM_DEAD_ZONE -> -1
        else -> 0
    }

    private fun parseRcSpeed(value: Int): Int = when {
        value == 0 -> WHSDControlBoardProtocol.SPEED_LOW
        value == 1 -> WHSDControlBoardProtocol.SPEED_HIGH
        value <= 1333 -> WHSDControlBoardProtocol.SPEED_LOW
        value >= 1667 -> WHSDControlBoardProtocol.SPEED_HIGH
        else -> WHSDControlBoardProtocol.SPEED_MID
    }

    private fun updateHorizontalMotor(action: Int, force: Boolean = false) {
        if (action != WHSDControlBoardProtocol.MOTOR_ACTION_STOP && isRcEstopHeld()) return
        if (horizontalAction == action && !force) return
        val speed = if (action == WHSDControlBoardProtocol.MOTOR_ACTION_STOP) 0 else horizontalSpeed
        val sent = robotLink.send(WHSDControlBoardProtocol.motorRun(
            WHSDControlBoardProtocol.MOTOR_GROUP_WALKING,
            HORIZONTAL_MOTOR_ENABLE,
            action,
            speed
        ))
        // 未连接或写入失败时不缓存动作，后续通道变化还会重新尝试发送
        if (sent) horizontalAction = action
    }

    private fun moveServo(angle: Int, position: String) {
        if (isRcEstopHeld()) {
            showToast("急停保持中，舵机指令已屏蔽")
            return
        }
        robotLink.send(WHSDControlBoardProtocol.motorRun(
            WHSDControlBoardProtocol.MOTOR_GROUP_SERVO,
            SERVO_MOTOR_ENABLE,
            WHSDControlBoardProtocol.MOTOR_ACTION_FORWARD,
            angle
        ))
        showToast("舵机前往$position 位置（$angle°）")
    }

    /** 测量按钮与屏幕开始按钮共用：手动单片测量，自动启停连续检测 */
    private fun triggerMeasurement() {
        if (isRcEstopHeld()) {
            showToast("急停保持中，测量指令已屏蔽")
            return
        }
        if (engine.running) {
            if (!config.isManual) {
                engine.stopRun(false)
                robotLink.send(WHSDControlBoardProtocol.sensorStopDetect(0))
                showToast("已停止检测")
            }
            return
        }
        robotLink.send(WHSDControlBoardProtocol.sensorStartDetect(0))
        engine.onMainButton()
    }

    private fun isRcEstopHeld(): Boolean =
        rcButtonStates.getOrElse(RCInputMonitor.CH_ESTOP) { false }

    /** 数传重连、退出页面和急停共用：停止检测、传感器、射线机和全部电机 */
    private fun stopDetectionAndRobot() {
        rcAutoWalkEnabled = false
        robotLink.send(WHSDControlBoardProtocol.stopXRay())
        robotLink.send(WHSDControlBoardProtocol.sensorStopDetect(0))
        val stopped = robotLink.send(WHSDControlBoardProtocol.motorRun(
            WHSDControlBoardProtocol.MOTOR_GROUP_ALL,
            0xFF,
            WHSDControlBoardProtocol.MOTOR_ACTION_STOP,
            0
        ))
        robotLink.send(WHSDControlBoardProtocol.motorStopAll())
        // 与 C++ DeviceBreak / 固件参考编码保持一致
        robotLink.send(WHSDControlBoardProtocol.motorBrakeAll())
        if (stopped) horizontalAction = WHSDControlBoardProtocol.MOTOR_ACTION_STOP
        engine.stopRun(false)
    }

    /** 急停：停止检测、传感器、射线机和全部电机，并执行全刹车 */
    private fun emergencyStopAll() {
        stopDetectionAndRobot()
        showToast(if (robotLink.isConnected) "已急停：全部动作停止" else "已停止检测，但数传未连接，设备急停指令未送达")
    }

    // =========================================================================
    //  RobotLink.Listener
    // =========================================================================

    override fun onLinkStateChanged(connected: Boolean) {
        runOnUiThread {
            if (connected) {
                // 重连后先强制全停，不恢复断链前的电机动作，等待用户重新操作
                stopDetectionAndRobot()
            } else {
                rcAutoWalkEnabled = false
                horizontalAction = null
                engine.stopRun(false)
            }
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
        config.camUrl = url
        configStore.save(config)
        rebuildRtsp(url)
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
        if (linkReleasedForDebug) return
        // 调试页会占用同一数传通道：先全停，留出短时间让 SDK 队列发出，再释放链路
        linkReleasedForDebug = true
        stopDetectionAndRobot()
        handler.postDelayed({
            robotLink.disconnect()
            startActivity(Intent(this, HomeActivity::class.java))
        }, 200)
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
        applyDetectionMode(!config.isManual, fromRc = false)
    }

    /** 手动模式停止横移；自动模式启动协议定义的自动行走动作 */
    private fun applyDetectionMode(manual: Boolean, fromRc: Boolean) {
        if (isRcEstopHeld()) {
            showToast("急停保持中，模式切换已屏蔽")
            return
        }
        if (engine.running) engine.stopRun(false)
        config.mode = if (manual) DetectConfig.MODE_MANUAL else DetectConfig.MODE_AUTO
        configStore.save(config)
        lastRcManualMode = manual
        rcAutoWalkEnabled = !manual
        updateModeButton()
        updateHorizontalMotor(
            if (manual) WHSDControlBoardProtocol.MOTOR_ACTION_STOP
            else WHSDControlBoardProtocol.MOTOR_ACTION_AUTO_WALK
        )
        val source = if (fromRc) "G-S：" else ""
        showToast(if (manual) "${source}已切换至手动模式" else "${source}已切换至自动模式并启动自动行走")
    }

    private fun updateModeButton() {
        if (config.isManual) {
            tvMode.text = "手动"
            tvMode.setTextColor(0xFFFFB340.toInt())
            btnMode.setBackgroundResource(R.drawable.bg_ctl_pill_manual)
        } else {
            tvMode.text = "自动"
            tvMode.setTextColor(0xFF9FB3A8.toInt())
            btnMode.setBackgroundResource(R.drawable.bg_ctl_pill)
        }
        // 同步开始按钮文案
        if (!engine.running) {
            tvStart.text = if (config.isManual) "检测当前片" else "开始检测"
        }
    }

    /** 录制当前 RTSP 拉流的 H264/H265 压缩帧，结束后发布到系统影片目录 */
    private fun toggleRecord() {
        if (isRecording) {
            finishRecording(true)
            return
        }
        ensureStoragePermission(ACTION_RECORD) { startRtspRecording() }
    }

    private fun startRtspRecording() {
        val player = rtspPlayer
        if (player == null || !rtspFrameReady) {
            showToast("RTSP 视频尚未就绪，无法录像")
            return
        }
        val file = createRecordingFile()
        if (file == null) {
            showToast("录像文件创建失败")
            return
        }
        val started = runCatching { player.startRecord(file.absolutePath) }.getOrDefault(false)
        if (!started) {
            file.delete()
            showToast("录像启动失败：视频编码信息尚未就绪或文件不可写")
            return
        }
        recordingFile = file
        updateRecordingUi(true)
        showToast("开始录像（RTSP 拉流数据）")
    }

    private fun createRecordingFile(): File? = runCatching {
        val directory = getExternalFilesDir(Environment.DIRECTORY_MOVIES)
            ?: File(filesDir, Environment.DIRECTORY_MOVIES)
        if (!directory.exists() && !directory.mkdirs()) return null
        val name = "VID_${SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.CHINA).format(Date())}.mp4"
        File(directory, name)
    }.getOrNull()

    private fun finishRecording(publish: Boolean) {
        val file = recordingFile ?: return
        recordingFile = null
        updateRecordingUi(false)
        rtspPlayer?.stopRecord()
        if (publish) {
            mediaExecutor.execute { publishRecording(file) }
        } else {
            file.delete()
        }
    }

    private fun updateRecordingUi(recording: Boolean) {
        isRecording = recording
        handler.removeCallbacks(recordTicker)
        if (recording) {
            recordStartTime = System.currentTimeMillis()
            btnRec.setBackgroundResource(R.drawable.bg_ctl_pill_rec_on)
            tvRec.text = "停止"
            tvRec.setTextColor(0xFFFF3B30.toInt())
            recTimer.visibility = View.VISIBLE
            handler.post(recordTicker)
        } else {
            btnRec.setBackgroundResource(R.drawable.bg_ctl_pill)
            tvRec.text = "录制"
            tvRec.setTextColor(0xFF9FB3A8.toInt())
            recTimer.visibility = View.GONE
        }
    }

    private fun publishRecording(file: File) {
        if (!file.exists() || file.length() <= 0L) {
            file.delete()
            runOnUiThread { showToast("录像文件为空，未保存") }
            return
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                publishRecordingToMediaStore(file)
            } else {
                publishRecordingToPublicDirectory(file)
            }
        }.onSuccess {
            file.delete()
            runOnUiThread { showToast("录像已保存：${file.name}") }
        }.onFailure { error ->
            Log.e(TAG, "录像发布失败: $error")
            runOnUiThread { showToast("录像发布失败，文件保留在应用目录") }
        }
    }

    private fun publishRecordingToMediaStore(file: File) {
        val resolver = contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/$MEDIA_DIRECTORY")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("MediaStore 插入失败")
        runCatching {
            val output = resolver.openOutputStream(uri)
                ?: throw IllegalStateException("MediaStore 输出流打开失败")
            output.use { out -> file.inputStream().use { input -> input.copyTo(out) } }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            if (resolver.update(uri, values, null, null) <= 0) {
                throw IllegalStateException("MediaStore 状态更新失败")
            }
        }.onFailure { error ->
            resolver.delete(uri, null, null)
            throw error
        }
    }

    private fun publishRecordingToPublicDirectory(file: File) {
        val directory = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
            MEDIA_DIRECTORY
        )
        if (!directory.exists() && !directory.mkdirs()) throw IllegalStateException("影片目录创建失败")
        val target = File(directory, file.name)
        file.copyTo(target, overwrite = false)
        MediaScannerConnection.scanFile(
            applicationContext, arrayOf(target.absolutePath), arrayOf("video/mp4"), null
        )
    }

    /** 从当前 TextureView 渲染画面取图并保存，不再向 C10Pro 发送板载拍照指令 */
    private fun takePicture() {
        ensureStoragePermission(ACTION_CAPTURE) { captureRtspFrame() }
    }

    private fun captureRtspFrame() {
        if (!rtspFrameReady || !rtspTexture.isAvailable) {
            showToast("RTSP 画面尚未就绪，无法截图")
            return
        }
        val bitmap = runCatching { rtspTexture.bitmap }.getOrNull()
        if (bitmap == null || bitmap.isRecycled) {
            showToast("当前视频帧获取失败")
            return
        }
        flashView.animate().alpha(0.85f).setDuration(80).withEndAction {
            flashView.animate().alpha(0f).setDuration(150).start()
        }.start()
        val name = "IMG_${SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.CHINA).format(Date())}.jpg"
        mediaExecutor.execute {
            val saved = runCatching { saveBitmap(bitmap, name) }.getOrDefault(false)
            bitmap.recycle()
            runOnUiThread {
                showToast(if (saved) "截图已保存：$name" else "截图保存失败")
            }
        }
    }

    private fun saveBitmap(bitmap: Bitmap, name: String): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$MEDIA_DIRECTORY")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return false
            return runCatching {
                val output = resolver.openOutputStream(uri)
                    ?: throw IllegalStateException("MediaStore 输出流打开失败")
                output.use { if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)) throw IllegalStateException("JPEG 编码失败") }
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                if (resolver.update(uri, values, null, null) <= 0) {
                    throw IllegalStateException("MediaStore 状态更新失败")
                }
            }.onFailure {
                resolver.delete(uri, null, null)
            }.isSuccess
        }

        val directory = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            MEDIA_DIRECTORY
        )
        if (!directory.exists() && !directory.mkdirs()) return false
        val target = File(directory, name)
        target.outputStream().use { if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)) return false }
        MediaScannerConnection.scanFile(
            applicationContext, arrayOf(target.absolutePath), arrayOf("image/jpeg"), null
        )
        return true
    }

    private fun ensureStoragePermission(actionCode: Int, action: () -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        ) {
            action()
            return
        }
        if (pendingStorageAction != 0) return
        pendingStorageAction = actionCode
        storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }

    // =========================================================================
    //  UI 刷新辅助
    // =========================================================================

    private fun updateTopInfo() {
        tvTopLine.text = "线路 ${config.voltageLevel} ${config.lineName}"
        tvTopTower.text = "杆塔 #${config.towerNo}"
        tvPhase.text = config.phase
        // 相别已由 tvPhase 展示，此处只描述串布置，避免重复
        tvStrInfo.text = if (config.isDual) {
            "双联 ${config.side}/${config.oppSide}"
        } else {
            config.side
        }
        // 遥测条为单行三列（每列约 128dp），文案压缩至单行可容纳
        tvEnv.text = "环境 ${config.temp}℃ · ${config.humi}%RH · 风 ${config.wind}m/s"
        tvKvTh.text = "${config.kv}kV · 阈值 ${config.th}MΩ"
    }

    private fun updateProbeText(extending: Boolean) {
        if (extending) {
            tvProbe.text = "探针 伸出·接触"
            tvProbe.setTextColor(0xFFFFB340.toInt())
        } else {
            tvProbe.text = "探针 复位"
            tvProbe.setTextColor(0xFF9FB3A8.toInt())
        }
    }

    private fun updateSignalDisplay() {
        // 信号质量 0-100%，延迟与 dBm 为估算值（SDK 仅提供质量百分比）
        val delay = (120 - signalQuality).coerceIn(5, 200)
        val dbm = signalQuality / 2 - 100
        // 顶栏右侧空间有限，压缩为两行短文案
        tvSigLine1.text = "信号 $signalQuality% · ${delay}ms"
        tvSigLine2.text = "强度 ${dbm}dBm"
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
        // 主动退出前先停止真实设备，再关闭 native muxer，最后释放播放器和数传链路
        stopDetectionAndRobot()
        finishRecording(true)
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

        // 信号监听
        try { KeyManager.cancelListen(signalListener) } catch (_: Exception) {}

        // 数传管道
        robotLink.disconnect()

        // 视频与媒体保存任务
        rtspPlayer?.stop()
        rtspPlayer = null
        mediaExecutor.shutdown()

        // 遥控器
        try { RCSDKManager.disconnectRC() } catch (_: Exception) {}
    }
}
