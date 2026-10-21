package com.wherelee.cabinet.storage.api;

/**
 * 公开的凭码取件入口（手机没电时的第二条路径）：码 + 手机尾号双因子。
 */
public interface PublicPickupPort {

    StorageOrderView pickup(String voucherCode, String phoneTail);
}
