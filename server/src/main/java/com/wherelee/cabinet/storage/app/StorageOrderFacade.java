package com.wherelee.cabinet.storage.app;

import com.wherelee.cabinet.device.api.CommandAction;
import com.wherelee.cabinet.locker.api.CabinetLockPort;
import com.wherelee.cabinet.storage.api.CreateOrderCommand;
import com.wherelee.cabinet.storage.api.StorageOrderView;
import org.springframework.stereotype.Service;

@Service
public class StorageOrderFacade implements com.wherelee.cabinet.storage.api.StorageOrderCommandPort {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(StorageOrderFacade.class);

    private final StorageOrderService syncService;
    private final CabinetLockPort cabinetLock;

    public StorageOrderFacade(StorageOrderService syncService, CabinetLockPort cabinetLock) {
        this.syncService = syncService;
        this.cabinetLock = cabinetLock;
    }

    public StorageOrderView create(Long customerId, CreateOrderCommand command) {
        if (!cabinetLock.active()) {
            return syncService.create(customerId, command);
        }
        return cabinetLock.aroundCabinet(command.cabinetNo(), () -> syncService.create(customerId, command));
    }

    public StorageOrderView place(Long customerId, CreateOrderCommand command) {
        StorageOrderView created = create(customerId, command);
        try {
            return openDoor(customerId, created.orderNo(), CommandAction.OPEN);
        } catch (RuntimeException e) {
            try {
                cancel(customerId, created.orderNo());
            } catch (RuntimeException release) {
                log.warn("存件失败后的退回也没成功，格口由 10 分钟待收敛回收兜底 orderNo={}: {}",
                        created.orderNo(), release.toString());
            }
            throw e;
        }
    }

    public StorageOrderView cancel(Long customerId, String orderNo) {
        return syncService.cancel(customerId, orderNo);
    }

    public StorageOrderView openDoor(Long customerId, String orderNo, CommandAction action) {
        return syncService.openDoor(customerId, orderNo, action);
    }

    public StorageOrderView pickup(Long customerId, String orderNo) {
        return syncService.pickup(customerId, orderNo);
    }

    public StorageOrderView pickup(Long customerId, String orderNo, String voucherCode) {
        return syncService.pickup(customerId, orderNo, voucherCode);
    }

    public StorageOrderView reportLeftover(Long customerId, String orderNo) {
        return syncService.reportLeftover(customerId, orderNo);
    }

    public com.wherelee.cabinet.storage.api.VoucherView resetVoucher(Long customerId, String orderNo) {
        return syncService.resetVoucher(customerId, orderNo);
    }

    @Override
    public boolean doorWatchTick(String orderNo, com.wherelee.cabinet.locker.api.Sensing sensing) {
        return syncService.doorWatchTick(orderNo, sensing);
    }

    @Override
    public void convergeDeviceOutcome(Long orderId, com.wherelee.cabinet.device.api.CommandAction action,
                                      com.wherelee.cabinet.device.api.CommandOutcome outcome) {
        syncService.convergeDeviceOutcome(orderId, action, outcome);
    }

    @Override
    public String activeStrategy() {
        return syncService.activeStrategy();
    }
}
