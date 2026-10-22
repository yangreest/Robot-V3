package com.skydroid.insulatordetect.detect

import android.os.Handler
import android.os.Looper
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * 检测数据源抽象：默认由 [SimulatedSource] 提供模拟阻值（与 HTML 演示逻辑一致）；
 * [RobotLink] 收到机器人真实检测帧后可替换为真实数据源
 */
interface DetectDataSource {
    /**
     * 对指定片发起一次测量，异步回调阻值（MΩ）
     */
    fun measure(pieceIndex: Int, config: DetectConfig, onResult: (valueMv: Int) -> Unit)
}

/**
 * 模拟数据源：8% 劣化概率，劣化片阻值 20~(th-20)，合格片阻值 (th+60)~(th+1660)
 * （移植自 HTML detectOnce）
 */
class SimulatedSource : DetectDataSource {

    private val handler = Handler(Looper.getMainLooper())

    override fun measure(pieceIndex: Int, config: DetectConfig, onResult: (valueMv: Int) -> Unit) {
        // 与 HTML 一致：800ms 后出值
        handler.postDelayed({
            val fail = Random.nextDouble() < 0.08
            val base = if (fail) {
                20 + Random.nextDouble() * (config.th - 40)
            } else {
                config.th + 60 + Random.nextDouble() * 1600
            }
            onResult(base.roundToInt())
        }, MEASURE_DELAY_MS)
    }

    fun destroy() {
        handler.removeCallbacksAndMessages(null)
    }

    companion object {
        const val MEASURE_DELAY_MS = 800L
    }
}

/**
 * 检测状态机（完整移植 HTML 检测逻辑）：
 * auto 模式 1500ms 逐片推进；manual 模式单片测量；劣化判定 value < th；
 * 双联串左右两列按行生成，逐行推进
 */
class DetectionEngine(private var config: DetectConfig) {

    /** 状态徽章（对应 HTML setStatus：idle/run/alarm） */
    enum class Status { IDLE, RUN, ALARM }

    interface Listener {
        /** 片列表重建（resetPieces/renderList） */
        fun onPiecesReset()

        /** 某些片状态变化，需要局部刷新（携带变化片下标） */
        fun onPiecesChanged(indices: List<Int>)

        /** 当前检测片变化，需要滚动定位 */
        fun onCurrentChanged(index: Int)

        /** 统计刷新：total 总片数, done 已测, ok 合格, bad 异常 */
        fun onStats(total: Int, done: Int, ok: Int, bad: Int)

        fun onStatusChanged(status: Status)

        /** 报警条（对应 HTML alarm()） */
        fun onAlarm(message: String)

        /** 提示（对应 HTML toast()） */
        fun onToast(message: String)

        /** 触发高压冲击曲线（对应 firePulse） */
        fun onPulse(kv: Int)

        /** 探针状态文本与颜色（true 表示伸出·接触琥珀色，false 表示复位绿色） */
        fun onProbeState(extending: Boolean)

        /** 运行状态变化，用于切换开始/停止按钮文案与图标 */
        fun onRunStateChanged(running: Boolean)
    }

    var listener: Listener? = null
    var dataSource: DetectDataSource = SimulatedSource()

    val pieces = mutableListOf<Piece>()
    val badList = mutableListOf<BadRecord>()

    var cur = 0
        private set
    var running = false
        private set

    private val handler = Handler(Looper.getMainLooper())
    private var autoTick: Runnable? = null
    private var manualReset: Runnable? = null
    private var alarmReset: Runnable? = null
    /** 测量代次令牌：每次 detectOnce/stopRun 递增，回调中校验以丢弃过期结果 */
    private var measureGen = 0

    /** 重建片列表（对应 HTML resetPieces） */
    fun resetPieces() {
        pieces.clear()
        if (config.isDual) {
            for (i in 1..config.count) {
                pieces.add(Piece(label = "左$i", row = i))
                pieces.add(Piece(label = "右$i", row = i))
            }
        } else {
            for (i in 1..config.count) {
                pieces.add(Piece(label = i.toString(), row = i))
            }
        }
        cur = 0
        badList.clear()
        listener?.onPiecesReset()
        publishStats()
    }

    /** 更新配置并重建（保存线路设置后调用） */
    fun updateConfig(newConfig: DetectConfig) {
        stopRun(false)
        config = newConfig
        resetPieces()
    }

    fun currentConfig(): DetectConfig = config

    /** 片显示名（对应 HTML pieceName） */
    fun pieceName(i: Int): String {
        val p = pieces[i]
        return if (config.isDual) "第 ${p.row} 片（${p.label[0]}列）" else "第 ${p.row} 片"
    }

    /** 开始检测按钮（对应 HTML startBtn.onclick） */
    fun onMainButton() {
        if (config.isManual) {
            manualOnce()
        } else {
            if (running) stopRun(false) else startRun()
        }
    }

    /** 自动/手动模式下的持续检测（对应 HTML startRun） */
    private fun startRun() {
        if (cur >= pieces.size) resetPieces()
        running = true
        listener?.onStatusChanged(Status.RUN)
        listener?.onRunStateChanged(true)
        if (!config.isManual) {
            detectOnce()
            val tick = object : Runnable {
                override fun run() {
                    detectOnce()
                    handler.postDelayed(this, AUTO_INTERVAL_MS)
                }
            }
            autoTick = tick
            handler.postDelayed(tick, AUTO_INTERVAL_MS)
        } else {
            detectOnce()
        }
    }

