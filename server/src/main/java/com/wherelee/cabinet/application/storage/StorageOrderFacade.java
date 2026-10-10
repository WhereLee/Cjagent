package com.wherelee.cabinet.application.storage;

import com.wherelee.cabinet.application.storage.dto.CreateOrderCommand;
import com.wherelee.cabinet.application.storage.dto.StorageOrderView;
import com.wherelee.cabinet.common.api.ResultCode;
import com.wherelee.cabinet.common.exception.BizException;
import com.wherelee.cabinet.domain.enums.CommandAction;
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

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(StorageOrderFacade.class);

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

    /**
     * 存件（2026-10-10 定-2）：<b>对用户就是一次动作</b>。
     *
     * <p>用户看到的是“点存件 → 屏幕告诉他 XX 格口即将开启 → 门开了”，而不是“先下单拿单号、
     * 再点一次开柜”。合并之前那个中间态（占了格口还没开门）还得靠一个超时任务去回收，
     * 现在开门失败就当场退回原状，对用户就是一句“没开成，换一格或稍后再试”。
     *
     * <p>为什么不把这个写进 create 本身：建单事务与“等开门回执”必须在两个事务里（等外部设备
     * 最长 2.5 秒，不能带着数据库连接等，第 9 刀量出来的瓶颈）；所以“合并”发生在用例行层，
     * 而不是把设备下发塞进建单事务。
     */
    public StorageOrderView place(Long customerId, CreateOrderCommand command) {
        StorageOrderView created = create(customerId, command);
        try {
            return openDoor(customerId, created.orderNo(), CommandAction.OPEN);
        } catch (RuntimeException e) {
            // 门没开成：这单不应留下任何东西。释放走业务取消路径（含柜内检查与预扣集合同步），
            // 不重写一遗。释放失败只记日志不能掩盖原始失败：格口会由待收敛回收器兼价。
            try {
                cancel(customerId, created.orderNo());
            } catch (RuntimeException release) {
                log.warn("存件失败后的退回也没成功，格口由 10 分钟待收敛回收兼价 orderNo={}: {}",
                        created.orderNo(), release.toString());
            }
            throw e;
        }
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

    public StorageOrderView openDoor(Long customerId, String orderNo, CommandAction action) {
        // 开柜必须面对已落库的订单。异步路径下“消息已发、行还没写”的短暂窗口里，
        // 去操作一个不存在的订单没有意义（格口可能正在被别人拿走），直接回“创建中、请重试”。
        // 把这个窗口暴露给用户而不是粉饰太平：它本来就是异步设让的代价。
        if (STRATEGY_PREALLOC.equalsIgnoreCase(strategy) && !syncService.orderExists(orderNo)) {
            throw new BizException(ResultCode.SYSTEM_ERROR, "寄存单创建中，请稍候重试");
        }
        return syncService.openDoor(customerId, orderNo, action);
    }

    public StorageOrderView pickup(Long customerId, String orderNo) {
        return syncService.pickup(customerId, orderNo);
    }

    /**
     * 取件，可带上取件码（定-6）。
     *
     * <p>码为空就是原来的主路径（登录态）；带码时校验、计错次、达阈就锁定该单开柜。
     * 不分流到异步：理由与 {@link #abandon} 相同——它要看当前的门磁与物检读数。
     */
    public StorageOrderView pickup(Long customerId, String orderNo, String voucherCode) {
        return syncService.pickup(customerId, orderNo, voucherCode);
    }

    /**
     * 声明放弃柜内物品后结束。
     *
     * <p>不分流到异步路径：它与取件一样要面对“已落库的单 + 当前门磁与物检”，
     * 而异步路径的待落库窗口里这些全部未知（第 9 刀定的“写与资金动作只认已落库行”）。
     */
    public StorageOrderView abandon(Long customerId, String orderNo) {
        return syncService.abandon(customerId, orderNo);
    }

    /** 远程结束订单（人不在现场，同一套判据 + 加收）。 */
    public StorageOrderView remoteClose(Long customerId, String orderNo) {
        return syncService.remoteClose(customerId, orderNo);
    }

    /**
     * 否认“柜内有我的东西”→ 走 AI 看图复审（争议阶梯）。
     *
     * <p>与 pickup/abandon 一样不走异步分流：它需要当前的门磁与物检读数，而异步待落库窗口里这些全部未知。
     */
    public StorageOrderView denyItem(Long customerId, String orderNo) {
        return syncService.denyItem(customerId, orderNo);
    }

    /** 下一位使用者上报“本格口里有别人的东西”：锁格 + 本单不收费退回。 */
    public StorageOrderView reportLeftover(Long customerId, String orderNo) {
        return syncService.reportLeftover(customerId, orderNo);
    }

    public String activeStrategy() {
        return strategy;
    }
}
