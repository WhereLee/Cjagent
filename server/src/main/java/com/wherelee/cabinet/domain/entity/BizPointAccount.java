package com.wherelee.cabinet.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Getter;
import lombok.Setter;

/**
 * 点数账户（一客户一账户，DDL 上 {@code uk_point_account_customer}）。
 *
 * <p>两栏必须分开看：{@code points} 是可花的，{@code frozenPoints} 是"占住但不是消耗"的
 * （押金 + 预估费用）。合在一栏里就无法回答"用户还剩多少能花"与"有多少是答应要还的"。
 *
 * <p><b>余额不是真相</b>：真相是流水求和（不变量 3）。这一栏是为了读取性能存在的派生值，
 * 所以 {@code PointAccountService#recalculate} 能从流水重算它，对账作业（第 13 刀）也靠这个校准。
 */
@Getter
@Setter
@TableName("biz_point_account")
public class BizPointAccount extends BaseEntity {

    private Long customerId;

    private Long points;

    private Long frozenPoints;

    /** 并发扣减靠它 + 条件 UPDATE 兜住（缺了就是"后提交者覆盖"）。 */
    @Version
    private Integer version;
}
