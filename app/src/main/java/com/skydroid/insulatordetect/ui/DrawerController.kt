package com.skydroid.insulatordetect.ui

import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import androidx.viewpager.widget.PagerAdapter
import androidx.viewpager.widget.ViewPager
import com.skydroid.insulatordetect.R
import com.skydroid.insulatordetect.detect.DetectConfig
import kotlin.random.Random

/**
 * 右侧设置抽屉控制器（对应 HTML .drawer：线路设置/参数配置/系统信息 三页 + 竖排 Tab）
 */
class DrawerController(
    private val root: View,
    private val callback: Callback
) {

    interface Callback {
        /** 抽屉页需要当前配置回填 */
        fun currentConfig(): DetectConfig

        /** 保存线路设置（宿主负责持久化 + 重建片列表） */
        fun onConfigSaved(cfg: DetectConfig, resetPieces: Boolean)

        /** 摄像头 IP 已应用（宿主重建视频链路） */
        fun onCamSourceChanged(url: String)

        /** 机器人 IP 已应用 */
        fun onRobotIpChanged(ip: String)

        fun showToast(msg: String)

        fun openReport()

        /** OTA 完成后回写系统信息页固件版本 */
        fun onOtaFinished(module: String, version: String)

        fun onSdkDebugClick()
    }

    private val drawer: View = root.findViewById(R.id.drawer)
    private val mask: View = root.findViewById(R.id.drawerMask)
    private val pager: ViewPager = root.findViewById(R.id.drawerPager)
    private val dtabLine: TextView = root.findViewById(R.id.dtabLine)
    private val dtabParam: TextView = root.findViewById(R.id.dtabParam)
    private val dtabSys: TextView = root.findViewById(R.id.dtabSys)

    private val pageLine: View
    private val pageParam: View
    private val pageSys: View

    private var opened = false
    private var suppressOppListener = false
    private val handler = Handler(Looper.getMainLooper())
    private val otaTasks = mutableListOf<Runnable>()

    // 固件版本（对应 HTML fw = {det:[2,4,2], main:[3,1,0]}）
    private val detFw = intArrayOf(2, 4, 2)
    private val mainFw = intArrayOf(3, 1, 0)
    private var otaDetBusy = false
    private var otaMainBusy = false

    init {
        val inflater = LayoutInflater.from(root.context)
        pageLine = inflater.inflate(R.layout.drawer_page_line, null)
        pageParam = inflater.inflate(R.layout.drawer_page_param, null)
        pageSys = inflater.inflate(R.layout.drawer_page_sys, null)
        pager.adapter = object : PagerAdapter() {
            private val pages = listOf(pageLine, pageParam, pageSys)
            override fun getCount() = pages.size
            override fun isViewFromObject(view: View, obj: Any) = view === obj
            override fun instantiateItem(container: ViewGroup, position: Int): Any {
                container.addView(pages[position])
                return pages[position]
            }
            override fun destroyItem(container: ViewGroup, position: Int, obj: Any) {
                container.removeView(obj as View)
            }
        }
        pager.offscreenPageLimit = 2

        // 竖排 Tab（对应 .dtab）
        val tabs = listOf(dtabLine, dtabParam, dtabSys)
        tabs.forEachIndexed { i, tab ->
            tab.setOnClickListener {
                pager.currentItem = i
            }
        }
        pager.addOnPageChangeListener(object : ViewPager.SimpleOnPageChangeListener() {
            override fun onPageSelected(position: Int) {
                tabs.forEachIndexed { i, tab ->
                    tab.isSelected = i == position
                    tab.setTextColor(if (i == position) 0xFF3DDC84.toInt() else 0xFF9FB3A8.toInt())
                }
            }
        })
        tabs[0].isSelected = true
        tabs[0].setTextColor(0xFF3DDC84.toInt())

        mask.setOnClickListener { close() }

        bindLinePage()
        bindParamPage()
        bindSysPage()
    }

    fun isOpen(): Boolean = opened

    /** 打开抽屉（对应 HTML openDrawer，先回填当前配置） */
    fun open() {
        if (opened) return
        populate(callback.currentConfig())
        opened = true
        mask.visibility = View.VISIBLE
        mask.animate().alpha(1f).setDuration(250).start()
        drawer.animate().translationX(0f).setDuration(300).start()
    }

    /** 关闭抽屉（对应 HTML closeDrawer） */
    fun close() {
        if (!opened) return
        opened = false
        mask.animate().alpha(0f).setDuration(250).withEndAction { mask.visibility = View.GONE }.start()
        drawer.animate()
            .translationX(470f * drawer.resources.displayMetrics.density)
            .setDuration(300).start()
    }

    fun destroy() {
        handler.removeCallbacksAndMessages(null)
        otaTasks.clear()
    }

    /* ==================== 线路设置页 ==================== */

    private fun bindLinePage() {
        bindSeg(pageLine.findViewById(R.id.segPosition), blue = true)
        bindSeg(pageLine.findViewById(R.id.segPhase), blue = true)
        val segSide = pageLine.findViewById<ViewGroup>(R.id.segSide)
        val segLink = pageLine.findViewById<ViewGroup>(R.id.segLink)
        bindSeg(segSide, blue = false)
        bindSeg(segLink, blue = false)

        val fOppSide = pageLine.findViewById<Spinner>(R.id.fOppSide)
        // 所在串号 ↔ 对侧串号 自动互斥（对应 HTML #fSide/#fOppSide 联动）
        for (i in 0 until segSide.childCount) {
            segSide.getChildAt(i).setOnClickListener {
                selectSegIndex(segSide, i)
                val v = (it as TextView).text.toString()
                if (fOppSide.selectedItem?.toString()?.startsWith("无") != true) {
                    val target = if (v == "左串") 0 else 1
                    suppressOppListener = true
                    fOppSide.setSelection(target)
                }
            }
        }
        fOppSide.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (suppressOppListener) {
                    suppressOppListener = false
                    return
                }
                if (position == 2) return // "无（单串布置）"
                val v = if (position == 0) "右串" else "左串"
                val cur = segSelectedText(segSide)
                if (cur == v) {
                    // 对侧与所在串相同时，切换所在串
                    val otherIdx = if (v == "左串") 1 else 0
                    selectSegIndex(segSide, otherIdx)
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // 每联片数滑条 10-200
        val fCount = pageLine.findViewById<SeekBar>(R.id.fCount)
        val fCountV = pageLine.findViewById<TextView>(R.id.fCountV)
        fCount.setOnSeekBarChangeListener(progressListener { p ->
            fCountV.text = "${p + 10} 片"
        })

        pageLine.findViewById<View>(R.id.btnSaveLine).setOnClickListener { saveLine() }
        pageLine.findViewById<View>(R.id.btnOpenReport).setOnClickListener {
            close()
            callback.openReport()
        }
    }

    private fun saveLine() {
        val cfg = callback.currentConfig().copy()
        cfg.temp = pageLine.findViewById<EditText>(R.id.fTemp).text.toString().ifEmpty { "20" }
        cfg.humi = pageLine.findViewById<EditText>(R.id.fHumi).text.toString().ifEmpty { "33" }
        cfg.wind = pageLine.findViewById<EditText>(R.id.fWind).text.toString().ifEmpty { "3.2" }
        cfg.lineName = pageLine.findViewById<EditText>(R.id.fLine).text.toString().ifEmpty { cfg.lineName }
        cfg.towerNo = pageLine.findViewById<EditText>(R.id.fTower).text.toString().ifEmpty { "20" }
        cfg.position = segSelectedText(pageLine.findViewById(R.id.segPosition)) ?: cfg.position
        cfg.phase = segSelectedText(pageLine.findViewById(R.id.segPhase)) ?: cfg.phase
        cfg.side = segSelectedText(pageLine.findViewById(R.id.segSide)) ?: cfg.side
        cfg.linkType = segSelectedText(pageLine.findViewById(R.id.segLink)) ?: cfg.linkType
        val opp = pageLine.findViewById<Spinner>(R.id.fOppSide).selectedItem?.toString() ?: "右串"
        cfg.oppSide = if (opp.startsWith("无")) "无" else opp
        cfg.count = (pageLine.findViewById<SeekBar>(R.id.fCount).progress + 10)
            .coerceIn(10, DetectConfig.MAX_COUNT)
        cfg.voltageLevel =
            pageLine.findViewById<Spinner>(R.id.fVoltage).selectedItem?.toString() ?: cfg.voltageLevel

        callback.onConfigSaved(cfg, true)
        close()
        callback.showToast(
            cfg.phase + " " + (if (cfg.isDual) "双联 ${cfg.side}/${cfg.oppSide}" else cfg.side) +
                    " · ${cfg.count} 片 / ${cfg.temp}℃ / ${cfg.humi}%RH / ${cfg.wind}m/s"
        )
    }

    /* ==================== 参数配置页 ==================== */

    private fun bindParamPage() {
        val fKv = pageParam.findViewById<SeekBar>(R.id.fKv)
        val fKvV = pageParam.findViewById<TextView>(R.id.fKvV)
        fKv.setOnSeekBarChangeListener(progressListener { p -> fKvV.text = "${p + 50}kV" })

        val fTh = pageParam.findViewById<SeekBar>(R.id.fTh)
        val fThV = pageParam.findViewById<TextView>(R.id.fThV)
        fTh.setOnSeekBarChangeListener(progressListener { p -> fThV.text = "${p * 50 + 100}MΩ" })

        bindSeg(pageParam.findViewById(R.id.segRetest), blue = false)
        bindSeg(pageParam.findViewById(R.id.segBitrate), blue = false)

        val fMotor = pageParam.findViewById<SeekBar>(R.id.fMotor)
        val fMotorV = pageParam.findViewById<TextView>(R.id.fMotorV)
        fMotor.setOnSeekBarChangeListener(progressListener { p -> fMotorV.text = "${p * 5 + 20} mm/s" })

        val fExt = pageParam.findViewById<SeekBar>(R.id.fExt)
        val fExtV = pageParam.findViewById<TextView>(R.id.fExtV)
        fExt.setOnSeekBarChangeListener(progressListener { p -> fExtV.text = "${p + 5} mm/s" })

        val fRet = pageParam.findViewById<SeekBar>(R.id.fRet)
        val fRetV = pageParam.findViewById<TextView>(R.id.fRetV)
        fRet.setOnSeekBarChangeListener(progressListener { p -> fRetV.text = "${p + 5} mm/s" })

        pageParam.findViewById<View>(R.id.btnApplyRbtIp).setOnClickListener { applyIp(robot = true) }
        pageParam.findViewById<View>(R.id.btnApplyCamIp).setOnClickListener { applyIp(robot = false) }

        pageParam.findViewById<View>(R.id.btnOtaDet).setOnClickListener { runOta(det = true) }
        pageParam.findViewById<View>(R.id.btnOtaMain).setOnClickListener { runOta(det = false) }

        pageParam.findViewById<View>(R.id.btnSaveParam).setOnClickListener { saveParam() }
    }

    private fun saveParam() {
        val cfg = callback.currentConfig().copy()
        cfg.kv = pageParam.findViewById<SeekBar>(R.id.fKv).progress + 50
        cfg.th = pageParam.findViewById<SeekBar>(R.id.fTh).progress * 50 + 100
        cfg.retest = segSelectedIndex(pageParam.findViewById(R.id.segRetest)) + 1
        cfg.bitrate = segSelectedText(pageParam.findViewById(R.id.segBitrate))?.substring(0, 1)
            ?: cfg.bitrate
        cfg.motor = pageParam.findViewById<SeekBar>(R.id.fMotor).progress * 5 + 20
        cfg.ext = pageParam.findViewById<SeekBar>(R.id.fExt).progress + 5
        cfg.ret = pageParam.findViewById<SeekBar>(R.id.fRet).progress + 5
        callback.onConfigSaved(cfg, false)
        close()
        callback.showToast("参数已下发：电机 ${cfg.motor}mm/s · 探针 伸${cfg.ext}/复${cfg.ret} mm/s")
    }

    /** IP 应用（对应 HTML applyIp：格式校验 + 重连提示） */
    private fun applyIp(robot: Boolean) {
        val input = pageParam.findViewById<EditText>(if (robot) R.id.fRbtIp else R.id.fCamIp)
        val btn = pageParam.findViewById<TextView>(if (robot) R.id.btnApplyRbtIp else R.id.btnApplyCamIp)
        val label = if (robot) "机器人" else "摄像头"
        val v = input.text.toString().trim()
        val ok = Regex("^(\\d{1,3}\\.){3}\\d{1,3}$").matches(v) &&
                v.split(".").all { (it.toIntOrNull() ?: 256) <= 255 }
        if (!ok) {
            callback.showToast("$label IP 格式不正确，请检查")
            return
        }
        btn.text = "重连中…"
        callback.showToast("$label IP 已修改为 $v，链路重连中…")
        val task = Runnable {
            btn.text = "已应用"
            if (robot) callback.onRobotIpChanged(v) else callback.onCamSourceChanged("rtsp://$v:554/stream=0")
            callback.showToast("$label（$v）链路重连成功")
        }
        otaTasks.add(task)
        handler.postDelayed(task, 1400)
    }

    /** OTA 升级模拟（对应 HTML runOta：下载进度 → 校验写入 → 版本号 +1） */
    private fun runOta(det: Boolean) {
        if (det && otaDetBusy) return
        if (!det && otaMainBusy) return
        if (det) otaDetBusy = true else otaMainBusy = true
        val name = if (det) "检测模块" else "主控模块"
        val btn = pageParam.findViewById<TextView>(if (det) R.id.btnOtaDet else R.id.btnOtaMain)
        val prog = pageParam.findViewById<ProgressBar>(if (det) R.id.progDet else R.id.progMain)
        btn.text = "正在下载固件包…"
        prog.visibility = View.VISIBLE
        prog.progress = 0
        var pct = 0f
        val tick = object : Runnable {
            override fun run() {
                pct += (Random.nextDouble() * 14 + 6).toFloat()
                if (pct >= 100f) {
                    prog.progress = 100
                    btn.text = "校验并写入中…"
                    val finish = Runnable {
                        val fw = if (det) detFw else mainFw
                        fw[2] += 1
                        val v = "V${fw.joinToString(".")}"
                        btn.text = "$name 升级（OTA）$v"
                        prog.visibility = View.GONE
                        prog.progress = 0
                        if (det) otaDetBusy = false else otaMainBusy = false
                        val sysFw = pageSys.findViewById<TextView>(if (det) R.id.sysDetFw else R.id.sysMainFw)
                        sysFw.text = if (det) v else "$v（${ReportDialogFragment.formatNow().substringBefore(' ')}）"
                        callback.onOtaFinished(name, v)
                        callback.showToast("$name 固件已升级至 $v，设备将自动重启生效")
                    }
                    otaTasks.add(finish)
                    handler.postDelayed(finish, 900)
                    return
                }
                prog.progress = pct.toInt()
                btn.text = "正在下载固件包… ${pct.toInt()}%"
                handler.postDelayed(this, 260)
            }
        }
        otaTasks.add(tick)
        handler.postDelayed(tick, 260)
    }

    /* ==================== 系统信息页 ==================== */

    private fun bindSysPage() {
        pageSys.findViewById<View>(R.id.btnCheckUpdate).setOnClickListener {
            callback.showToast("已是最新版本 V${mainFw.joinToString(".")}")
        }
        pageSys.findViewById<View>(R.id.btnSdkDebug).setOnClickListener {
            callback.onSdkDebugClick()
        }
    }

    /** 抽屉打开时回填当前配置（对应 HTML 各 input 初始 value） */
    private fun populate(cfg: DetectConfig) {
        // 线路设置页
        pageLine.findViewById<EditText>(R.id.fTemp).setText(cfg.temp)
        pageLine.findViewById<EditText>(R.id.fHumi).setText(cfg.humi)
        pageLine.findViewById<EditText>(R.id.fWind).setText(cfg.wind)
        pageLine.findViewById<EditText>(R.id.fLine).setText(cfg.lineName)
        pageLine.findViewById<EditText>(R.id.fTower).setText(cfg.towerNo)
        selectSegByText(pageLine.findViewById(R.id.segPosition), cfg.position)
        selectSegByText(pageLine.findViewById(R.id.segPhase), cfg.phase)
        selectSegByText(pageLine.findViewById(R.id.segSide), cfg.side)
        selectSegByText(pageLine.findViewById(R.id.segLink), cfg.linkType)
        val oppSpinner = pageLine.findViewById<Spinner>(R.id.fOppSide)
        suppressOppListener = true
        oppSpinner.setSelection(if (cfg.oppSide == "右串") 0 else if (cfg.oppSide == "左串") 1 else 2)
        val voltageAdapter = ArrayAdapter.createFromResource(
            root.context, R.array.voltage_options, android.R.layout.simple_spinner_item)
        voltageAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        val voltageSpinner = pageLine.findViewById<Spinner>(R.id.fVoltage)
        voltageSpinner.adapter = voltageAdapter
        val vIdx = voltageOptionsIndex(cfg.voltageLevel)
        voltageSpinner.setSelection(vIdx)
        val fCount = pageLine.findViewById<SeekBar>(R.id.fCount)
        fCount.progress = (cfg.count - 10).coerceIn(0, 190)
        pageLine.findViewById<TextView>(R.id.fCountV).text = "${cfg.count} 片"

        // 参数配置页
        val fKv = pageParam.findViewById<SeekBar>(R.id.fKv)
        fKv.progress = (cfg.kv - 50).coerceIn(0, 10)
        pageParam.findViewById<TextView>(R.id.fKvV).text = "${cfg.kv}kV"
        val fTh = pageParam.findViewById<SeekBar>(R.id.fTh)
        fTh.progress = ((cfg.th - 100) / 50).coerceIn(0, 18)
        pageParam.findViewById<TextView>(R.id.fThV).text = "${cfg.th}MΩ"
        selectSegIndex(pageParam.findViewById(R.id.segRetest), (cfg.retest - 1).coerceIn(0, 2))
        selectSegByText(pageParam.findViewById(R.id.segBitrate), when (cfg.bitrate) {
            "低" -> "低（保链路）"
            "高" -> "高（画质优先）"
            else -> "中"
        })
        val fMotor = pageParam.findViewById<SeekBar>(R.id.fMotor)
        fMotor.progress = ((cfg.motor - 20) / 5).coerceIn(0, 36)
        pageParam.findViewById<TextView>(R.id.fMotorV).text = "${cfg.motor} mm/s"
        val fExt = pageParam.findViewById<SeekBar>(R.id.fExt)
        fExt.progress = (cfg.ext - 5).coerceIn(0, 75)
        pageParam.findViewById<TextView>(R.id.fExtV).text = "${cfg.ext} mm/s"
        val fRet = pageParam.findViewById<SeekBar>(R.id.fRet)
        fRet.progress = (cfg.ret - 5).coerceIn(0, 95)
        pageParam.findViewById<TextView>(R.id.fRetV).text = "${cfg.ret} mm/s"
        pageParam.findViewById<EditText>(R.id.fRbtIp).setText(cfg.rbtIp)
        pageParam.findViewById<EditText>(R.id.fCamIp).setText(cfg.camIp)

        // 系统信息页
        pageSys.findViewById<TextView>(R.id.sysRbtIp).text = cfg.rbtIp
        pageSys.findViewById<TextView>(R.id.sysCamIp).text = cfg.camIp
        pageSys.findViewById<TextView>(R.id.sysMotion).text = "${cfg.motor}mm/s"
        pageSys.findViewById<TextView>(R.id.sysMainFw).text = "V${mainFw.joinToString(".")}（2026-09-10）"
        pageSys.findViewById<TextView>(R.id.sysDetFw).text = "V${detFw.joinToString(".")}"
    }

    private fun voltageOptionsIndex(level: String): Int {
        val options = root.context.resources.getStringArray(R.array.voltage_options)
        val idx = options.indexOf(level)
        return if (idx >= 0) idx else 1
    }

    /* ==================== seg 分组单选辅助 ==================== */

    /**
     * 绑定 seg 单选组（对应 HTML .seg .opt 点击互斥）
     * @param blue true 使用蓝色选中样式，false 使用绿色
     */
    private fun bindSeg(seg: ViewGroup, blue: Boolean) {
        for (i in 0 until seg.childCount) {
            val child = seg.getChildAt(i)
            child.setBackgroundResource(
                if (blue) R.drawable.bg_seg_opt_blue else R.drawable.bg_seg_opt_green
            )
            child.setOnClickListener {
                selectSegIndex(seg, i)
            }
        }
        selectSegIndex(seg, 0)
    }

    private fun selectSegIndex(seg: ViewGroup, index: Int) {
        for (i in 0 until seg.childCount) {
            val child = seg.getChildAt(i)
            child.isSelected = i == index
            (child as? TextView)?.setTextColor(if (i == index) 0xFFFFFFFF.toInt() else 0xFF9FB3A8.toInt())
        }
    }

    private fun selectSegByText(seg: ViewGroup, text: String) {
        for (i in 0 until seg.childCount) {
            val child = seg.getChildAt(i) as? TextView ?: continue
            if (child.text.toString() == text || child.text.toString().startsWith(text)) {
                selectSegIndex(seg, i)
                return
            }
        }
    }

    private fun segSelectedIndex(seg: ViewGroup): Int {
        for (i in 0 until seg.childCount) {
            if (seg.getChildAt(i).isSelected) return i
        }
        return 0
    }

    private fun segSelectedText(seg: ViewGroup): String? {
        val child = seg.getChildAt(segSelectedIndex(seg)) as? TextView
        return child?.text?.toString()
    }

    private fun progressListener(onChange: (progress: Int) -> Unit): SeekBar.OnSeekBarChangeListener {
        return object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                onChange(progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        }
    }

    /** 更新系统信息页链路状态与遥控器型号 */
    fun setSysInfo(linkState: String?, rcType: String?) {
        linkState?.let { pageSys.findViewById<TextView>(R.id.sysLink).text = it }
        rcType?.let { pageSys.findViewById<TextView>(R.id.sysRcType).text = it }
    }
}
