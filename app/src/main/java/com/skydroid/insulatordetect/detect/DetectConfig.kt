package com.skydroid.insulatordetect.detect

/**
 * 检测配置模型（对应 HTML cfg 对象 + 线路设置/参数配置抽屉字段）
 */
data class DetectConfig(
    // ===== 线路设置 =====
    var phase: String = "A相",           // 安装相别
    var side: String = "左串",           // 所在串号
    var oppSide: String = "右串",        // 对侧串号（"无" 表示单串布置）
    var count: Int = 60,                 // 每联片数（最大 200）
    var linkType: String = LINK_DUAL,    // 串型：双联/单联
    var lineName: String = "750kV城苏2线",
    var towerNo: String = "20",          // 塔杆序号
    var position: String = "大号侧",     // 安装位置
    var voltageLevel: String = "AC 750kV",
    // 环境信息（现场实测录入）
    var temp: String = "20",
    var humi: String = "33",
    var wind: String = "3.2",
    // ===== 参数配置 =====
    var kv: Int = 50,                    // 冲击电压档位 kV（DL/T 2453-2021，≥50kV 起调）
    var th: Int = 500,                   // 劣化判定阈值 MΩ
    var retest: Int = 2,                 // 报警片自动复测次数
    var bitrate: String = "中",          // 图传码率：低/中/高
    var motor: Int = 80,                 // 电机行走速度 mm/s
    var ext: Int = 35,                   // 探针伸出速度 mm/s
    var ret: Int = 50,                   // 探针复位速度 mm/s
    var rbtIp: String = "192.168.1.10",  // 机器人 IP
    var camUrl: String = DEFAULT_CAM_URL, // 摄像头完整 RTSP 地址（自定义摄像头可任意指定路径）
    // ===== 检测模式 =====
    var mode: String = MODE_AUTO         // auto/manual
) {
    val isDual: Boolean get() = linkType == LINK_DUAL
    val isManual: Boolean get() = mode == MODE_MANUAL

    /** 旧版本仅保存摄像头 IP，读取旧配置时兜底拼接默认流地址 */
    private var camIp: String? = null

    /** 摄像头图传 RTSP 地址（完整 URL，直接交给 FPVWidget 播放） */
    fun rtspUrl(): String {
        // Gson 反序列化旧配置时新字段可能为 null，经局部可空变量兜底
        val url: String? = camUrl
        if (!url.isNullOrBlank()) return url
        val ip: String? = camIp
        return if (!ip.isNullOrBlank()) "rtsp://$ip:554/stream=0" else DEFAULT_CAM_URL
    }

    /** 从 RTSP 地址中提取主机 IP（供 C10Pro 等按 IP 寻址的相机控制使用） */
    fun camHost(): String =
        rtspUrl().removePrefix("rtsp://").substringBefore('/').substringBefore(':')

    companion object {
        const val LINK_DUAL = "双联"
        const val LINK_SINGLE = "单联"
        const val MODE_AUTO = "auto"
        const val MODE_MANUAL = "manual"
        const val MAX_COUNT = 200
        const val DEFAULT_CAM_URL = "rtsp://192.168.144.108:554/stream=0"
    }
}