    /** 手动模式单片测量（对应 HTML manualOnce：单片完成后自动复位） */
    private fun manualOnce() {
        if (running) return
        if (cur >= pieces.size) resetPieces()
        running = true
        listener?.onStatusChanged(Status.RUN)
        listener?.onRunStateChanged(true)
        detectOnce()
        val reset = Runnable {
            running = false
            listener?.onStatusChanged(Status.IDLE)
            listener?.onRunStateChanged(false)
        }
        manualReset = reset
        handler.postDelayed(reset, MANUAL_RESET_MS)
    }

    /** 停止检测（对应 HTML stopRun） */
    fun stopRun(done: Boolean) {
        running = false
        measureGen++ // 使所有在途测量回调失效
        autoTick?.let { handler.removeCallbacks(it) }
        autoTick = null
        manualReset?.let { handler.removeCallbacks(it) }
        manualReset = null
        val measuring = pieces.indices.filter { pieces[it].state == Piece.STATE_MEASURING }
        measuring.forEach { pieces[it].state = Piece.STATE_IDLE }
        if (measuring.isNotEmpty()) listener?.onPiecesChanged(measuring)
        listener?.onStatusChanged(Status.IDLE)
        listener?.onRunStateChanged(false)
        listener?.onProbeState(false)
        if (done) {
            listener?.onToast("本串检测完成，可打开 ⋯ → 线路设置 → 查看报告")
        }
    }

    /** 急停（对应 HTML estopBtn.onclick） */
    fun emergencyStop() {
        stopRun(false)
        listener?.onToast("已急停：所有动作停止，探针复位")
    }

    /** 点击片项跳转选中（对应 HTML piece onclick） */
    fun jumpTo(index: Int) {
        if (index !in pieces.indices) return
        if (running && !config.isManual) return
        val prev = pieces.indices.filter { pieces[it].state == Piece.STATE_MEASURING }
        prev.forEach { pieces[it].state = Piece.STATE_IDLE }
        cur = index
        pieces[index].state = Piece.STATE_MEASURING
        val changed = (prev + index).distinct()
        listener?.onPiecesChanged(changed)
        listener?.onCurrentChanged(index)
        publishStats()
        if (config.isManual) {
            listener?.onToast("已选中 ${pieceName(index)}，点“检测当前片”执行")
        }
    }

    /** 单片检测流程（对应 HTML detectOnce） */
    private fun detectOnce() {
        if (cur >= pieces.size) {
            stopRun(true)
            return
        }
        val prev = pieces.indices.filter { pieces[it].state == Piece.STATE_MEASURING }
        prev.forEach { pieces[it].state = Piece.STATE_IDLE }
        val index = cur
        pieces[index].state = Piece.STATE_MEASURING
        listener?.onPiecesChanged((prev + index).distinct())
        listener?.onCurrentChanged(index)
        listener?.onProbeState(true)
        listener?.onPulse(config.kv)
        val gen = ++measureGen
        dataSource.measure(index, config) { value ->
            if (!running || gen != measureGen) return@measure
            val fail = value < config.th
            pieces[index].state = if (fail) Piece.STATE_BAD else Piece.STATE_OK
            pieces[index].value = value
            if (fail) {
                badList.add(BadRecord(pieceName(index), value))
                alarm("发现劣化绝缘子：${pieceName(index)}，阻值 $value MΩ（阈值 ${config.th} MΩ）")
            } else {
                listener?.onStatusChanged(Status.RUN)
            }
            listener?.onProbeState(false)
            listener?.onPiecesChanged(listOf(index))
            cur++
            listener?.onCurrentChanged(cur)
            publishStats()
        }
    }

    /** 报警条：显示 4s 后自动收起（对应 HTML alarm()） */
    private fun alarm(msg: String) {
        listener?.onAlarm(msg)
        listener?.onStatusChanged(Status.ALARM)
        alarmReset?.let { handler.removeCallbacks(it) }
        val reset = Runnable {
            if (running) listener?.onStatusChanged(Status.RUN) else listener?.onStatusChanged(Status.IDLE)
        }
        alarmReset = reset
        handler.postDelayed(reset, ALARM_DURATION_MS)
    }

    private fun publishStats() {
        val done = pieces.count { it.state == Piece.STATE_OK || it.state == Piece.STATE_BAD }
        val ok = pieces.count { it.state == Piece.STATE_OK }
        val bad = pieces.count { it.state == Piece.STATE_BAD }
        listener?.onStats(pieces.size, done, ok, bad)
    }

    /** 释放所有回调（onDestroy 调用） */
    fun destroy() {
        running = false
        handler.removeCallbacksAndMessages(null)
        (dataSource as? SimulatedSource)?.destroy()
        listener = null
    }

    companion object {
        const val AUTO_INTERVAL_MS = 1500L   // HTML: setInterval(detectOnce, 1500)
        const val MANUAL_RESET_MS = 900L     // HTML: manT 900ms 自动复位
        const val ALARM_DURATION_MS = 4000L  // HTML: alarmT 4000ms
    }
}
