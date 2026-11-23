package com.skydroid.insulatordetect.protocol;

/**
 * 0x00 心跳数据模型（模块→上位机，指令内容 64 字节，模块每 200ms 发送一次）。
 * 对应《控制模块协议 V1.20》3.1 节与 C++ 参考实现 CDeviceHeartBeat。
 */
public class DeviceHeartBeat {

    /** 每组电机数量 */
    public static final int MOTOR_COUNT = 8;

    /** 单个电机状态 */
    public static class MotorStatus {
        /** 电机使能（心跳 Byte0/4/8/12 的 bit0~bit7 对应 1~8 号电机） */
        public boolean enable;
        /**
         * 3bit 运行状态：
         * 行走/横移组：0停止 1右运行 2左运行 3刹车 4故障 5右限位 6左限位 7自动行走到位；
         * 卷扬/升降组：0停止 1上运行 2下运行 3刹车 4故障；
         * 安全组：0停止 1关闭中 2开启中 3刹车 4故障；
         * 成像板组：0停止 1水平打开 2垂直收拢 3刹车 4故障。
         */
        public int status;
    }

    /** 行走/成像板横移/支柱绝缘子水平移动电机组（Byte0~Byte3） */
    public final MotorStatus[] walkingMotors = new MotorStatus[MOTOR_COUNT];
    /** 卷扬/升降/旋转/支柱绝缘子伸缩电机组（Byte4~Byte7） */
    public final MotorStatus[] liftMotors = new MotorStatus[MOTOR_COUNT];
    /** 安全/支柱绝缘子旋转电机组（Byte8~Byte11） */
    public final MotorStatus[] safetyMotors = new MotorStatus[MOTOR_COUNT];
    /** 成像板电机组（Byte12~Byte15） */
    public final MotorStatus[] panelMotors = new MotorStatus[MOTOR_COUNT];

    /** Byte16：设备总电量百分比 */
    public int battery;
    /** Byte17：固件发布年份（2025 以 0x19 表示，即十进制 25） */
    public int firmwareYear;
    /** Byte18：固件发布月份 */
    public int firmwareMonth;
    /** Byte19：固件发布日期 */
    public int firmwareDay;
    /** Byte20 bit4~bit7：版本号 */
    public int firmwareVersion;
    /** Byte20 bit0~bit3：固件当天编译次数 */
    public int firmwareBuildOfDay;
    /** Byte21：射线机状态 0空闲 1延时开启中 2工作完成 3故障 */
    public int xRayStatus;
    /** Byte22：总电源 0关 1开 */
    public int mainPower;
    /** Byte23 bit0：工程模式 */
    public boolean factoryMode;
    /** Byte24~Byte25：编码器角度值（高8位在前） */
    public int encoderAngle;

    public DeviceHeartBeat() {
        for (int i = 0; i < MOTOR_COUNT; i++) {
            walkingMotors[i] = new MotorStatus();
            liftMotors[i] = new MotorStatus();
            safetyMotors[i] = new MotorStatus();
            panelMotors[i] = new MotorStatus();
        }
    }

    /**
     * 从 64 字节心跳指令内容解析。
     * 每组 4 字节：Byte0 使能位图，Byte1~Byte3 小端 24bit，每电机占 3bit。
     */
    public static DeviceHeartBeat parse(byte[] data) {
        if (data == null || data.length < 26) {
            return null;
        }
        DeviceHeartBeat hb = new DeviceHeartBeat();
        extractMotorGroup(hb.walkingMotors, data, 0);
        extractMotorGroup(hb.liftMotors, data, 4);
        extractMotorGroup(hb.safetyMotors, data, 8);
        extractMotorGroup(hb.panelMotors, data, 12);
        hb.battery = data[16] & 0xFF;
        hb.firmwareYear = data[17] & 0xFF;
        hb.firmwareMonth = data[18] & 0xFF;
        hb.firmwareDay = data[19] & 0xFF;
        hb.firmwareVersion = (data[20] >> 4) & 0x0F;
        hb.firmwareBuildOfDay = data[20] & 0x0F;
        hb.xRayStatus = data[21] & 0xFF;
        hb.mainPower = data[22] & 0xFF;
        hb.factoryMode = (data[23] & 0x01) != 0;
        hb.encoderAngle = ((data[24] & 0xFF) << 8) | (data[25] & 0xFF);
        return hb;
    }

    private static void extractMotorGroup(MotorStatus[] group, byte[] data, int offset) {
        int enableBits = data[offset] & 0xFF;
        // 后 3 字节按小端拼成 24bit，再逐电机取 3bit 状态
        int statusBits = (data[offset + 1] & 0xFF)
                | ((data[offset + 2] & 0xFF) << 8)
                | ((data[offset + 3] & 0xFF) << 16);
        for (int i = 0; i < MOTOR_COUNT; i++) {
            group[i].enable = (enableBits & (1 << i)) != 0;
            group[i].status = (statusBits >> (i * 3)) & 0x07;
        }
    }

    /** 固件版本描述，例：25-11-13 V2.1 */
    public String firmwareText() {
        return firmwareYear + "-" + firmwareMonth + "-" + firmwareDay
                + " V" + firmwareVersion + "." + firmwareBuildOfDay;
    }
}