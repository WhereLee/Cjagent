package com.wherelee.cabinet.application.storage.dto;

/**
 * 寄存单视图（占位/取消的返回）。
 *
 * <p>{@code allocatedSize} 可能与请求尺寸不同（小格口满时降级用了更大的），
 * 必须回显——用户按"背包"的价格占了位、实际用掉一个行李箱格口，
 * 事后从账单里看不出来就是投诉与纠纷。
 *
 * <p>{@code triedSlots} 与 {@code strategy} 是刻意暴露的观测字段：
 * 压测对比时要能证明"CAS 平均尝试了 1.8 次"，而不是只在服务端日志里飘过。
 *
 * <p>ID 一律是字符串（雪花 ID 超 JS 安全整数，见 JacksonConfig）。
 */
public record StorageOrderView(String orderNo,
                               String cabinetNo,
                               String slotNo,
                               String allocatedSize,
                               String status,
                               Integer estimateMinutes,
                               int triedSlots,
                               String strategy) {
}
