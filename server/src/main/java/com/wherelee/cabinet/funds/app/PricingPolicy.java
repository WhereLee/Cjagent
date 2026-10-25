package com.wherelee.cabinet.funds.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wherelee.cabinet.common.api.ResultCode;
import com.wherelee.cabinet.common.exception.BizException;
import com.wherelee.cabinet.locker.api.SizeType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class PricingPolicy {

    private static final Logger log = LoggerFactory.getLogger(PricingPolicy.class);

    private static final long HOURS_IN_MINUTES = 60L;

    private static final int HOURS_IN_DAY = 24;

    private static final ObjectMapper JSON = new ObjectMapper();

    @Value("${cabinet.pricing.prepay-hours}")
    private int prepayHours;

    @Value("${cabinet.pricing.free-minutes}")
    private int freeMinutes;

    @Value("${cabinet.pricing.unit-points-per-hour.LARGE}")
    private long largeUnit;

    @Value("${cabinet.pricing.unit-points-per-hour.MEDIUM}")
    private long mediumUnit;

    @Value("${cabinet.pricing.unit-points-per-hour.SMALL}")
    private long smallUnit;

    @Value("${cabinet.pricing.daily-cap-hours}")
    private int dailyCapHours;

    @Value("${cabinet.pricing.cap-days}")
    private int capDays;

    @Value("${cabinet.pricing.remote-close-hours}")
    private int remoteCloseHours;

    public PricingPolicy() {
    }

    public record Quote(long unitPointsPerHour, int billedHours, long consumePoints,
                        long depositPoints, String snapshot) {
    }

    public Quote quote(SizeType size, long minutes) {
        long unit = unitOf(size);
        return quoteWith(unit, minutes);
    }

    public Quote quoteByRule(SizeType size, long minutes, com.wherelee.cabinet.funds.domain.BizPriceRule rule) {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("priceRuleId", rule.getId());
        extra.put("priceRuleVersion", rule.getVersion());
        return buildQuote(rule.unitOf(size), minutes, rule.getFreeMinutes(), rule.getDailyCapHours(),
                rule.getCapDays(), rule.getDepositPoints(), rule.getRemoteCloseHours(), extra);
    }

    public int prepayHours() {
        return prepayHours;
    }

    public long prepayPoints(SizeType size, com.wherelee.cabinet.funds.domain.BizPriceRule rule) {
        long unit = rule == null ? unitOf(size) : rule.unitOf(size);
        return unit * prepayHours;
    }

    public String inputSnapshot(SizeType size, com.wherelee.cabinet.funds.domain.BizPriceRule rule) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("strategy", "per-hour-daily-cap");
        snapshot.put("sizeType", size.name());
        if (rule == null) {
            snapshot.put("unitPointsPerHour", unitOf(size));
            snapshot.put("freeMinutes", freeMinutes);
            snapshot.put("dailyCapHours", dailyCapHours);
            snapshot.put("capDays", capDays);
            snapshot.put("depositPoints", 0L);
            snapshot.put("prepayHours", prepayHours);
            snapshot.put("prepayPoints", prepayPoints(size, null));
        } else {
            snapshot.put("unitPointsPerHour", rule.unitOf(size));
            snapshot.put("freeMinutes", rule.getFreeMinutes());
            snapshot.put("dailyCapHours", rule.getDailyCapHours());
            snapshot.put("capDays", rule.getCapDays());
            snapshot.put("prepayHours", prepayHours);
            snapshot.put("prepayPoints", prepayPoints(size, rule));
            snapshot.put("priceRuleId", rule.getId());
            snapshot.put("priceRuleVersion", rule.getVersion());
        }
        return write(snapshot);
    }

    public record PricingParams(long unitPointsPerHour, int freeMinutes, int dailyCapHours, int capDays) {
    }

    public PricingParams snapshotParams(String snapshotJson) {
        Snap snap = readSnapshot(snapshotJson);
        return new PricingParams(snap.unit(), snap.freeMinutes(), snap.dailyCapHours(), snap.capDays());
    }

    public int capDaysOf(String snapshotJson) {
        return (int) readSnapshot(snapshotJson).capDays();
    }

    public Quote fromSnapshot(String snapshotJson, long actualMinutes) {
        Snap snap = readSnapshot(snapshotJson);
        return buildQuote(snap.unit(), actualMinutes, snap.freeMinutes(), snap.dailyCapHours(),
                snap.capDays(), snap.depositPoints(), snap.remoteCloseHours());
    }

    private Quote quoteWith(long unit, long minutes) {
        return buildQuote(unit, minutes, freeMinutes, dailyCapHours, capDays, 0L, remoteCloseHours);
    }

    public boolean isCapped(String snapshotJson, long heldMinutes) {
        try {
            Snap snap = readSnapshot(snapshotJson);
            if (snap.capDays() <= 0) {
                return false;
            }
            return billedHours(heldMinutes, snap.freeMinutes()) >= snap.capDays() * HOURS_IN_DAY;
        } catch (RuntimeException unreadable) {
            log.warn("定价快照读不出封顶参数，按“未到顶”继续看管（结算时仍会硬失败）snapshot={}",
                    preview(String.valueOf(snapshotJson)), unreadable);
            return false;
        }
    }

    private record Snap(long unit, int freeMinutes, int dailyCapHours, int capDays, long depositPoints,
                        int remoteCloseHours) {
    }

    private Quote buildQuote(long unit, long minutes, int freeMinutes,
                             int dailyCapHours, int capDays, long depositPoints, int remoteCloseHours) {
        return buildQuote(unit, minutes, freeMinutes, dailyCapHours, capDays, depositPoints,
                remoteCloseHours, Map.of());
    }

    private Quote buildQuote(long unit, long minutes, int freeMinutes,
                             int dailyCapHours, int capDays, long depositPoints, int remoteCloseHours,
                             Map<String, Object> extraSnapshot) {
        int rawHours = billedHours(minutes, freeMinutes);
        int chargeable = chargeableHours(rawHours, dailyCapHours, capDays);
        long consume = chargeable * unit;
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("strategy", "per-hour-daily-cap");
        snapshot.put("unitPointsPerHour", unit);
        snapshot.put("freeMinutes", freeMinutes);
        snapshot.put("dailyCapHours", dailyCapHours);
        snapshot.put("capDays", capDays);
        snapshot.put("remoteCloseHours", remoteCloseHours);
        snapshot.put("rawHours", rawHours);
        snapshot.put("billedHours", chargeable);
        snapshot.put("depositPoints", depositPoints);
        snapshot.put("formula", "chargeableHours(ceil(max(0, minutes - freeMinutes) / 60), dailyCapHours, capDays) * unit");
        snapshot.putAll(extraSnapshot);
        return new Quote(unit, chargeable, consume, depositPoints, write(snapshot));
    }

    static int chargeableHours(int rawHours, int dailyCapHours, int capDays) {
        if (rawHours <= 0) {
            return 0;
        }
        if (dailyCapHours <= 0 && capDays <= 0) {
            return rawHours;
        }
        int perDay = dailyCapHours > 0 ? Math.min(dailyCapHours, HOURS_IN_DAY) : HOURS_IN_DAY;
        int days = (rawHours + HOURS_IN_DAY - 1) / HOURS_IN_DAY;
        if (capDays > 0 && days > capDays) {
            return capDays * perDay;
        }
        int lastDayHours = rawHours - (days - 1) * HOURS_IN_DAY;
        return (days - 1) * perDay + Math.min(lastDayHours, perDay);
    }

    private int billedHours(long minutes, int freeMinutes) {
        long billable = minutes - freeMinutes;
        if (billable <= 0) {
            return 0;
        }
        return (int) ((billable + HOURS_IN_MINUTES - 1) / HOURS_IN_MINUTES);
    }

    private long unitOf(SizeType size) {
        return switch (size) {
            case LARGE -> largeUnit;
            case MEDIUM -> mediumUnit;
            case SMALL -> smallUnit;
        };
    }

    private Snap readSnapshot(String snapshotJson) {
        if (snapshotJson == null || snapshotJson.isBlank()) {
            throw new BizException(ResultCode.SYSTEM_ERROR, "订单缺少定价快照，无法结算，需人工处理");
        }
        try {
            JsonNode node = JSON.readTree(snapshotJson);
            long unit = requireLong(node, "unitPointsPerHour", snapshotJson);
            return new Snap(unit,
                    (int) readLong(node, "freeMinutes", this.freeMinutes),
                    (int) readLong(node, "dailyCapHours", 0L),
                    (int) readLong(node, "capDays", 0L),
                    readLong(node, "depositPoints", 0L),
                    (int) readLong(node, "remoteCloseHours", 0L));
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(ResultCode.SYSTEM_ERROR,
                    "定价快照解析失败，需人工处理 snapshot=" + preview(snapshotJson));
        }
    }

    private long requireLong(JsonNode node, String field, String snapshotJson) {
        JsonNode value = node.get(field);
        if (value == null) {
            throw new BizException(ResultCode.SYSTEM_ERROR,
                    "定价快照缺少 " + field + " 字段，需人工处理 snapshot=" + preview(snapshotJson));
        }
        if (value.isNumber()) {
            return value.asLong();
        }
        try {
            long parsed = Long.parseLong(value.asText().trim());
            log.warn("定价快照 {} 是字符串而非数字，已按数值读取；请排查写入侧 snapshot={}",
                    field, preview(snapshotJson));
            return parsed;
        } catch (NumberFormatException notNumeric) {
            throw new BizException(ResultCode.SYSTEM_ERROR,
                    "定价快照 " + field + " 无法解析，需人工处理 snapshot=" + preview(snapshotJson));
        }
    }

    private long readLong(JsonNode node, String field, long fallback) {
        JsonNode value = node.get(field);
        return value == null || !value.isNumber() ? fallback : value.asLong();
    }

    private String preview(String json) {
        return json.length() <= 400 ? json : json.substring(0, 400) + "…";
    }

    private String write(Map<String, Object> snapshot) {
        try {
            return JSON.writeValueAsString(snapshot);
        } catch (Exception e) {
            throw new BizException(ResultCode.SYSTEM_ERROR, "定价快照序列化失败");
        }
    }
}
