package com.skydroid.insulatordetect.protocol;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * 0x0D 配置参数模型（上位机→模块），对应《控制模块协议 V1.20》3.14 节
 * 与 C++ 参考实现 CControlBoardProtocolConfig。
 * 参数表共 36 字节，全部小端模式（与 C++ memcpy 行为一致）：
 * byte0~1 激光最近距离阈值(uint16)、byte2~3 激光最远距离阈值(uint16)、
 * byte4~7 安全轮角度阈值(float)、byte8~11 成像板角度阈值(float)、
 * byte12~27 行走/安全轮/成像板/卷扬电机异常电压阈值(4×float)、
 * byte28~31 备用卷扬电机异常电压阈值(float)、byte32~35 成像板运行时间阈值(uint32, ms)。
 */
public class ControlBoardConfig {

    /** 激光测距最近距离阈值 */
    public int lidarMinDis;
    /** 激光测距最远距离阈值 */
    public int lidarMaxDis;
    /** 安全轮角度判断阈值（单位度） */
    public float safeAngle;
    /** 成像板角度判断阈值（单位度） */
    public float panelAngle;
    /** 行走电机异常电压阈值 */
    public float walkingVoltage;
    /** 安全轮电机异常电压阈值 */
    public float safeVoltage;
    /** 成像板电机异常电压阈值 */
    public float panelVoltage;
    /** 卷扬电机异常电压阈值 */
    public float winchVoltage;
    /** 备用卷扬电机异常电压阈值 */
    public float winch2Voltage;
    /** 成像板运行时间阈值，单位毫秒 */
    public long panelRunMs;

    /** 序列化为 36 字节小端参数表，供 0x0D 指令内容使用 */
    public byte[] toBytes() {
        ByteBuffer bb = ByteBuffer.allocate(36).order(ByteOrder.LITTLE_ENDIAN);
        bb.putShort((short) lidarMinDis);
        bb.putShort((short) lidarMaxDis);
        bb.putFloat(safeAngle);
        bb.putFloat(panelAngle);
        bb.putFloat(walkingVoltage);
        bb.putFloat(safeVoltage);
        bb.putFloat(panelVoltage);
        bb.putFloat(winchVoltage);
        bb.putFloat(winch2Voltage);
        bb.putInt((int) panelRunMs);
        return bb.array();
    }
}