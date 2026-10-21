package com.wherelee.cabinet.storage.api;

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
 *
 * <p>{@code message} 是“这次操作对用户说了什么”（缺陷 16）：门没开成、格口被换过、
 * 取物窗口已下发这类结果原来只进了服务端日志，用户拿到 200 却不知道发生了什么。
 * 它不是错误码的替代品（失败仍然抛异常走 5xx/业务码），只补“请求成功了但结果有讲究”那一段。
 */
public record StorageOrderView(String orderNo,
                               String cabinetNo,
                               String slotNo,
                               String allocatedSize,
                               String status,
                               Integer estimateMinutes,
                               int triedSlots,
                               String strategy,
                               String message) {

    /** 带话的版本：把已有视图重拼一次，不强迫每个构造点都先想好要说什么。 */
    public StorageOrderView withMessage(String note) {
        return new StorageOrderView(orderNo, cabinetNo, slotNo, allocatedSize, status,
                estimateMinutes, triedSlots, strategy, note);
    }
}
