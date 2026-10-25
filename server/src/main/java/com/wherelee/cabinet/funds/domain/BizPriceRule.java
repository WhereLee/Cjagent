package com.wherelee.cabinet.funds.domain;

import com.wherelee.cabinet.domain.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import com.wherelee.cabinet.locker.api.SizeType;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@TableName("biz_price_rule")
public class BizPriceRule extends BaseEntity {

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

    public long unitOf(SizeType size) {
        return switch (size) {
            case SMALL -> unitSmall;
            case MEDIUM -> unitMedium;
            case LARGE -> unitLarge;
        };
    }

    public boolean effectiveNow(LocalDateTime now) {
        return LIVE.equals(status) && !effectiveAt.isAfter(now);
    }
}
