package com.wherelee.cabinet.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 寄存点位（商场、车站、景区等）。
 *
 * <p>柜机与订单都冗余存了 {@code siteId}：列表页要按点位过滤，join 一次就能避免，
 * 就别在热路径上留 join（见 docs/储物柜业务规划.md §16.7）。
 */
@Getter
@Setter
@TableName("biz_site")
public class BizSite extends BaseEntity {

    private String siteCode;
    private String name;
    /** MALL / STATION / SCENIC / OTHER，与数据库 VARCHAR 直接按枚举名映射 */
    private String category;
    private String address;
    private BigDecimal longitude;
    private BigDecimal latitude;
    /** 1 营业 0 停用 */
    private Integer status;
}
