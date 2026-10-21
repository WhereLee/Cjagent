package com.wherelee.cabinet.servicedesk.api;

/**
 * 分诊规则的热加载入口（运营改完规则后由后台触发重算）。
 */
public interface WorkOrderTriagePort {

    void reload();
}
