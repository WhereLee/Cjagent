package com.wherelee.cabinet.storage.app;

import com.wherelee.cabinet.device.api.CommandAction;
import com.wherelee.cabinet.locker.api.CabinetLockPort;
import com.wherelee.cabinet.storage.api.CreateOrderCommand;
import com.wherelee.cabinet.storage.api.StorageOrderView;
import org.springframework.stereotype.Service;

/**
 * 寄存用例的统一入口。
 *
 * <p>有了它，Controller 不需要知道有四种格口分配策略、也不需要每个接口都写一遍分支；
 * 而压测切策略时，<b>业务代码一个字都不用改</b>——这是第 9 刀"复制粘贴出来的对比不可信"
 * 那条判断的落地形式。策略差异全部收敛在 {@code SlotAllocator} 的四个实现里
 * （悲观锁 / CAS / Redisson / Redis 预占），由 {@code cabinet.alloc.strategy} 选一个装配。
 *
 * <p>本类自己只承担两件不属于任何单一服务的事：redisson 策略下把锁包在建单事务<b>外面</b>
 * （锁必须包住整个事务，在事务里加锁等于没锁），以及"存件 = 建单 + 紧接着开门"这一步合并。
 *
 * <p>这里曾经按策略分流到一条"预占 + 异步落库"的平行下单路径，已删除：
 * 那条路径绕过了本类的建单流程，于是欠费拦截、一人一单校验、超时释放任务登记与取件码
 * 站内消息四件事在它上面都不存在；而它返回的中间态与"下单即开门"互斥，
 * 存件接口在该策略下 100% 失败。预占的价值（把热点行竞争挡在 DB 之前）已经收进
 * {@code PreallocSlotAllocator}，下单只有一条路径。
 */
@Service
public class StorageOrderFacade implements com.wherelee.cabinet.storage.api.StorageOrderCommandPort {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(StorageOrderFacade.class);

    private final StorageOrderService syncService;
    /** 只有 redisson 策略会真的加锁；其他策略下 active() 为 false。 */
    private final CabinetLockPort cabinetLock;

    public StorageOrderFacade(StorageOrderService syncService, CabinetLockPort cabinetLock) {
        this.syncService = syncService;
        this.cabinetLock = cabinetLock;
    }

    public StorageOrderView create(Long customerId, CreateOrderCommand command) {
        if (!cabinetLock.active()) {
            return syncService.create(customerId, command);
        }
        // 锁必须包住整个事务（而不是在事务里加锁）：原因见 CabinetLockPort
        return cabinetLock.aroundCabinet(command.cabinetNo(), () -> syncService.create(customerId, command));
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
            // 门没开成：这单不应留下任何东西。释放走业务取消路径（含柜内检查与预占集合同步），
            // 不重写一遍。释放失败只记日志不能掩盖原始失败：格口会由待收敛回收器兜底。
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

    /**
     * 取件，可带上取件码（定-6）。
     *
     * <p>码为空就是原来的主路径（登录态）；带码时校验、计错次、达阈就锁定该单开柜。
     */
    public StorageOrderView pickup(Long customerId, String orderNo, String voucherCode) {
        return syncService.pickup(customerId, orderNo, voucherCode);
    }

    // abandon（声明放弃柜内物品后结束）、remoteClose（人不在现场的远程结束 + 加收）、
    // denyItem（否认柜内有我的东西 → AI 看图复审）三个转发已于 2026-10-12 随定-13/定-15 一并删除：
    // 结束本来就是在手机上点的，加收失去存在理由；柜内物品争议与误报免除链同时作废，
    // 本类不再保留任何“换取结束许可”的入口。记在这里是为了防止重新加回来——它不是待办。

    /** 下一位使用者上报“本格口里有别人的东西”：锁格 + 本单不收费退回。 */
    public StorageOrderView reportLeftover(Long customerId, String orderNo) {
        return syncService.reportLeftover(customerId, orderNo);
    }

    /**
     * 重置取件码（第 25 刀）：换码 + 错次清零 + 解锁。
     */
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

    /** 当前生效的策略名。真相在分配器上——它才是被 {@code cabinet.alloc.strategy} 选中的那个实现。 */
    @Override
    public String activeStrategy() {
        return syncService.activeStrategy();
    }
}
