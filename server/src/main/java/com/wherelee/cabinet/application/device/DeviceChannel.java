package com.wherelee.cabinet.application.device;

import com.wherelee.cabinet.domain.enums.CommandAction;
import com.wherelee.cabinet.domain.enums.Presence;
import com.wherelee.cabinet.domain.enums.ReportEvent;

import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;

/**
 * 设备通道端口：把"下发一条指令并拿回执"这件事抽象出来。
 *
 * <p>业务侧<b>只认这个接口</b>：当前实现是进程内模拟器（第 11 刀的定位就是"可控故障源"），
 * 第 16 刀换成真实 MQTT/Paho 实现时，指令幂等、超时、乱序、故障处置这些代码一行都不用改。
 * 这不是为了好看——换实现时最容易出事的正是"这些容错逻辑本来写在通道里"。
 *
 * <p>返回 {@link CompletableFuture} 而不是同步结果，是因为设备回不回、什么时候回<b>不由我们决定</b>。
 * 同步接口会诱使实现方在通道里 sleep，而超时判定必须由调用方掌握（它才知道用户还在不在前面等）。
 */
public interface DeviceChannel {

    /**
     * 异步下发。
     *
     * @return 回执 future；设备不回时 future <b>永不到来</b>，由调用方超时判定（不是这里抛异常）
     */
    CompletableFuture<CommandReceipt> send(CommandTicket ticket);

    /** 通道当前是否可用（柜机离线判定）。真实实现里它对应 broker 连接与会话状态。 */
    boolean available(Long cabinetId);

    /**
     * 读一次格口传感器现状。<b>这是命令做不到的事：一条命令不能替用户把门关上。</b>
     *
     * <p>为什么业务必须靠它而不是只靠回执判定门与物：回执说明的是
     * “设备在那一问里答了什么”，而“结束订单”要的是“此刻门真的关了、里面真的没东西”。
     * 两者不等价，这个不等价就是谎报与漏检钻进来的缝（docs/门态与物品争议设计.md §3）。
     */
    SlotSensor probe(Long cabinetId, Long slotId);

    /**
     * 一次传感器读数。
     *
     * @param doorClosed 门磁结论：门关了没
     * @param presence   柜内物检结论。<b>三值而不是布尔</b>：设备没装物检、传感器脏污、
     *                   柜机离线都是“不知道”，把它们当成“没东西”就会把别人的行李卖给下一位
     * @param sensedAt   读数时刻（服务端时间，设备时钟不可信 S-07）。
     *                   调用方必须查这个时刻新旧：设备“看了一眼”不等于“现在也是”
     */
    record SlotSensor(boolean doorClosed, Presence presence, LocalDateTime sensedAt) {
    }

    /**
     * @param requestId 幂等键，重试不变
     * @param slotId    目标格口；模拟器可能"错报到别的格口"来制造部分成功
     */
    record CommandTicket(String requestId, Long cabinetId, Long slotId, CommandAction action) {
    }

    /**
     * 设备回执。
     *
     * @param success         设备自述是否执行成功
     * @param sensorConfirmed 门磁/光电传感器确认位。<b>谎报的核心检测点</b>：
     *                        {@code success=true} 而它=false 时业务不得当成已关门（S-06）
     * @param seq             设备侧单调序号，乱序收敛依据
     * @param executedSlotId  设备实际操作的格口，与指令不符即为部分成功/错报目标
     */
    record CommandReceipt(String requestId,
                          boolean success,
                          ReportEvent event,
                          long seq,
                          boolean sensorConfirmed,
                          Long executedSlotId,
                          String detail) {
    }
}
