package com.skydroid.insulatordetect.protocol;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * 0x17 校准命令应答模型（模块→上位机）。
 * 应答数据域：子命令 + 结果 + 原因 + 参数1(4B) [+ 参数2(4B)]。
 * 参数1/2 除 0x03/0x04 外均为 int32 毫值大端（物理值×1000，500 MΩ → 500000）；
 * 0x03（写 K）/0x04（读 K）的参数1 为 4 字节 IEEE754 float 大端，通过 {@link #k} 读取，
 * {@link #value1} 仍保留原始 32 位比特便于日志核对。
 * 对应 C++ 参考实现 CCalibAnswer。
 */
public class CalibAnswer {

    /** K 的上电默认值（仅存 RAM，掉电/复位后恢复为 10） */
    public static final float DEFAULT_K = 10.0f;

    /** 子命令：0x03写K 0x04读K 0x05恢复默认 0x06查询原始值 0x0A补偿验证 0x0C读回系数 0x0E单点定标 */
    public int subCmd;
    /** 结果：0x01成功 0x00失败 */
    public int result;
    /** 失败原因：0x01原始值超时 0x04Flash写失败 0x05参数长度/格式错误 0x09参数非法 */
    public int reason;
    /**
     * 参数1（int32 毫值大端）：0x05为k，0x06为原始X，0x0C为a，0x0A为原始值，0x0E为a；
     * 0x03/0x04 时为 K 的 IEEE754 比特原样（请优先使用 {@link #k}）。
     */
    public int value1;
    /** 参数2（int32 毫值大端）：0x05/0x0C/0x0E为b，0x0A为校准后值；无第二参数时为 0 */
    public int value2;
    /** 校准系数 K，仅 subCmd 为 0x03/0x04 且应答带参数时有效 */
    public float k = DEFAULT_K;

    public boolean isSuccess() {
        return result == 0x01;
    }

    /** 当前应答是否为 K 的写入/读回（0x03/0x04） */
    public boolean isKAnswer() {
        return subCmd == 0x03 || subCmd == 0x04;
    }

    /** 参数1 的物理值（毫值 ÷ 1000），0x03/0x04 时应改用 {@link #k} */
    public float value1Physical() {
        return value1 / 1000.0f;
    }

    /** 参数2 的物理值（毫值 ÷ 1000） */
    public float value2Physical() {
        return value2 / 1000.0f;
    }

    /**
     * 从 0x17 应答数据域解析（不含 0x10/0x11 采样电压子命令，
     * 那两个由 {@link WHSDControlBoardProtocol.SampleVoltageFrame} 解析）。
     */
    public static CalibAnswer parse(byte[] data) {
        if (data == null || data.length < 3) {
            return null;
        }
        CalibAnswer a = new CalibAnswer();
        a.subCmd = data[0] & 0xFF;
        a.result = data[1] & 0xFF;
        a.reason = data[2] & 0xFF;
        // 失败帧可能不带参数（如 17 03 00 05），按长度逐个取
        if (data.length >= 7) {
            ByteBuffer bb = ByteBuffer.wrap(data, 3, 4).order(ByteOrder.BIG_ENDIAN);
            a.value1 = bb.getInt();
            if (a.isKAnswer()) {
                a.k = bb.getFloat(3);
            }
        }
        if (data.length >= 11) {
            a.value2 = ByteBuffer.wrap(data, 7, 4).order(ByteOrder.BIG_ENDIAN).getInt();
        }
        return a;
    }

    /**
     * 单点拉齐辅助计算：由当前读数 x0 与期望目标 y0 反解 K，
     * K = 500 × (y0 − x0) / (x0 − 500)，要求 x0 ≠ 500（500 是公式不动点，无解）。
     * 参数为 MΩ 物理值（非毫值），返回值可直接用于写 K 指令；x0 == 500 时返回 NaN。
     */
    public static float computeKFromSinglePoint(float x0, float y0) {
        float denom = x0 - 500.0f;
        if (denom == 0.0f) {
            return Float.NaN;
        }
        return 500.0f * (y0 - x0) / denom;
    }
}