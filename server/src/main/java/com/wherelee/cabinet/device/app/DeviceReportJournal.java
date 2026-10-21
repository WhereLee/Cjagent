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

/**
 * 设备上报流水：写入、幂等判定与“从流水重建回执”。
 *
 * <p>为什么单独一个类：回执入库的幂等键规则（{@code cmd:<requestId>#rN}）是设备重启、
 * 跳轮重试这些真实事故换来的，放在指令状态机里会被后来的改动当成细节抹掉；
 * 这里把它和 payload 结构一起钉在一个地方。
 */
@Component
public class DeviceReportJournal {

    private static final Logger log = LoggerFactory.getLogger(DeviceReportJournal.class);

    private static final String REPORT_PREFIX = "cmd:";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final BizDeviceReportMapper reportMapper;

    public DeviceReportJournal(BizDeviceReportMapper reportMapper) {
        this.reportMapper = reportMapper;
    }

    /**
     * 上报流水入库；返回是否“旧事件”（同一指令的回执重复送达）。
     *
     * <p><b>幂等键用 requestId，不用 {@code cabinetId:seq}</b>。后者看着自然其实错：
     * 设备重启、刷固件、换主控都会把 seq 归零重发，于是重启后第一条<b>正常</b>回执会撞上
     * 重启前的 dedupKey 而被当成重复丢弃——对外表现就是“柜机上线后所有开柜都不生效”。
     * 真实设备一定会重启，所以这条规则在生产上必然发火。
     */
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
        // dedup_key 带轮次后缀：“同一轮内的重复送达”才算重复（挡重放），
        // 跳轮是新的一次物理事件，必须各自入库。否则回扫重试拿到的回执会撞上第一轮的空
        // 幂等键而被判成旧事件，结果就是“重试永远成功不了”（本刀实测）。
        report.setDedupKey(REPORT_PREFIX + receipt.requestId() + (round > 0 ? "#r" + round : ""));
        // success 必须进 payload：回扫时要从流水重建一个能驱动状态机的回执，
        // 缺这一列就分不清“设备说失败”与“设备没报”，回扫只能一律重发
        report.setPayload("{\"requestId\":\"" + receipt.requestId() + "\",\"success\":" + receipt.success()
                + ",\"detail\":" + jsonString(receipt.detail()) + ",\"sensorConfirmed\":" + receipt.sensorConfirmed()
                + ",\"executedSlotId\":" + receipt.executedSlotId() + "}");
        // 设备声称时间只用于对账；权威时间是 received_at（S-07）
        report.setReportedAt(now);
        report.setReceivedAt(now);
        report.setCreateTime(now);

        // 序号对比不能短路：设备重启后 seq 从 0 或 1 重放是真实场景，
        // “seq 很小”本身就是旧事件的信号
        Long maxSeq = reportMapper.selectMaxSeq(cabinet.id(), slotId);
        if (maxSeq != null && receipt.seq() <= maxSeq) {
            // 序号较旧不再拦截业务：设备重启后 seq 会归零，旧≠重复；仅留证据供对账
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

    /** 回扫时从流水重建最近一条回执；没有或解析失败都返回 null。 */
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

    /** {@code cmd:<requestId>} 或 {@code cmd:<requestId>#rN} → requestId。 */
    private String requestIdOf(String dedupKey) {
        String value = dedupKey.startsWith(REPORT_PREFIX) ? dedupKey.substring(REPORT_PREFIX.length()) : dedupKey;
        int round = value.indexOf('#');
        return round < 0 ? value : value.substring(0, round);
    }

    private String jsonString(String value) {
        return value == null ? "null" : "\"" + value.replace("\"", "'") + "\"";
    }
}
