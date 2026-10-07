package com.wherelee.cabinet.domain.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 操作审计记录（对应 V1 建的 {@code sys_operation_log}）。
 *
 * <p>不继承 {@link BaseEntity}：审计是<b>只增不改不删</b>的流水，
 * 没有 update_time / deleted 的语义；{@code tenantId} 由切面按当前登录上下文显式写入
 * （平台侧操作允许为空）。该表也在租户白名单里，否则查审计列表会被自动限定成当前租户。
 */
@Getter
@Setter
@TableName("sys_operation_log")
public class SysOperationLog {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long tenantId;

    private String module;

    private String operation;

    private String requestUri;

    private String requestMethod;

    private Long operatorId;

    /** 操作人名称快照：改名或删号后日志仍能读得懂。 */
    private String operatorName;

    /** 已脱敏、已截断的入参摘要。 */
    private String params;

    /** 1 成功 0 失败。 */
    private Integer success;

    private String errorMsg;

    private Long costMs;

    private String traceId;

    private String ip;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
