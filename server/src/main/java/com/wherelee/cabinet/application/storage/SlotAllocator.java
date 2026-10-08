package com.wherelee.cabinet.application.storage;

import com.wherelee.cabinet.domain.enums.SizeType;

/**
 * 格口分配策略。<b>刻意做成接口</b>：第 9~10 刀要用同一份业务代码跑四种并发方案
 * （悲观锁 / 乐观 CAS / 分布式锁 / Redis 预扣），只差一个配置项
 * {@code cabinet.alloc.strategy}。如果分配逻辑写死在 Service 里，对比就只能靠复制粘贴，
 * 而复制粘贴出来的对比数据是不可信的。
 *
 * <p>实现必须满足两条硬要求：
 * ① 同一格口不得被两个订单同时占住（数据库 {@code uk_order_active_slot} 是最后兜底，
 *    但分配器自己不能依赖它来"正确地失败"）；
 * ② 抛异常时不得留下已占用未绑定的格口（调用方在事务里，异常会回滚，实现里就不许有
 *    绕过事务的写，比如直接改 Redis 计数）。
 */
public interface SlotAllocator {

    /**
     * 在指定柜机上抢占一个能容纳 {@code required} 的格口，并把它绑到 {@code orderId}。
     *
     * @param orderId 订单主键（由调用方预生成，见 StorageOrderService 的说明）
     * @throws com.wherelee.cabinet.common.exception.BizException 10409 真没位 / 10410 抢输了
     */
    AllocatedSlot allocate(Long cabinetId, SizeType required, Long orderId);

    /** 策略名，进日志与压测报告，避免"测了哪个"事后说不清。 */
    String strategy();

    /**
     * @param slotId      抢到的格口 ID
     * @param actualSize  实际分配到的尺寸——可能比需求更大（小格口满时的降级），要回带给用户看
     * @param triedSlots  本次尝试过的候选数量（CAS 方案的性能指标，悲观方案恒为 1）
     */
    record AllocatedSlot(Long slotId, SizeType actualSize, int triedSlots) {
    }
}
