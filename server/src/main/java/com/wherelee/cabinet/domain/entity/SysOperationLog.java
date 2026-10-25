package com.wherelee.cabinet.domain.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

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

    private String operatorName;

    private String params;

    private Integer success;

    private String errorMsg;

    private Long costMs;

    private String traceId;

    private String ip;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
