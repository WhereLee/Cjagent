package com.wherelee.cabinet.application.storage;

import com.wherelee.cabinet.application.storage.dto.CreateOrderCommand;
import com.wherelee.cabinet.application.storage.dto.StorageOrderView;
import com.wherelee.cabinet.common.api.ResultCode;
import com.wherelee.cabinet.common.exception.BizException;
import com.wherelee.cabinet.domain.enums.OrderStatus;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 寄存用例的统一入口：按策略决定走"同步落库"还是"预扣 + 异步落库"。
 *
 * <p>有了它，Controller 不需要知道有四种策略、也不需要每个接口都写一遍分支；
 * 而压测切策略时，<b>业务代码一个字都不用改</b>——这是第 9 刀"复制粘贴出来的对比不可信"
 * 那条判断的落地形式。
 *
 * <p>取消不分流：无论哪条路径，取消都以 DB 里的订单行为准（异步路径下若行还不存在，
 * 由 {@link AsyncStorageOrderAppService#cancelQueued} 处理"取消先于落库"的赛跑）。
 */
@Service
public class StorageOrderFacade {

    private static final String STRATEGY_PREALLOC = "prealloc";

    private final StorageOrderService syncService;
    private final AsyncStorageOrderAppService asyncService;
    /** 只有 redisson 策略会注册这个 bean；其他策略下为空。 */
    private final ObjectProvider<CabinetLockGuard> lockGuardProvider;
    private final String strategy;

    public StorageOrderFacade(StorageOrderService syncService,
                              AsyncStorageOrderAppService asyncService,
                              ObjectProvider<CabinetLockGuard> lockGuardProvider,
                              @Value("${cabinet.alloc.strategy:pessimistic}") String strategy) {
        this.syncService = syncService;
        this.asyncService = asyncService;
        this.lockGuardProvider = lockGuardProvider;
        this.strategy = strategy;
    }

    public StorageOrderView create(Long customerId, CreateOrderCommand command) {
        if (STRATEGY_PREALLOC.equalsIgnoreCase(strategy)) {
            return asyncService.createAsync(customerId, command);
        }
        CabinetLockGuard guard = lockGuardProvider.getIfAvailable();
        if (guard == null) {
            return syncService.create(customerId, command);
        }
        // 锁必须包住整个事务（而不是在事务里加锁）：原因见 CabinetLockGuard
        return guard.aroundCabinet(command.cabinetNo(), () -> syncService.create(customerId, command));
    }

    public StorageOrderView cancel(Long customerId, String orderNo) {
        try {
            return syncService.cancel(customerId, orderNo);
        } catch (BizException e) {
            // 异步路径下“取消先于落库”：行还不存在，只能归还占位。
            // 这个分支不能反过来——已落库的单必须走同步取消，否则格口在 DB 里永远不会释放。
            if (STRATEGY_PREALLOC.equalsIgnoreCase(strategy)
                    && e.getResultCode() == ResultCode.RESOURCE_NOT_FOUND
                    && asyncService.cancelQueued(orderNo, customerId)) {
                return new StorageOrderView(orderNo, "-", "-", "-",
                        OrderStatus.CANCELLED.name(), null, 0, strategy);
            }
            throw e;
        }
    }

    public String activeStrategy() {
        return strategy;
    }
}
