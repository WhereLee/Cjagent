package com.wherelee.cabinet.domain.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.wherelee.cabinet.domain.enums.AiJobStatus;
import com.wherelee.cabinet.domain.enums.AiJobType;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * B 端 AI 作业：一次长文本生成的完整生命周期。
 *
 * <p>它和 {@link BizOutbox} 的区别：outbox 的行是“已经发生、待投递的事实”，投一次就完；
 * 这里的行是“用户点了按钮、要生成一段东西”，有执行中状态、有产出、而且**要被前端按作业号读**。
 *
 * <p>去重不靠时间窗口，靠 {@code activeFlag}：
 * 未完成时写 1、{@code DONE}/{@code FAILED} 时置 {@code null}，配合唯一键
 * {@code (tenantId, jobType, requestKey, requesterId, activeFlag)}——
 * 同一人重复点只能落到同一条；完成之后同内容可以再来一次（那时是他真想再问）。
 * MySQL 唯一索引对含 NULL 的行不判重，正是这里要的语义。
 *
 * <p>{@code requesterId} 在唯一键里是刻意的：AI 产出是 copilot 形态、仅提交人可见，
 * 所以两个人各自点一次就该是两条作业，而不是共享。
 */
@Getter
@Setter
@TableName("biz_ai_job")
public class BizAiJob extends BaseEntity {

    private AiJobType jobType;

    /** 去重键：无入参用固定串，问答用问题文本，简报用工单 ID。 */
    private String requestKey;

    private Long requesterId;

    /** 入参原文（只有 ASK 用得上），仅供执行时读取。 */
    private String payload;

    private AiJobStatus status;

    /** 1 = 未完成；终态置 null（见类注释）。 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Integer activeFlag;

    /** AI 产出。执行成功才写，前端轮询读它。 */
    private String result;

    /** 失败原因要能被清空（成功后不留旧错误），所以 ALWAYS。列名与 biz_delay_task 保持一致。 */
    @TableField(value = "last_error", updateStrategy = FieldStrategy.ALWAYS)
    private String lastError;

    private Integer attempt;

    private String leaseOwner;

    private LocalDateTime leaseExpireAt;
}
