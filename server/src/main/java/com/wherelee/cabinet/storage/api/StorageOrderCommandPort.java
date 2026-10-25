package com.wherelee.cabinet.storage.api;

import com.wherelee.cabinet.device.api.CommandAction;
import com.wherelee.cabinet.device.api.CommandOutcome;
import com.wherelee.cabinet.locker.api.Sensing;

public interface StorageOrderCommandPort {

    StorageOrderView create(Long customerId, CreateOrderCommand command);

    StorageOrderView place(Long customerId, CreateOrderCommand command);

    StorageOrderView cancel(Long customerId, String orderNo);

    StorageOrderView openDoor(Long customerId, String orderNo, CommandAction action);

    StorageOrderView pickup(Long customerId, String orderNo);

    StorageOrderView pickup(Long customerId, String orderNo, String voucherCode);

    StorageOrderView reportLeftover(Long customerId, String orderNo);

    VoucherView resetVoucher(Long customerId, String orderNo);

    String activeStrategy();

    boolean doorWatchTick(String orderNo, Sensing sensing);

    void convergeDeviceOutcome(Long orderId, CommandAction action, CommandOutcome outcome);
}
