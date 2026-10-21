package com.wherelee.cabinet.identity.api;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 客户资料的只读端口（identity 对外唯一的“看客户”入口）。
 *
 * <p>为什么要有它：以前订单、工单、柜机控制台各自 selectById 客户表，
 * 连“按手机号搜客户”这种查询也散在运营台里。客户属于身份，别人只需要名字与电话，
 * 拿到实体反而会有人顺手改状态。
 */
public interface CustomerQueryPort {

    Optional<CustomerSnapshot> byId(Long customerId);

    List<CustomerSnapshot> byIds(Collection<Long> customerIds);

    /** 按完整手机号找客户 id（运营台按手机号筛订单用）。 */
    List<Long> idsByPhone(String phone);
}
