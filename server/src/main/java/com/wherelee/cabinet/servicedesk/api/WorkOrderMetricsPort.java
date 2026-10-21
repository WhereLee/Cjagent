package com.wherelee.cabinet.servicedesk.api;

/**
 * 客服侧指标的上报入口（只写不读）。
 *
 * <p>BFF 在每次答复后记一笔“是否升级/是否兜底”，指标的聚合与暴露由工单服务自己负责；
 * 这样接口层不会为了打点而依赖整个指标实现类。
 */
public interface WorkOrderMetricsPort {

    void chat(boolean escalated, boolean fallback);
}
