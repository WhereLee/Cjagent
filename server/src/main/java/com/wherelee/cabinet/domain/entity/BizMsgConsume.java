package com.wherelee.cabinet.domain.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 消息消费幂等记录（V6）。
 *
 * <p>它解决的是"至少一次投递"下的重复消费：同一 {@code (topic, msgKey)} 只处理一次，
 * 重投时撞 {@code uk_msg_consume} 直接跳过业务。
 *
 * <p><b>但这张表不是正确性的唯一防线</b>：并发消费两个副本时，两边都可能先查不到记录、
 * 再各自插入（一方撞唯一键）。所以业务本身仍必须幂等——本项目的落库路径靠
 * {@code uk_order_active_slot} 与订单号唯一索引兜底。<b>幂等表降低重复处理概率，
 * 唯一索引保证重复不发生</b>，两者角色不同，不能互相替代。
 *
 * <p>本表在租户白名单里（写入发生在信任消息里的 tenant 之前），所以 {@code tenantId}
 * 只是排查越权投递用的普通列，查询要自己加条件。
 */
@Getter
@Setter
@TableName("biz_msg_consume")
public class BizMsgConsume extends BaseEntity {

    private String topic;

    /** 业务幂等键：本项目用订单号，不用 broker 的 msgId（重投时 msgId 可能变）。 */
    private String msgKey;

    private Long tenantId;

    /** PENDING / PROCESSED / FAILED，单向推进，由 Mapper 的条件更新保证。 */
    private String status;

    private Integer attempt;

    /** 上游 traceId：让异步链路的日志能接回 HTTP 请求。 */
    private String traceId;

    /**
     * 失败原因。置空是有效语义（重试成功后要清掉旧错误），
     * 因此必须 ALWAYS——MP 默认不写 null（第 8 刀实测坑）。
     */
    @TableField(value = "last_error", updateStrategy = FieldStrategy.ALWAYS)
    private String lastError;

    /** 状态常量，避免各处裸写字符串。 */
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_PROCESSED = "PROCESSED";
    public static final String STATUS_FAILED = "FAILED";
}
