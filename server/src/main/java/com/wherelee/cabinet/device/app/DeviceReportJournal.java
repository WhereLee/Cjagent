package com.wherelee.cabinet.device.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wherelee.cabinet.device.api.DeviceChannel;
import com.wherelee.cabinet.device.domain.BizDeviceCommand;
import com.wherelee.cabinet.device.domain.BizDeviceReport;
import com.wherelee.cabinet.device.infra.BizDeviceReportMapper;
import com.wherelee.cabinet.locker.api.CabinetSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
public class DeviceReportJournal {

    private static final Logger log = LoggerFactory.getLogger(DeviceReportJournal.class);

    private static final String REPORT_PREFIX = "cmd:";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final BizDeviceReportMapper reportMapper;

    public DeviceReportJournal(BizDeviceReportMapper reportMapper) {
        this.reportMapper = reportMapper;
    }

    public boolean write(CabinetSnapshot cabinet, Long slotId, BizDeviceCommand command,
                         DeviceChannel.CommandReceipt receipt) {
        LocalDateTime now = LocalDateTime.now();
        BizDeviceReport report = new BizDeviceReport();
        report.setTenantId(cabinet.tenantId());
        report.setCabinetId(cabinet.id());
        report.setSlotId(slotId);
        report.setOrderId(command.getOrderId());
        report.setSeq(receipt.seq());
        report.setEventType(receipt.event());
        int round = command.getRetryCount() == null ? 0 : command.getRetryCount();
        report.setDedupKey(REPORT_PREFIX + receipt.requestId() + (round > 0 ? "#r" + round : ""));
        report.setPayload("{\"requestId\":\"" + receipt.requestId() + "\",\"success\":" + receipt.success()
                + ",\"detail\":" + jsonString(receipt.detail()) + ",\"sensorConfirmed\":" + receipt.sensorConfirmed()
                + ",\"executedSlotId\":" + receipt.executedSlotId() + "}");
        report.setReportedAt(now);
        report.setReceivedAt(now);
        report.setCreateTime(now);

        Long maxSeq = reportMapper.selectMaxSeq(cabinet.id(), slotId);
        if (maxSeq != null && receipt.seq() <= maxSeq) {
            log.info("回执序号不高于已记录（可能为设备重启重发），仍按本指令处理 requestId={} seq={} maxSeq={}",
                    receipt.requestId(), receipt.seq(), maxSeq);
        }
        try {
            reportMapper.insert(report);
        } catch (DuplicateKeyException e) {
            log.info("同一指令回执重复送达，不重复推进状态 dedupKey={}", report.getDedupKey());
            return true;
        }
        return false;
    }

    public DeviceChannel.CommandReceipt latestReceipt(String requestId) {
        return receiptFrom(reportMapper.selectLatestForCommand(requestId));
    }

    private DeviceChannel.CommandReceipt receiptFrom(BizDeviceReport report) {
        if (report == null) {
            return null;
        }
        try {
            JsonNode node = JSON.readTree(report.getPayload());
            return new DeviceChannel.CommandReceipt(requestIdOf(report.getDedupKey()),
                    node.path("success").asBoolean(false),
                    report.getEventType(),
                    report.getSeq() == null ? 0L : report.getSeq(),
                    node.path("sensorConfirmed").asBoolean(false),
                    node.hasNonNull("executedSlotId") ? node.get("executedSlotId").asLong() : null,
                    node.hasNonNull("detail") ? node.get("detail").asText() : null);
        } catch (Exception e) {
            log.error("上报流水 payload 解析失败，改走重试/判死路径 dedupKey={}", report.getDedupKey(), e);
            return null;
        }
    }

    private String requestIdOf(String dedupKey) {
        String value = dedupKey.startsWith(REPORT_PREFIX) ? dedupKey.substring(REPORT_PREFIX.length()) : dedupKey;
        int round = value.indexOf('#');
        return round < 0 ? value : value.substring(0, round);
    }

    private String jsonString(String value) {
        return value == null ? "null" : "\"" + value.replace("\"", "'") + "\"";
    }
}
