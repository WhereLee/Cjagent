package com.wherelee.cabinet.storage.api;

/**
 * 遗留物自助取回入口：先开柜确认，再确认已取走。
 *
 * <p>两条动作都有副作用（开门、改格口异常），BFF 只传单号与登录态；
 * 判据（是否本人、是否仍有物、是否超期）都在存储服务里。
 */
public interface LeftoverPort {

    String openForRetrieval(Long customerId, String orderNo);

    String confirmRetrieved(Long customerId, String orderNo);
}
