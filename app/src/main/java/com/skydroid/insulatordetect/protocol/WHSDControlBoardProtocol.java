package com.skydroid.insulatordetect.protocol;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 射线机控制模块通讯协议（V1.20）Java 实现。
 * 依据《doc/控制模块协议.md》与 C++ 参考实现 Protocol/WHSDControlBoradProtocol。
 *
 * <p>帧格式：包头 FF FE + 包长度(1B, 整帧≤255) + 包计数(1B, 逐帧递增且不为0)
 * + 指令号(1B) + 指令内容(nB) + 校验和(1B, 包头至指令内容累加和取低8位) + 包尾 FD FC。
 *
 * <p>通讯链路：模块为 TcpServer（192.168.n.111:8234）；本项目也可经 RCSDK 数传管道透传。
 * 本类不绑定具体传输方式，通过 {@link Sender} 输出帧、通过 {@link #feed(byte[])} 输入收到的字节流。
 *
 * <p>典型用法（调试）：
 * <pre>
 *   WHSDControlBoardProtocol p = new WHSDControlBoardProtocol();
 *   p.setSender(frame -&gt; robotLink.send(frame));          // 或 TCP OutputStream::write
 *   p.setListener(new WHSDControlBoardProtocol.Listener() {
 *       &#64;Override public void onZeroValue(float megaOhm) { ... }
 *       &#64;Override public void onHeartBeat(DeviceHeartBeat hb) { ... }
 *   });
 *   p.startHeartbeat();                                   // 200ms 心跳应答
 *   p.send(WHSDControlBoardProtocol.sensorStartDetect(0)); // 触发检测
 *   // 数传/TCP 收到数据后：p.feed(bytes);
 * </pre>
 *
 * <p>多字节编码约定：0x0D 配置参数表与心跳电机状态为小端；
 * 0x17 校准族、0x18 自动行走配置、0x1A 舵机位置为大端；
 * X 值/标准值/系数 a/b 为 int32 毫值（物理值×1000），K 为 IEEE754 float。
 */
public class WHSDControlBoardProtocol {

    // ==================== 指令号 ====================
    public static final int CMD_HEART_BEAT = 0x00;
    public static final int CMD_XRAY_DELAY = 0x01;
    public static final int CMD_XRAY_PULSE = 0x02;
    public static final int CMD_XRAY_START = 0x03;
    public static final int CMD_XRAY_STOP = 0x04;
    public static final int CMD_MOTOR_RUN = 0x05;
    public static final int CMD_MOTOR_FAULT = 0x06;
    public static final int CMD_LOG = 0x07;
    public static final int CMD_RELAY = 0x08;
    public static final int CMD_FACTORY_MODE = 0x09;
    public static final int CMD_OTA_ENTER = 0x0A;
    public static final int CMD_OTA_DATA = 0x0B;
    public static final int CMD_OTA_EXIT = 0x0C;
    public static final int CMD_CONFIG = 0x0D;
    public static final int CMD_CAMERA = 0x0E;
    public static final int CMD_SENSOR_VALUE = 0x0F;
    public static final int CMD_USER_NETWORK = 0x10;
    public static final int CMD_SENSOR = 0x11;
    public static final int CMD_SENSOR_BATTERY = 0x12;
    public static final int CMD_SENSOR_STATUS = 0x13;
    public static final int CMD_SENSOR_RAW = 0x14;
    public static final int CMD_ENCODER_ZERO = 0x15;
    public static final int CMD_CONTROL_MODE = 0x16;
    public static final int CMD_CALIB = 0x17;
    public static final int CMD_AUTO_WALK = 0x18;
    public static final int CMD_SERVO_ARRIVAL = 0x1A;

    // ==================== 电机组（0x05 Byte0） ====================
    public static final int MOTOR_GROUP_ALL = 0x00;
    /** 行走/成像板横移电机组 */
    public static final int MOTOR_GROUP_WALKING = 0x01;
    /** 卷扬/升降/旋转电机组 */
    public static final int MOTOR_GROUP_LIFT = 0x02;
    /** 安全电机组 */
    public static final int MOTOR_GROUP_SAFETY = 0x03;
    /** 成像板电机组 */
    public static final int MOTOR_GROUP_PANEL = 0x04;
    /** 舵机/旋转电机组 */
    public static final int MOTOR_GROUP_SERVO = 0x05;
    /** 支柱绝缘子水平步进电机组 */
    public static final int MOTOR_GROUP_POST_HORIZONTAL = 0x06;
    /** 支柱绝缘子伸缩步进电机组 */
    public static final int MOTOR_GROUP_POST_TELESCOPIC = 0x07;
    /** 支柱绝缘子旋转电机组（文档该组号误写为0x06，按故障反馈类型编号推定为0x08，使用前与固件核对） */
    public static final int MOTOR_GROUP_POST_ROTARY = 0x08;

    // ==================== 电机动作（0x05 Byte2） ====================
    public static final int MOTOR_ACTION_STOP = 0x00;
    /** 右运行/上运行/关闭/水平打开/伸出/检测/舵机运行 */
    public static final int MOTOR_ACTION_FORWARD = 0x01;
    /** 左运行/下运行/开启/垂直收拢/回收/归位 */
    public static final int MOTOR_ACTION_BACKWARD = 0x02;
    public static final int MOTOR_ACTION_BRAKE = 0x03;
    /** 自动行走（仅行走电机，单方向） */
    public static final int MOTOR_ACTION_AUTO_WALK = 0x04;

    // ==================== 速度（0x05 Byte3，舵机组时为角度0~180） ====================
    public static final int SPEED_LOW = 0x00;
    public static final int SPEED_MID = 0x01;
    public static final int SPEED_HIGH = 0x02;

    // ==================== 0x11 传感器子命令 ====================
    public static final int SENSOR_CMD_STOP = 0x00;
    public static final int SENSOR_CMD_START = 0x01;
    public static final int SENSOR_CMD_STATUS = 0x02;
    public static final int SENSOR_CMD_BATTERY = 0x03;
    public static final int SENSOR_CMD_RESULT = 0x04;

    // ==================== 0x17 校准子命令 ====================
    public static final int CALIB_SUB_WRITE_K = 0x03;
    public static final int CALIB_SUB_READ_K = 0x04;
    public static final int CALIB_SUB_RESET = 0x05;
    public static final int CALIB_SUB_QUERY_RAW = 0x06;
    public static final int CALIB_SUB_VERIFY = 0x0A;
    public static final int CALIB_SUB_READ_COEF = 0x0C;
    /** 清除公式系数，会擦写 Flash，严禁用于读取数据 */
    public static final int CALIB_SUB_CLEAR_COEF = 0x0D;
    public static final int CALIB_SUB_SINGLE_POINT = 0x0E;
    public static final int CALIB_SUB_SAMPLE_GROUP0 = 0x10;
    public static final int CALIB_SUB_SAMPLE_GROUP1 = 0x11;

    /** OTA 单包数据长度 */
    public static final int OTA_PACK_LEN = 128;
    /** 心跳默认间隔（协议规定模块 200ms 发一次心跳） */
    public static final int HEART_BEAT_INTERVAL_MS = 200;

    /** 帧发送出口：由调用方绑定 TCP OutputStream 或 RCSDK 数传管道 */
    public interface Sender {
        void send(byte[] frame);
    }

    /**
     * 接收回调。所有方法均为空默认实现，按需覆写。
     * 回调在 {@link #feed(byte[])} 的调用线程执行，更新 UI 请自行切主线程。
     */
    public interface Listener {
        /** 任意校验通过的帧（调试用），payload 为指令内容（不含指令号） */
        default void onRawFrame(int cmd, int packNumber, byte[] payload) {}

        /** 0x00 模块心跳（200ms 一次） */
        default void onHeartBeat(DeviceHeartBeat heartBeat) {}

        /** 单字节执行结果应答：0x01/0x02/0x03/0x04/0x05/0x09/0x0D/0x0E/0x10/0x15/0x16 */
        default void onCommandAck(int cmd, int value) {}

        /** 0x06 电机故障反馈：motorType 0行走 1卷扬 2安全 3成像板 4舵机 5支柱水平 6支柱伸缩 7支柱旋转 */
        default void onMotorFault(int motorType, int motorIndex, int status) {}

        /** 0x07 设备日志（ASCII） */
        default void onDeviceLog(String log) {}

        /** 0x08 继电器状态反馈 */
        default void onRelayState(boolean on) {}

        /** 0x0A 进入 OTA 应答：0失败 1成功(继续心跳) 2成功(暂停心跳) */
        default void onOtaEnterAck(int result) {}

        /** 0x0B OTA 单包应答：result 0失败 1成功，packIndex 为模块确认的包序号(1-based) */
        default void onOtaPackAck(int result, long packIndex) {}

        /** 0x0C 退出 OTA 应答：0升级失败 1升级成功 */
        default void onOtaExitAck(int result) {}

        /** 0x0F 传感器值反馈原始数据：type 0-int 1-float 2-double */
        default void onSensorValue(int sensorIndex, int valueType, byte[] payload) {}

        /** 0x0F 绝缘子 X 值（int32 毫值大端 ÷1000，单位 MΩ），未校准时为原始值 */
        default void onZeroValue(float megaOhm) {}

        /** 0x11 传感器指令反馈（设备状态/电量/检测结果） */
        default void onSensorData(SensorData data) {}

        /** 0x12 传感器模块电量反馈 */
        default void onSensorBattery(int sensorIndex, int percent) {}

        /** 0x13 传感器模块状态反馈：0待机 1触发 2触发完成 */
        default void onSensorStatus(int sensorIndex, int status) {}

        /** 0x14 编码器等传感器数值反馈（原始字节，编码方式由传感器编号决定） */
        default void onSensorRawValue(int sensorIndex, byte[] value) {}

        /** 0x17 校准应答（0x10/0x11 采样电压子命令除外） */
        default void onCalibAnswer(CalibAnswer answer) {}

        /** 0x17/0x10、0x17/0x11 采样电压序列 */
        default void onSampleVoltage(SampleVoltageFrame frame) {}

        /** 0x18 自动行走配置应答 */
        default void onAutoWalkAnswer(AutoWalkAnswer answer) {}

        /** 0x1A 舵机到位反馈（正常到位或超时均上报） */
        default void onServoArrival(ServoArrivalFeedback feedback) {}
    }

    /** 0x11 传感器指令反馈数据 */
    public static class SensorData {
        public int sensorIndex;
        /** 子命令：2设备状态 3电池电量 4检测结果 */
        public int cmd;
        /** 反馈值（小端 uint16，与 C++ 参考实现一致）：状态/电量百分比/结果码 */
        public int value;
    }

    /** 0x1A 舵机到位反馈 */
    public static class ServoArrivalFeedback {
        /** 0x01正常到位 0x00超时未到位 */
        public int result;
        /** 目标位置（0~4095步，大端） */
        public int targetPos;
        /** 实际位置（0~4095步，大端） */
        public int actualPos;

        /** 到位判定：|实际-目标| ≤ 20步（约±1.8°） */
        public boolean isArrived() {
            int diff = actualPos - targetPos;
            return diff >= -20 && diff <= 20;
        }

        /** 步数转角度：角度 = 步数 × 360 ÷ 4095 */
        public static double stepsToAngle(int steps) {
            return steps * 360.0 / 4095.0;
        }
    }

    /** 0x17/0x10、0x17/0x11 采样电压应答（int16 大端，单位 mV） */
    public static class SampleVoltageFrame {
        public int subCmd;
        public int result;
        /** 0x0A无数据 0x0B模块超时 0x0C回帧校验失败 0x0D起始索引越界 */
        public int reason;
        /** 组号：0继电器断开 1继电器闭合 */
        public int group;
        /** 该组完整点数（与分帧无关） */
        public int totalPoints;
        /** 本帧第一个点在整组中的下标 */
        public int startIndex;
        /** 本帧电压序列，单位 mV；单帧上限 100 点，超出按起始索引循环读取 */
        public int[] voltagesMv = new int[0];

        public boolean isSuccess() {
            return result == 0x01;
        }

        static SampleVoltageFrame parse(byte[] p) {
            if (p == null || p.length < 9) {
                return null;
            }
            SampleVoltageFrame f = new SampleVoltageFrame();
            f.subCmd = p[0] & 0xFF;
            f.result = p[1] & 0xFF;
            f.reason = p[2] & 0xFF;
            f.group = p[3] & 0xFF;
            f.totalPoints = ((p[4] & 0xFF) << 8) | (p[5] & 0xFF);
            f.startIndex = ((p[6] & 0xFF) << 8) | (p[7] & 0xFF);
            int n = Math.min(p[8] & 0xFF, (p.length - 9) / 2);
            f.voltagesMv = new int[n];
            for (int i = 0; i < n; i++) {
                f.voltagesMv[i] = (short) (((p[9 + i * 2] & 0xFF) << 8) | (p[10 + i * 2] & 0xFF));
            }
            return f;
        }
    }

    /** 0x18 自动行走配置应答（两参数均 uint16 大端 ms，仅存 RAM，上电默认 1000/6000） */
    public static class AutoWalkAnswer {
        public int subCmd;
        public int result;
        /** 激光触发后继续行走时间 ms，0=触发即停 */
        public int continueWalkMs;
        /** 超时退出自动行走时间 ms，0=不超时退出 */
        public int timeoutExitMs;

        public boolean isSuccess() {
            return result == 0x01;
        }

        static AutoWalkAnswer parse(byte[] p) {
            if (p == null || p.length < 2) {
                return null;
            }
            AutoWalkAnswer a = new AutoWalkAnswer();
            a.subCmd = p[0] & 0xFF;
            a.result = p[1] & 0xFF;
            if (p.length >= 6) {
                a.continueWalkMs = ((p[2] & 0xFF) << 8) | (p[3] & 0xFF);
                a.timeoutExitMs = ((p[4] & 0xFF) << 8) | (p[5] & 0xFF);
            }
            return a;
        }
    }

    // ==================== 实例状态 ====================
    private Sender sender;
    private Listener listener;
    private byte[] buffer = new byte[0];
    private volatile int lastReceivedPackNumber = 0;
    private volatile boolean pauseHeartBeat = false;
    private volatile boolean heartbeatRunning = false;
    private Thread heartbeatThread;

    /** 全局包计数：每发一帧 +1，跳过 0（协议规定包计数不能为 0） */
    private static final AtomicInteger PACK_NUMBER = new AtomicInteger(0);

    public void setSender(Sender sender) {
        this.sender = sender;
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /** 最近一次收到模块帧的包计数（心跳应答需回传该值） */
    public int getLastReceivedPackNumber() {
        return lastReceivedPackNumber;
    }

    /** OTA 期间可手动暂停/恢复心跳应答 */
    public void setPauseHeartBeat(boolean pause) {
        this.pauseHeartBeat = pause;
    }

    /** 发送一帧（经 {@link Sender}），未绑定 Sender 时静默丢弃 */
    public void send(byte[] frame) {
        Sender s = sender;
        if (s != null && frame != null) {
            s.send(frame);
        }
    }

    // ==================== 组帧 ====================

    /**
     * 通用组帧：包头 + 长度 + 自增包计数 + 指令号 + 内容 + 校验和 + 包尾。
     * 所有静态 build 方法最终都走这里，调试时也可直接用它拼任意指令。
     */
    public static byte[] buildFrame(int cmd, byte... content) {
        int len = content.length + 8;
        if (len > 255) {
            throw new IllegalArgumentException("帧长度超限: " + len + " > 255");
        }
        int n = PACK_NUMBER.incrementAndGet() & 0xFF;
        if (n == 0) {
            n = PACK_NUMBER.incrementAndGet() & 0xFF;
        }
        byte[] frame = new byte[len];
        frame[0] = (byte) 0xFF;
        frame[1] = (byte) 0xFE;
        frame[2] = (byte) len;
        frame[3] = (byte) n;
        frame[4] = (byte) cmd;
        System.arraycopy(content, 0, frame, 5, content.length);
        frame[len - 3] = (byte) checksum(frame, len - 3);
        frame[len - 2] = (byte) 0xFD;
        frame[len - 1] = (byte) 0xFC;
        return frame;
    }

    /** 累加和取低8位 */
    private static int checksum(byte[] data, int len) {
        int sum = 0;
        for (int i = 0; i < len; i++) {
            sum += data[i] & 0xFF;
        }
        return sum & 0xFF;
    }

    /** 调试用：字节数组转 HEX 字符串（空格分隔） */
    public static String toHex(byte[] data) {
        if (data == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(data.length * 3);
        for (byte b : data) {
            sb.append(String.format("%02X ", b));
        }
        return sb.toString().trim();
    }

    // ==================== 各指令组帧（上位机→模块） ====================

    /** 0x00 心跳应答：内容为最近一次收到模块心跳的包计数 */
    public static byte[] heartbeatAck(int modulePackNumber) {
        return buildFrame(CMD_HEART_BEAT, (byte) modulePackNumber);
    }

    /** 0x01 射线机设置延迟时间（秒）。未设置时固件默认 15s */
    public static byte[] setXRayDelaySeconds(int seconds) {
        return buildFrame(CMD_XRAY_DELAY, (byte) seconds);
    }

    /** 0x02 射线机设置脉冲数 */
    public static byte[] setXRayPulseCount(int count) {
        return buildFrame(CMD_XRAY_PULSE, (byte) count);
    }

    /**
     * 0x03 射线机开启。
     * @param startMode 0电平开启 1协议开启
     * @param startLevel 0高电平开启 1低电平开启（仅电平开启时有效）
     * @param noDelay 0延迟（读取0x01设置值） 1不延迟
     */
    public static byte[] startXRay(int startMode, int startLevel, int noDelay) {
        return buildFrame(CMD_XRAY_START, (byte) startMode, (byte) startLevel, (byte) noDelay);
    }

    /** 0x04 射线机停止 */
    public static byte[] stopXRay() {
        return buildFrame(CMD_XRAY_STOP, (byte) 0x00);
    }

    /**
     * 0x05 电机运行。
     * @param group 电机组，见 MOTOR_GROUP_*
     * @param enable 使能位图，bit0~bit7 对应组内 1~8 号电机
     * @param action 动作，见 MOTOR_ACTION_*
     * @param speedOrAngle 普通电机为速度档（SPEED_*）；舵机/旋转电机为角度 0~180
     */
    public static byte[] motorRun(int group, int enable, int action, int speedOrAngle) {
        return buildFrame(CMD_MOTOR_RUN, (byte) group, (byte) enable, (byte) action, (byte) speedOrAngle);
    }

    /**
     * 0x05 扩展格式（6字节内容，与 C++ 参考实现 DeviceRun 重载一致）：
     * 角度 + uint16 大端速度，用于支柱绝缘子步进/旋转电机等需要精确参数的场合。
     */
    public static byte[] motorRunWithAngle(int group, int enable, int action, int angle, int speed) {
        return buildFrame(CMD_MOTOR_RUN, (byte) group, (byte) enable, (byte) action,
                (byte) angle, (byte) (speed >> 8), (byte) speed);
    }

    /** 0x05 停止指定电机组（与 C++ DeviceStop 一致：enable=0x03, action=0） */
    public static byte[] motorStop(int group) {
        return buildFrame(CMD_MOTOR_RUN, (byte) group, (byte) 0x03, (byte) 0x00, (byte) 0x00);
    }

    /** 0x05 停止所有电机（与 C++ DeviceStopAll 一致） */
    public static byte[] motorStopAll() {
        return motorStop(MOTOR_GROUP_ALL);
    }

    /** 0x05 全部电机刹车（与 C++ DeviceBreak 一致：00 00 02 03） */
    public static byte[] motorBrakeAll() {
        return buildFrame(CMD_MOTOR_RUN, (byte) 0x00, (byte) 0x00, (byte) 0x02, (byte) 0x03);
    }

    /** 0x05 指定组内电机刹车（action=0x03） */
    public static byte[] motorBrake(int group, int enable) {
        return motorRun(group, enable, MOTOR_ACTION_BRAKE, 0);
    }

    /** 0x06 电机故障反馈应答 */
    public static byte[] motorFaultAck(boolean success) {
        return buildFrame(CMD_MOTOR_FAULT, (byte) (success ? 0x01 : 0x00));
    }

    /** 0x08 继电器控制（总电源）：true 打开 false 关闭 */
    public static byte[] setRelay(boolean on) {
        return buildFrame(CMD_RELAY, (byte) (on ? 0x01 : 0x00));
    }

    /** 0x09 进入/退出工程模式 */
    public static byte[] setFactoryMode(boolean enter) {
        return buildFrame(CMD_FACTORY_MODE, (byte) (enter ? 0x01 : 0x00));
    }

    /** 0x0A 进入 OTA 升级模式 */
    public static byte[] enterOtaMode() {
        return buildFrame(CMD_OTA_ENTER, (byte) 0x00);
    }

    /**
     * 0x0B OTA 数据传输单包。内容 138 字节：
     * 总包数(4B小端) + 当前包序号(4B小端,1-based) + 固定128 + 数据域128B(不足补0) + 域内校验和。
     * 连续 5 次失败应认为升级失败并退出 OTA。
     */
    public static byte[] buildOtaDataFrame(int totalPacks, int packIndex, byte[] chunk) {
        byte[] payload = new byte[138];
        ByteBuffer bb = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(totalPacks);
        bb.putInt(packIndex);
        payload[8] = (byte) OTA_PACK_LEN;
        int n = Math.min(chunk.length, OTA_PACK_LEN);
        System.arraycopy(chunk, 0, payload, 9, n);
        payload[137] = (byte) checksum(payload, 137);
        return buildFrame(CMD_OTA_DATA, payload);
    }

    /** 0x0C 退出 OTA 升级模式 */
    public static byte[] exitOtaMode() {
        return buildFrame(CMD_OTA_EXIT, (byte) 0x00);
    }

    /** 0x0D 配置参数（36字节小端参数表） */
    public static byte[] setControlBoardConfig(ControlBoardConfig config) {
        return buildFrame(CMD_CONFIG, config.toBytes());
    }

    /** 0x0E 摄像头电源控制 */
    public static byte[] setCameraPower(boolean on) {
        return buildFrame(CMD_CAMERA, (byte) (on ? 0x01 : 0x00));
    }

    /** 0x0F 传感器值反馈应答 */
    public static byte[] sensorValueAck(boolean success) {
        return buildFrame(CMD_SENSOR_VALUE, (byte) (success ? 0x01 : 0x00));
    }

    /**
     * 0x10 自定义用户网络参数：ssid长度 + ssid + 密码长度 + 密码
     * + IP(4B) + 端口(2B) + 掩码(4B) + 网关(4B)。
     */
    public static byte[] setUserNetworkConfig(String ssid, String password,
                                              byte[] ip, int port, byte[] mask, byte[] gateway) {
        Charset utf8 = Charset.forName("UTF-8");
        byte[] ssidBytes = ssid.getBytes(utf8);
        byte[] pwdBytes = password.getBytes(utf8);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(ssidBytes.length);
        out.write(ssidBytes, 0, ssidBytes.length);
        out.write(pwdBytes.length);
        out.write(pwdBytes, 0, pwdBytes.length);
        out.write(ip, 0, 4);
        out.write(port >> 8);
        out.write(port & 0xFF);
        out.write(mask, 0, 4);
        out.write(gateway, 0, 4);
        return buildFrame(CMD_USER_NETWORK, out.toByteArray());
    }

    /**
     * 0x11 传感器指令：序号 + 子命令 + 预留2字节（value 大端，与 C++ SensorCmd 一致）。
     * 子命令见 SENSOR_CMD_*。
     */
    public static byte[] sensorCmd(int sensorIndex, int cmd, int value) {
        return buildFrame(CMD_SENSOR, (byte) sensorIndex, (byte) cmd,
                (byte) (value >> 8), (byte) value);
    }

    /** 0x11 触发检测（绝缘子零值检测传感器编号 0） */
    public static byte[] sensorStartDetect(int sensorIndex) {
        return sensorCmd(sensorIndex, SENSOR_CMD_START, 0);
    }

    /** 0x11 停止检测 */
    public static byte[] sensorStopDetect(int sensorIndex) {
        return sensorCmd(sensorIndex, SENSOR_CMD_STOP, 0);
    }

    /** 0x11 查询设备状态：0待机 1触发 2触发完成 3未找到设备 */
    public static byte[] sensorQueryStatus(int sensorIndex) {
        return sensorCmd(sensorIndex, SENSOR_CMD_STATUS, 0);
    }

    /** 0x11 查询电池电量（0~100%） */
    public static byte[] sensorQueryBattery(int sensorIndex) {
        return sensorCmd(sensorIndex, SENSOR_CMD_BATTERY, 0);
    }

    /** 0x11 查询检测结果：0零值 1正常 2未知 3未找到设备 */
    public static byte[] sensorQueryResult(int sensorIndex) {
        return sensorCmd(sensorIndex, SENSOR_CMD_RESULT, 0);
    }

    /** 0x12 查询传感器模块电量 */
    public static byte[] querySensorBattery(int sensorIndex) {
        return buildFrame(CMD_SENSOR_BATTERY, (byte) sensorIndex);
    }

    /** 0x13 查询传感器模块状态 */
    public static byte[] querySensorStatus(int sensorIndex) {
        return buildFrame(CMD_SENSOR_STATUS, (byte) sensorIndex);
    }

    /** 0x14 查询编码器等传感器数值 */
    public static byte[] querySensorValue(int sensorIndex) {
        return buildFrame(CMD_SENSOR_RAW, (byte) sensorIndex);
    }

    /** 0x15 编码器置零（参数含义以固件为准，默认传 0x01） */
    public static byte[] encoderResetZero(int flag) {
        return buildFrame(CMD_ENCODER_ZERO, (byte) flag);
    }

    /** 0x16 电机控制方式（单双一体智能款）：false 单步控制 true 联动控制 */
    public static byte[] setMotorControlMode(boolean linked) {
        return buildFrame(CMD_CONTROL_MODE, (byte) (linked ? 0x01 : 0x00));
    }

    // ==================== 0x17 校准命令族 ====================

    /**
     * 0x17/0x03 写校准系数 K（当前主用，增益校准恒生效）。
     * K 为 4 字节 IEEE754 float 大端：K=10 → 41 20 00 00；K=0 直通；K=50 增益+10%。
     * 立即生效、不写 Flash，掉电/复位恢复默认 10，上位机每次上电需重新下发。
     * 校准模型：校准值 = (1 + K/500) × 测量值 − K。NaN/±Inf 会被固件拒绝（原因0x05）。
     */
    public static byte[] calibWriteK(float k) {
        byte[] payload = new byte[5];
        payload[0] = (byte) CALIB_SUB_WRITE_K;
        ByteBuffer.wrap(payload, 1, 4).order(ByteOrder.BIG_ENDIAN).putFloat(k);
        return buildFrame(CMD_CALIB, payload);
    }

    /** 0x17/0x04 读校准系数 K（上电后首次必读到默认值 10，不符说明发生过复位） */
    public static byte[] calibReadK() {
        return buildFrame(CMD_CALIB, (byte) CALIB_SUB_READ_K);
    }

    /** 0x17/0x05 恢复默认：清空全部校准并把 K 复位为 10。定标前必发 */
    public static byte[] calibReset() {
        return buildFrame(CMD_CALIB, (byte) CALIB_SUB_RESET);
    }

    /** 0x17/0x06 查询原始值：仅认最近一次 0x0F 回报后 5 秒内的值，超时回结果00原因01 */
    public static byte[] calibQueryRaw() {
        return buildFrame(CMD_CALIB, (byte) CALIB_SUB_QUERY_RAW);
    }

    /** 0x17/0x0A 补偿验证：不挂电阻下发任意原始值（int32 毫值），回显原始值+校准后值 */
    public static byte[] calibVerify(int rawMilli) {
        byte[] payload = new byte[5];
        payload[0] = (byte) CALIB_SUB_VERIFY;
        ByteBuffer.wrap(payload, 1, 4).order(ByteOrder.BIG_ENDIAN).putInt(rawMilli);
        return buildFrame(CMD_CALIB, payload);
    }

    /** 0x17/0x0C 读回 Flash 中公式系数 a/b（毫值），用于核对 0x0E 定标结果 */
    public static byte[] calibReadCoef() {
        return buildFrame(CMD_CALIB, (byte) CALIB_SUB_READ_COEF);
    }

    /** 0x17/0x0D 清除公式系数。会擦写 Flash，严禁用于读取数据！采样读取请用 0x10/0x11 */
    public static byte[] calibClearCoef() {
        return buildFrame(CMD_CALIB, (byte) CALIB_SUB_CLEAR_COEF);
    }

    /**
     * 0x17/0x0E 单点定标：标准值 + 实测原始值（均 int32 毫值大端，如 500 MΩ 传 500000）。
     * 要求两值 > 0 且原始值 < 标准值；写 Flash 需 1~2 秒，应答超时建议 ≥ 3 秒。
     * 注意：增益校准优先级更高，本命令算出的 a/b 不会改变 0x0F 回报值，现场校准请用写 K。
     */
    public static byte[] calibSinglePoint(int stdMilli, int rawMilli) {
        byte[] payload = new byte[9];
        payload[0] = (byte) CALIB_SUB_SINGLE_POINT;
        ByteBuffer bb = ByteBuffer.wrap(payload, 1, 8).order(ByteOrder.BIG_ENDIAN);
        bb.putInt(stdMilli);
        bb.putInt(rawMilli);
        return buildFrame(CMD_CALIB, payload);
    }

    /**
     * 0x17/0x10（第0组，继电器断开）或 0x17/0x11（第1组，继电器闭合）读采样电压。
     * 必须先等 0x0F 回报后再发；单帧上限 100 点，总点数超出时按起始索引循环读取。
     * @param group 0 或 1
     * @param startIndex 起始索引（uint16），传 -1 表示省略（按 0 处理）
     */
    public static byte[] calibReadSamples(int group, int startIndex) {
        byte sub = (byte) (group == 1 ? CALIB_SUB_SAMPLE_GROUP1 : CALIB_SUB_SAMPLE_GROUP0);
        if (startIndex < 0) {
            return buildFrame(CMD_CALIB, sub);
        }
        return buildFrame(CMD_CALIB, sub, (byte) (startIndex >> 8), (byte) startIndex);
    }

    // ==================== 0x18 自动行走配置 ====================

    /**
     * 0x18/0x01 写自动行走配置（两参数成对下发，uint16 大端 ms，0~65535）。
     * 立即生效、仅存 RAM，上电默认 1000/6000。只改一项时先读再覆盖下发。
     * @param continueWalkMs 激光触发后继续行走时间，0=触发即停
     * @param timeoutExitMs 超时退出自动行走时间，0=不超时退出
     */
    public static byte[] writeAutoWalkConfig(int continueWalkMs, int timeoutExitMs) {
        return buildFrame(CMD_AUTO_WALK, (byte) 0x01,
                (byte) (continueWalkMs >> 8), (byte) continueWalkMs,
                (byte) (timeoutExitMs >> 8), (byte) timeoutExitMs);
    }

    /** 0x18/0x02 读自动行走配置 */
    public static byte[] readAutoWalkConfig() {
        return buildFrame(CMD_AUTO_WALK, (byte) 0x02);
    }

    // ==================== 接收解析 ====================

    /** 输入收到的字节流（可粘包/半包），内部缓冲并逐帧解析分发 */
    public void feed(byte[] data) {
        if (data != null) {
            feed(data, data.length);
        }
    }

    /** 输入收到的字节流前 len 字节 */
    public synchronized void feed(byte[] data, int len) {
        byte[] merged = new byte[buffer.length + len];
        System.arraycopy(buffer, 0, merged, 0, buffer.length);
        System.arraycopy(data, 0, merged, buffer.length, len);
        buffer = merged;
        while (parseOne()) {
            // 循环解析直到缓冲区不足一帧
        }
    }

    /** 清空接收缓冲（链路重连时调用，避免残留半包干扰） */
    public synchronized void resetBuffer() {
        buffer = new byte[0];
    }

    /** 尝试从缓冲区解析一帧；成功分发后返回 true */
    private boolean parseOne() {
        while (buffer.length > 8) {
            if ((buffer[0] & 0xFF) != 0xFF || (buffer[1] & 0xFF) != 0xFE) {
                erase(1);
                continue;
            }
            int packLen = buffer[2] & 0xFF;
            if (packLen < 9) {
                // 长度非法（最小帧为 9 字节），丢弃包头继续找
                erase(1);
                continue;
            }
            if (buffer.length < packLen) {
                return false; // 半包，等更多数据
            }
            if ((buffer[packLen - 2] & 0xFF) != 0xFD || (buffer[packLen - 1] & 0xFF) != 0xFC) {
                erase(1);
                continue;
            }
            int sum = checksum(buffer, packLen - 3);
            if (sum != (buffer[packLen - 3] & 0xFF)) {
                erase(1);
                continue;
            }
            int packNumber = buffer[3] & 0xFF;
            int cmd = buffer[4] & 0xFF;
            byte[] payload = Arrays.copyOfRange(buffer, 5, packLen - 3);
            erase(packLen);
            dispatch(cmd, packNumber, payload);
            return true;
        }
        return false;
    }

    private void erase(int n) {
        buffer = n >= buffer.length ? new byte[0] : Arrays.copyOfRange(buffer, n, buffer.length);
    }

    /** 帧分发：先回调原始帧，再按指令号解析；0x06/0x0F 按协议自动应答 */
    private void dispatch(int cmd, int packNumber, byte[] p) {
        lastReceivedPackNumber = packNumber;
        Listener l = listener;
        if (l != null) {
            l.onRawFrame(cmd, packNumber, p);
        }
        switch (cmd) {
            case CMD_HEART_BEAT: {
                if (p.length == 64) {
                    DeviceHeartBeat hb = DeviceHeartBeat.parse(p);
                    if (hb != null && l != null) {
                        l.onHeartBeat(hb);
                    }
                }
                break;
            }
            case CMD_MOTOR_FAULT: {
                if (p.length >= 3 && l != null) {
                    l.onMotorFault(p[0] & 0xFF, p[1] & 0xFF, p[2] & 0xFF);
                }
                send(motorFaultAck(true));
                break;
            }
            case CMD_LOG: {
                if (l != null) {
                    l.onDeviceLog(new String(p, Charset.forName("US-ASCII")));
                }
                break;
            }
            case CMD_RELAY: {
                if (p.length >= 1 && l != null) {
                    l.onRelayState(p[0] != 0);
                }
                break;
            }
            case CMD_OTA_ENTER: {
                if (p.length >= 1) {
                    // 与 C++ 一致：应答 0x02 表示模块暂停心跳，上位机同步暂停应答
                    if (p[0] == 0x02) {
                        pauseHeartBeat = true;
                    }
                    if (l != null) {
                        l.onOtaEnterAck(p[0] & 0xFF);
                    }
                }
                break;
            }
            case CMD_OTA_DATA: {
                if (p.length >= 5) {
                    long index = ByteBuffer.wrap(p, 1, 4).order(ByteOrder.LITTLE_ENDIAN).getInt() & 0xFFFFFFFFL;
                    if (l != null) {
                        l.onOtaPackAck(p[0] & 0xFF, index);
                    }
                }
                break;
            }
            case CMD_OTA_EXIT: {
                if (p.length >= 1 && l != null) {
                    l.onOtaExitAck(p[0] & 0xFF);
                }
                break;
            }
            case CMD_SENSOR_VALUE: {
                if (p.length >= 2) {
                    int index = p[0] & 0xFF;
                    int type = p[1] & 0xFF;
                    if (l != null) {
                        l.onSensorValue(index, type, p);
                    }
                    // X 值位于数据域 Byte6~Byte9，int32 有符号大端毫值（不能用无符号接，负值会溢出）
                    if (type == 0 && p.length >= 10 && l != null) {
                        int raw = ByteBuffer.wrap(p, 6, 4).order(ByteOrder.BIG_ENDIAN).getInt();
                        l.onZeroValue(raw / 1000.0f);
                    }
                }
                send(sensorValueAck(true));
                break;
            }
            case CMD_SENSOR: {
                if (p.length >= 4) {
                    SensorData d = new SensorData();
                    d.sensorIndex = p[0] & 0xFF;
                    d.cmd = p[1] & 0xFF;
                    d.value = (p[2] & 0xFF) | ((p[3] & 0xFF) << 8);
                    if (l != null) {
                        l.onSensorData(d);
                    }
                }
                break;
            }
            case CMD_SENSOR_BATTERY: {
                if (p.length >= 2 && l != null) {
                    l.onSensorBattery(p[0] & 0xFF, p[1] & 0xFF);
                }
                break;
            }
            case CMD_SENSOR_STATUS: {
                if (p.length >= 2 && l != null) {
                    l.onSensorStatus(p[0] & 0xFF, p[1] & 0xFF);
                }
                break;
            }
            case CMD_SENSOR_RAW: {
                if (p.length >= 2 && l != null) {
                    int n = Math.min(p[1] & 0xFF, p.length - 2);
                    l.onSensorRawValue(p[0] & 0xFF, Arrays.copyOfRange(p, 2, 2 + n));
                }
                break;
            }
            case CMD_CALIB: {
                if (p.length >= 1 && (p[0] == CALIB_SUB_SAMPLE_GROUP0 || p[0] == CALIB_SUB_SAMPLE_GROUP1)) {
                    SampleVoltageFrame f = SampleVoltageFrame.parse(p);
                    if (f != null && l != null) {
                        l.onSampleVoltage(f);
                    }
                } else {
                    CalibAnswer a = CalibAnswer.parse(p);
                    if (a != null && l != null) {
                        l.onCalibAnswer(a);
                    }
                }
                break;
            }
            case CMD_AUTO_WALK: {
                AutoWalkAnswer a = AutoWalkAnswer.parse(p);
                if (a != null && l != null) {
                    l.onAutoWalkAnswer(a);
                }
                break;
            }
            case CMD_SERVO_ARRIVAL: {
                if (p.length >= 5) {
                    ServoArrivalFeedback f = new ServoArrivalFeedback();
                    f.result = p[0] & 0xFF;
                    f.targetPos = ((p[1] & 0xFF) << 8) | (p[2] & 0xFF);
                    f.actualPos = ((p[3] & 0xFF) << 8) | (p[4] & 0xFF);
                    if (l != null) {
                        l.onServoArrival(f);
                    }
                }
                break;
            }
            default: {
                // 0x01/0x02/0x03/0x04/0x05/0x09/0x0D/0x0E/0x10/0x15/0x16 等单字节结果应答
                if (p.length >= 1 && l != null) {
                    l.onCommandAck(cmd, p[0] & 0xFF);
                }
                break;
            }
        }
    }

    // ==================== 心跳应答线程 ====================

    /** 以协议默认 200ms 间隔启动心跳应答 */
    public void startHeartbeat() {
        startHeartbeat(HEART_BEAT_INTERVAL_MS);
    }

    /**
     * 启动心跳应答线程：周期性向模块回 0x00（内容为最近收到的模块包计数）。
     * 需先 {@link #setSender(Sender)}；OTA 应答 0x02 或手动暂停时不发。
     */
    public synchronized void startHeartbeat(int intervalMs) {
        stopHeartbeat();
        heartbeatRunning = true;
        heartbeatThread = new Thread(() -> {
            while (heartbeatRunning) {
                try {
                    Thread.sleep(intervalMs);
                } catch (InterruptedException e) {
                    break;
                }
                if (!heartbeatRunning) {
                    break;
                }
                if (!pauseHeartBeat) {
                    send(heartbeatAck(lastReceivedPackNumber));
                }
            }
        }, "WHSD-HeartBeat");
        heartbeatThread.setDaemon(true);
        heartbeatThread.start();
    }

    /** 停止心跳应答线程（断开链路前调用） */
    public synchronized void stopHeartbeat() {
        heartbeatRunning = false;
        if (heartbeatThread != null) {
            heartbeatThread.interrupt();
            heartbeatThread = null;
        }
    }
}