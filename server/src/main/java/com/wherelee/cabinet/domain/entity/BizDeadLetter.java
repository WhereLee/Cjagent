package com.wherelee.cabinet.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * MQ 死信归档。重试耗尽仍失败的事实落在这里，等人工看或重放。
 *
 * <p>它是 {@code biz_outbox} 的对立面：outbox 存"待投递"，这张存"投了但没人吃得下"。
 * 两者都以数据库为真相源——MQ 的死信队列只保留 3 天，撑不起证据链。
 *
 * <p><b>三个 topic 字段不是冗余</b>：死信消息躺在 {@code %DLQ%{group}} 里，
 * 而重放必须投回原来的业务 topic + tag。只存死信 topic 的话，重放时无从知道该投回哪。
 *
 * <p>没有 {@code tenantId}：失败的消息可能来自任何租户，这是运维视角的全量表
 * （与 {@code SysOperationLog} 同款处理，在 {@code CabinetTenantHandler} 的白名单里）。
 */
@Getter
@Setter
@TableName("biz_dead_letter")
public class BizDeadLetter extends BaseEntity {

    /** 原业务 topic（重放目标）。 */
    private String topic;

    /** 原 tag（= 事件类型）。 */
    private String tag;

    /** 死信所在 topic，形如 {@code %DLQ%cabinet-outbox-workorder}。 */
    private String dlqTopic;

    /** 消息 KEYS（= outbox 的 dedupKey）。坏消息可能连这个都没有。 */
    private String msgKey;

    private String payload;

    /** 消费失败原因，列宽 255。 */
    private String error;

    /** 已被重试的次数（RocketMQ 的 reconsumeTimes）。 */
    private Integer reconsumeTimes;

    /** OPEN 待处理 / REPLAYED 已重放。 */
    private String status;

    private LocalDateTime receivedAt;

    private LocalDateTime replayedAt;

    public static final String STATUS_OPEN = "OPEN";
    public static final String STATUS_REPLAYED = "REPLAYED";
}
