package com.wherelee.cabinet.storage.api;

/**
 * 一张寄存单的对外快照（只读）。
 *
 * <p>只放"别的服务判断需要什么"：身份（谁的单）、位置（哪台柜哪个格）、状态。
 * 金额、争议、快照 JSON 一律不出去——那些是订单服务自己的事，需要时再开专用查询方法。
 */
public record OrderSnapshot(Long id,
                            String orderNo,
                            Long customerId,
                            Long cabinetId,
                            Long slotId,
                            String status,
                            String sizeType) {
}
