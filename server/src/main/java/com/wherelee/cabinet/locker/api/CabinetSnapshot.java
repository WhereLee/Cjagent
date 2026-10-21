package com.wherelee.cabinet.locker.api;

/**
 * 柜机的只读快照。
 *
 * <p>带 tenantId 是因为设备轮询要按租户发延迟任务：指令结果回到哪个租户，
 * 只能由柜机自己的归属回答，不能让设备服务去猜。
 */
public record CabinetSnapshot(Long id,
                              Long tenantId,
                              Long siteId,
                              String cabinetNo,
                              String name,
                              String cabinetStatus,
                              String onlineState) {
}
