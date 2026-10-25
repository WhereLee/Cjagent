package com.wherelee.cabinet.funds.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.wherelee.cabinet.funds.api.PointTxnType;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@TableName("biz_point_txn")
public class BizPointTxn {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long tenantId;

    private Long customerId;

    private PointTxnType bizType;

    private Long amount;

    private Long balanceAfter;

    private String refType;

    private Long refId;

    private String bizNo;

    private String remark;

    private LocalDateTime createTime;
}
