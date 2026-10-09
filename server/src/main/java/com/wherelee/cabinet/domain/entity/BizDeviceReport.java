package com.wherelee.cabinet.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.wherelee.cabinet.domain.enums.ReportEvent;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 设备上报流水（<b>只增不改</b>）。
 *
 * <p>刻意<b>不继承 {@link BaseEntity}</b>：上报是全系统量最大的一类写入（V4 就是这么权衡的），
 * 建 {@code update_time}/{@code deleted} 纯属浪费，而继承会带来"MP 往不存在的列里写"这种
 * 只在运行时炸的问题。代价在这里显式承担：审计列要自己填。
 *
 * <p>两列时间是这套设计的关键（S-07）：
 * <ul>
 *   <li>{@code reportedAt} 是<b>设备声称</b>的时间，只用于对账，不参与任何计费与状态判定；</li>
 *   <li>{@code receivedAt} 是服务端入库时间，<b>权威</b>。设备时钟漂移、断电重启回到过去，
 *       都会让 reportedAt 不可信——用它能算出"设备说 3 分钟前开的门"，但不能用它决定"要不要收超时费"。</li>
 * </ul>
 *
 * <p>{@code dedupKey} 现在存的是 {@code cmd:<requestId>}：同一条指令的回执重复送达只留一条。
 * 曾经用 {@code cabinetId:seq}，但设备重启会把 seq 归零，重启后的正常回执会被误判为重复而丢弃。
 * 序号本身仍然入库，只用于对账与人工排查。
 */
@Getter
@Setter
@TableName("biz_device_report")
public class BizDeviceReport {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long tenantId;

    private Long cabinetId;

    private Long slotId;

    private Long orderId;

    /** 设备侧单调序号。 */
    private Long seq;

    /** 事件类型，列名 {@code event_type}（字段名必须与列的驼峰形式一致，否则映射不到）。 */
    private ReportEvent eventType;

    /** cabinetId:seq，唯一索引列。 */
    private String dedupKey;

    /** 事件内容（JSON 文本；MySQL 侧是 JSON 列，写入必须是合法 JSON）。 */
    private String payload;

    /** 设备声称时间，仅对账。 */
    private LocalDateTime reportedAt;

    /** 服务端接收时间，权威。 */
    private LocalDateTime receivedAt;

    private LocalDateTime createTime;
}
