package com.wherelee.cabinet.locker.api;

import java.util.Optional;

/**
 * 换格端口：设备在“预备开”阶段明确回报打不开时，在同一柜机里换一个同尺寸空格。
 *
 * <p>它必须留在 locker：候选要用<b>同一条可分配判据</b>（空闲 ∧ 门已关 ∧ 无异常），
 * 另写一套筛选就会出现“换到一个有别人东西的格子”这种最坏结果。
 */
public interface SlotSwapPort {

    /**
     * 先抢新格、再摘旧格；抢不到就试下一个。
     *
     * @return 换到的格口快照；没有可换的返回 empty
     */
    Optional<SlotSnapshot> swapIn(Long cabinetId, SizeType required, Long orderId, Long oldSlotId);
}
