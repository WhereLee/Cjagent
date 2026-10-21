package com.wherelee.cabinet.storage.api;

import com.wherelee.cabinet.device.api.CommandAction;

/**
 * 寄存单的写侧入口：下单/打开柜门/取件/取消/凭据重置/遗留物上报。
 *
 * <p>为什么放在 api：BFF 与 AI 客服只需要“做这些动作”，不需要知道同步/异步两条通道、
 * 预扣集合与开门重试。返回类型本来就已是 api 记录；把方法集合收成端口后，
 * 应用类怎么拆都不会再把包名漏给调用方。
 */
public interface StorageOrderCommandPort {

    StorageOrderView create(Long customerId, CreateOrderCommand command);

    /** 下单并开门（当前主路径）；异步通道未启用时与 create 等价。 */
    StorageOrderView place(Long customerId, CreateOrderCommand command);

    StorageOrderView cancel(Long customerId, String orderNo);

    StorageOrderView openDoor(Long customerId, String orderNo, CommandAction action);

    StorageOrderView pickup(Long customerId, String orderNo);

    StorageOrderView pickup(Long customerId, String orderNo, String voucherCode);

    StorageOrderView reportLeftover(Long customerId, String orderNo);

    VoucherView resetVoucher(Long customerId, String orderNo);

    String activeStrategy();
}
