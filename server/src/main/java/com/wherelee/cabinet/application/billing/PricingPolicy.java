package com.wherelee.cabinet.application.billing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wherelee.cabinet.common.api.ResultCode;
import com.wherelee.cabinet.common.exception.BizException;
import com.wherelee.cabinet.domain.enums.SizeType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 计费策略（轻档：按尺寸单价 × 计费小时 + 每单固定押金，S-02/S-03）。
 *
 * <p><b>核心约束：结算只能用下单时写进 {@code pricing_snapshot} 的单价，不能读当前配置。</b>
 * 否则"运营改价"会追溯性地改变进行中的订单价格——用户看到的价格和实际扣的不一样，
 * 这类差异在真实系统里就是投诉与纠纷，而且事后无法复现当时算的是什么。
 * 所以快照既是审计材料，也是计算的输入，不只是"留个记录"。
 *
 * <p>规则要能一句话说清（复杂规则让账单不可解释）：
 * 免费窗口内不计费；超出后<b>不足一小时按一小时</b>；押金与消耗分栏，退押金不抵扣欠费。
 */
@Component
public class PricingPolicy {

    private static final Logger log = LoggerFactory.getLogger(PricingPolicy.class);

    private static final long HOURS_IN_MINUTES = 60L;

    /**
     * 快照专用 mapper：<b>不能用 Spring 容器里那个</b>。
     *
     * <p>第 5 刀为了让雪花 ID 不在前端丢精度，全局把 {@code Long} 编成字符串。
     * 那是对的，但它只适用于“输出给人看”；拿它写定价快照，算术输入就变成了
     * {@code "unitPointsPerHour": "25"}（字符串），结算读不回来。本刀实测到这一点。
     * 规则：<b>展示层的编码器不得用来序列化业务算术数据</b>。
     */
    private static final ObjectMapper JSON = new ObjectMapper();

    @Value("${cabinet.pricing.deposit-points:200}")
    private long depositPoints;

    @Value("${cabinet.pricing.free-minutes:10}")
    private int freeMinutes;

    @Value("${cabinet.pricing.unit-points-per-hour.LARGE:40}")
    private long largeUnit;

    @Value("${cabinet.pricing.unit-points-per-hour.MEDIUM:25}")
    private long mediumUnit;

    @Value("${cabinet.pricing.unit-points-per-hour.SMALL:15}")
    private long smallUnit;

    public PricingPolicy() {
    }

    /**
     * @param unitPointsPerHour 计费单价（快照后不可变）
     * @param billedHours       计费小时数
     * @param consumePoints       应消耗点数
     * @param depositPoints       押金点数（每单固定）
     * @param snapshot            写进订单的定价快照（结算的唯一依据）
     */
    public record Quote(long unitPointsPerHour, int billedHours, long consumePoints,
                        long depositPoints, String snapshot) {
    }

    public Quote quote(SizeType size, long minutes) {
        long unit = unitOf(size);
        return quoteWith(unit, minutes);
    }

    /** 结算入口：单价取自快照，时长取自服务端时间差。 */
    public Quote fromSnapshot(String snapshotJson, long actualMinutes) {
        long unit = readUnit(snapshotJson);
        return quoteWith(unit, actualMinutes);
    }

    private Quote quoteWith(long unit, long minutes) {
        int hours = billedHours(minutes);
        long consume = hours == 0 ? 0L : unit * hours;
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("strategy", "per-hour-fixed-deposit");
        snapshot.put("unitPointsPerHour", unit);
        snapshot.put("freeMinutes", freeMinutes);
        snapshot.put("billedHours", hours);
        snapshot.put("depositPoints", depositPoints);
        snapshot.put("formula", "ceil(max(0, minutes - freeMinutes) / 60) * unit");
        return new Quote(unit, hours, consume, depositPoints, write(snapshot));
    }

    /** 免费窗口内 0 小时；超出后向上取整（不足一小时按一小时）。 */
    private int billedHours(long minutes) {
        long billable = minutes - freeMinutes;
        if (billable <= 0) {
            return 0;
        }
        // 整数向上取整：(billable + 59) / 60。写成浮点再 ceil 会因为精度在边界上飘（60.0000000001 小时）
        return (int) ((billable + HOURS_IN_MINUTES - 1) / HOURS_IN_MINUTES);
    }

    private long unitOf(SizeType size) {
        return switch (size) {
            case LARGE -> largeUnit;
            case MEDIUM -> mediumUnit;
            case SMALL -> smallUnit;
        };
    }

    private long readUnit(String snapshotJson) {
        if (snapshotJson == null || snapshotJson.isBlank()) {
            throw new BizException(ResultCode.SYSTEM_ERROR, "订单缺少定价快照，无法结算，需人工处理");
        }
        try {
            JsonNode node = JSON.readTree(snapshotJson);
            JsonNode unit = node.get("unitPointsPerHour");
            if (unit == null) {
                throw new BizException(ResultCode.SYSTEM_ERROR,
                        "定价快照缺少单价字段，需人工处理 snapshot=" + preview(snapshotJson));
            }
            if (unit.isNumber()) {
                return unit.asLong();
            }
            // 容错：历史脏数据里单价被写成了字符串（就是上面那个错用容器 mapper 的时期留下的）。
            // 能读出来就该读出来，不该让已经存在的单突然无法结算
            try {
                long parsed = Long.parseLong(unit.asText().trim());
                log.warn("定价快照单价是字符串而非数字，已按数值读取；请排查写入侧 snapshot={}", preview(snapshotJson));
                return parsed;
            } catch (NumberFormatException notNumeric) {
                throw new BizException(ResultCode.SYSTEM_ERROR,
                        "定价快照单价无法解析，需人工处理 snapshot=" + preview(snapshotJson));
            }
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(ResultCode.SYSTEM_ERROR,
                    "定价快照解析失败，需人工处理 snapshot=" + preview(snapshotJson));
        }
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
