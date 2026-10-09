package com.wherelee.cabinet.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.wherelee.cabinet.domain.enums.SizeType;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 计价策略（第 14D 刀）。
 *
 * <p>它只回答一个问题：<b>下一张新单按什么价算</b>。已经下出去的单不参与——
 * 它们的价格在下单那一刻就抄进了 {@code pricing_snapshot}，结算是读快照而不是读这张表。
 * 这条边界是第 12 刀定的，本刀把"价"从配置文件挪到数据库之后更不能松：
 * 否则"运营改价"就变成"追溯改掉进行中的订单"，用户看到的价和实际扣的价不一样。
 *
 * <p>{@code siteId} 为 NULL 表示该租户的全局策略；非空就是按点位灰度。
 * 这里<b>不用 {@code site_id = 0} 当"全局"的魔法值</b>：那样"全局"就有两种表达
 * （NULL 与 0），查错一个就出现"全局策略悄悄盖住了站点策略"这类查不动的问题。
 */
@Getter
@Setter
@TableName("biz_price_rule")
public class BizPriceRule extends BaseEntity {

    /** 生效状态 */
    public static final String LIVE = "LIVE";
    public static final String SUPERSEDED = "SUPERSEDED";

    private Long siteId;
    private Integer version;
    private String status;

    private Long depositPoints;
    private Integer freeMinutes;
    private Integer dailyCapHours;
    private Integer capDays;
    private Integer toleranceMinutes;
    private Integer remoteCloseHours;
    private Long unitSmall;
    private Long unitMedium;
    private Long unitLarge;

    private LocalDateTime effectiveAt;
    private Long publishedBy;
    private String reason;

    /** 按尺寸取小时单价。尺寸与列一一对应，所以这里不给默认值——拿不到就是编程错误。 */
    public long unitOf(SizeType size) {
        return switch (size) {
            case SMALL -> unitSmall;
            case MEDIUM -> unitMedium;
            case LARGE -> unitLarge;
        };
    }

    /** 是否已进入生效期（未来生效的策略在到点前不参与结算价）。 */
    public boolean effectiveNow(LocalDateTime now) {
        return LIVE.equals(status) && !effectiveAt.isAfter(now);
    }
}
