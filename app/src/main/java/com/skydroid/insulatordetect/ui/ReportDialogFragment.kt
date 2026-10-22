package com.skydroid.insulatordetect.ui

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatDialogFragment
import com.skydroid.insulatordetect.R
import com.skydroid.insulatordetect.detect.BadRecord
import com.skydroid.insulatordetect.detect.DetectConfig
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 检测报告弹窗（对应 HTML openReport/#rptMask）：
 * 统计格 + 异常片明细 + 导出占位按钮
 */
class ReportDialogFragment : AppCompatDialogFragment() {

    /** 由宿主 Activity 注入的回调（提供打开弹窗时的实时统计） */
    interface Provider {
        fun reportSubtitle(): String
        fun reportTotal(): Int
        fun reportOk(): Int
        fun reportBadCount(): Int
        fun reportBadList(): List<BadRecord>
        fun reportConfig(): DetectConfig
        fun onReportToast(msg: String)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return Dialog(requireContext(), R.style.Theme_DetectDialog)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.dialog_report, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        dialog?.setCanceledOnTouchOutside(true)
        val provider = activity as? Provider ?: return

        val cfg = provider.reportConfig()
        val total = provider.reportTotal()
        val ok = provider.reportOk()
        val bad = provider.reportBadCount()
        view.findViewById<TextView>(R.id.rTotal).text = total.toString()
        view.findViewById<TextView>(R.id.rOk).text = ok.toString()
        view.findViewById<TextView>(R.id.rBad).text = bad.toString()
        view.findViewById<TextView>(R.id.rRate).text =
            if (total > 0) String.format(Locale.CHINA, "%.2f%%", bad * 100.0 / total) else "0.0%"
        view.findViewById<TextView>(R.id.rTh).text = "${cfg.th}MΩ"
        view.findViewById<TextView>(R.id.tvRptSub).text = provider.reportSubtitle()

        // 异常片明细（对应 .rpt-list）
        val list = view.findViewById<ViewGroup>(R.id.rptList)
        list.removeAllViews()
        val badList = provider.reportBadList()
        if (badList.isEmpty()) {
            val row = TextView(requireContext()).apply {
                text = "暂无异常记录"
                textSize = 13f
                setTextColor(0xFF9FB3A8.toInt())
                setPadding(0, 24, 0, 24)
                gravity = android.view.Gravity.CENTER
            }
            list.addView(row, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        } else {
            badList.forEach { record ->
                val row = LayoutInflater.from(requireContext())
                    .inflate(R.layout.item_report_bad, list, false)
                row.findViewById<TextView>(R.id.tvName).text = record.name
                row.findViewById<TextView>(R.id.tvValue).text = "异常 ${record.value} MΩ"
                list.addView(row)
            }
        }

        view.findViewById<View>(R.id.btnExpWord).setOnClickListener {
            provider.onReportToast("已生成 Word 报告并保存至本地（符合定制模板）")
        }
        view.findViewById<View>(R.id.btnExpPdf).setOnClickListener {
            provider.onReportToast("已生成 PDF 报告并保存至本地（符合定制模板）")
        }
        view.findViewById<View>(R.id.btnCloseRpt).setOnClickListener { dismiss() }
    }

    override fun onStart() {
        super.onStart()
        // 固定弹窗宽度 560dp（对应 .modal width）
        dialog?.window?.setLayout(
            (560 * resources.displayMetrics.density).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    companion object {
        const val TAG = "ReportDialog"

        /** 报告时间戳（对应 HTML new Date().toLocaleString） */
        fun formatNow(): String =
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date())
    }
}
