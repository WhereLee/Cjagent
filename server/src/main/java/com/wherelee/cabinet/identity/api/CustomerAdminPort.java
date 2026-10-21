package com.wherelee.cabinet.identity.api;

/**
 * 后台的客户管理入口（目前只有“改状态”）。
 *
 * <p>它被后台的格口/台账页面用到来展示“这个人还能不能用服务”，但归属与规则都属于身份服务：
 * 接口层只传目标状态与事由，不直接读写客户表。
 */
public interface CustomerAdminPort {

    void changeCustomerStatus(Long customerId, Integer status, String reason);
}
