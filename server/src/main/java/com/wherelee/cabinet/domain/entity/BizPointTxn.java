package com.wherelee.cabinet.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.wherelee.cabinet.domain.enums.PointTxnType;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 点数流水（<b>只增不改</b>，是账的真相）。
 *
 * <p>刻意不继承 {@link BaseEntity}：表里没有 {@code update_time}/{@code deleted}，
 * 继承会带来"MP 往不存在的列写"这种只在运行时炸的问题（第 11 刀已实测踩过两次列名问题）。
 * 代价显式承担：审计列要自己填，且<b>不提供任何 update 方法</b>——
 * 流水一旦被允许修改，"按流水重算余额"这个校准手段就失去意义。
 *
 * <p>{@code balanceAfter} 是给人看和对账用的快照，<b>不是真相</b>：
 * 真相永远是 {@code Σ amount}（按 {@link PointTxnType#bucket()} 分栏求和）。
 */
@Getter
@Setter
@TableName("biz_point_txn")
public class BizPointTxn {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long tenantId;

    private Long customerId;

    private PointTxnType bizType;

    /** 带符号的变动点数。 */
    private Long amount;

    /** 变动后所属栏的余额快照（便于人读，不参与校验）。 */
    private Long balanceAfter;

    /** 关联单据类型，如 STORAGE_ORDER / DEPOSIT。 */
    private String refType;

    private Long refId;

    /** 幂等业务号；与 bizType 一起构成唯一键 {@code uk_txn_biz}。 */
    private String bizNo;

    private String remark;

    private LocalDateTime createTime;
}
