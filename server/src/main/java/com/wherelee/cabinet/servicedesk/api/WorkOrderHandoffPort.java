package com.wherelee.cabinet.servicedesk.api;

public interface WorkOrderHandoffPort {

    String aiReport(String orderNo, String userStatement, String attempt, Boolean userOnSite,
                    WorkOrderIntent intent, String traceId);
}
