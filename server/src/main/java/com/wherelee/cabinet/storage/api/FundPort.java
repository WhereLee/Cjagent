package com.wherelee.cabinet.storage.api;

import com.wherelee.cabinet.domain.entity.BizStorageOrder;
import com.wherelee.cabinet.domain.enums.OrderCloseReason;

/**
 * 订单需要的资金能力（消费方定义的端口）。
 *
 * <p>为什么端口放在 storage 而不是 funds：这四项是“订单流程要钱做什么”，由订单侧定义；
 * 资金服务负责实现。将来拆进程时，把实现换成 HTTP 客户端即可，订单侧一行不动。
 *
 * <p><b>仍然传实体的原因</b>：hold/settle 会在同一个事务里改订单的
 * prepay/settled/arrears 并落库（乐观锁在订单实体上）。改成“返回金额变更、由订单落库”
 * 需要重排事务与补扣路径，属于资金专项改造，不夹在结构搬迁里做。
 * 适配器放在 {@code funds.infra}，把这处临时耦合收在一个类里。
 */
public interface FundPort {

    /** 下单预收：从可用余额扣 4 小时费用，余额不足直接失败。 */
    void holdFunds(BizStorageOrder order);

    /** 下单失败：预收全额退回。 */
    void cancelHold(BizStorageOrder order);

    /** 结算：多退少补、落欠费、关单、释放格口。 */
    void settle(BizStorageOrder order, long actualMinutes, OrderCloseReason reason);

    /** 快照里的封顶天数（订单侧算呆滞用，不把 PricingPolicy 泄进来）。 */
    int capDaysOf(String pricingSnapshot);

    /** 押金退还重试（调度任务用）。 @return 实际退还的点数 */
    int refundHangingDeposit(BizStorageOrder order);
}
