package com.wherelee.cabinet.infrastructure.alloc;

import com.wherelee.cabinet.domain.enums.SizeType;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 格口预扣（Redis 集合模型）。
 *
 * <p><b>它只负责"准入"，不是库存真相源</b>：真相在 {@code biz_compartment.status} 与
 * {@code uk_order_active_slot}。预扣的意义是把竞争挡在数据库之前，让 DB 事务不再位于关键路径
 * （第 9 刀压测算出的瓶颈正是"连接池 10 × 事务 1.6s"）。
 *
 * <p>为什么是集合而不是计数：计数会漂移——重复归还凭空造出可卖位（等于超卖），
 * 崩溃/漏消费让计数长期偏低（少卖）。集合成员只能由 {@link #replaceFreeSet} 从 DB 真相同步，
 * <b>多卖在结构上做不到</b>，少卖靠再次同步收敛。
 *
 * <p>已知代价（这套设计的账，第 13 刀调度结清）：
 * <ul>
 *   <li>预扣后进程崩溃或消息未消费 → 该格口在 TTL 内"悬空"（既没卖给别人也没落库）。
 *       方向是<b>少卖</b>不是超卖，靠 hold 标记过期 + 重新同步收敛；</li>
 *   <li>Redis 与 DB 之间没有事务，二者短暂不一致是设计内状态，不是 bug；</li>
 *   <li>Redis 故障必须 <b>fail-closed</b>（拿不到就拒单）。它与限流相反：限流挂了放行只是少一层保护，
 *       预扣挂了放行等于把全部压力直接砸到热点行上，那才是雪崩。</li>
 * </ul>
 */
@Service
public class SlotPreDeductionService {

    private static final String FREE_PREFIX = "cab:alloc:free:";
    private static final String HOLD_PREFIX = "cab:alloc:hold:";

    private static final DefaultRedisScript<String> PRE_ALLOC = load("lua/slot-prealloc.lua", String.class);
    private static final DefaultRedisScript<Long> HOLD_DONE = load("lua/slot-hold.lua", Long.class);

    private final StringRedisTemplate redis;

    public SlotPreDeductionService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** DefaultRedisScript 没有 (Resource, Class) 构造器，只能 setter 装配（第 5 刀实测）。 */
    private static <T> DefaultRedisScript<T> load(String path, Class<T> resultType) {
        DefaultRedisScript<T> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(path));
        script.setResultType(resultType);
        return script;
    }

    /**
     * @param size    实际分配到的尺寸（可能比请求大，因为按偏好降级）
     * @param slotId  从空闲集合里原子弹出的格口 ID
     * @param freeKey 当初命中的集合 key（归还时必须放回**同一个**集合）
     */
    public record PreDeduct(SizeType size, Long slotId, String freeKey) {
    }

    /**
     * 按偏好顺序预扣一个具体格口。
     *
     * @param preference {@code SizeType.acceptanceOrder(required)} 的顺序，即“最小满足优先”
     * @return 命中的格口；{@code null} 表示无空闲
     */
    public PreDeduct tryPreDeduct(Long cabinetId, List<SizeType> preference, String orderNo,
                                  Duration holdTtl, Long customerId) {
        List<String> keys = new ArrayList<>(preference.size() + 1);
        for (SizeType size : preference) {
            keys.add(freeKey(cabinetId, size));
        }
        keys.add(HOLD_PREFIX + orderNo);

        String hit = redis.execute(PRE_ALLOC, keys,
                String.valueOf(preference.size()), String.valueOf(holdTtl.toSeconds()),
                String.valueOf(customerId));
        if (hit == null) {
            return null;
        }

        // 脚本返回 "序号:格口ID"，序号是 1-based 的偏好下标
        int sep = hit.indexOf(':');
        if (sep <= 0) {
            throw new IllegalStateException("预扣脚本返回格式异常: " + hit);
        }
        int index = Integer.parseInt(hit.substring(0, sep)) - 1;
        if (index < 0 || index >= preference.size()) {
            throw new IllegalStateException("预扣脚本返回越界序号: " + hit);
        }
        SizeType size = preference.get(index);
        return new PreDeduct(size, Long.valueOf(hit.substring(sep + 1)), freeKey(cabinetId, size));
    }

    /** 落库成功后收尾：只删标记，格口不回空闲集合。 */
    public boolean commit(PreDeduct deducted, String orderNo) {
        return finish(deducted.freeKey(), orderNo, deducted.slotId(), "COMMIT");
    }

    /** 失败/取消收尾：删标记并把格口放回空闲集合（幂等）。 */
    public boolean release(PreDeduct deducted, String orderNo) {
        return finish(deducted.freeKey(), orderNo, deducted.slotId(), "RELEASE");
    }

    /** 按 hold 里记录的原始集合 key 归还（用于“取消先于落库”：那时调用方手里没有 PreDeduct）。 */
    public boolean releaseByKey(String freeKey, String orderNo, Long slotId) {
        return finish(freeKey, orderNo, slotId, "RELEASE");
    }

    /**
     * 消费者手上只有“柜机 + 尺寸 + 格口”，用它重建收尾所需的上下文。
     * 集合 key 由命名规则推导，但规则只居在本类里（而不是泄到调用方手上拼字符串）。
     */
    public PreDeduct rehydrate(Long cabinetId, SizeType size, Long slotId) {
        return new PreDeduct(size, slotId, freeKey(cabinetId, size));
    }

    /** 读占位标记（取消排队中的单时用它做归属校验与归还定位）。 */
    public Map<String, String> holdOf(String orderNo) {
        Map<Object, Object> raw = redis.opsForHash().entries(HOLD_PREFIX + orderNo);
        Map<String, String> result = new LinkedHashMap<>(raw.size());
        raw.forEach((k, v) -> result.put(String.valueOf(k), String.valueOf(v)));
        return result;
    }

    private boolean finish(String freeKey, String orderNo, Long slotId, String action) {
        Long done = redis.execute(HOLD_DONE, List.of(HOLD_PREFIX + orderNo, freeKey),
                action, String.valueOf(slotId));
        return done != null && done == 1L;
    }

    /**
     * 用 DB 真相重建某柜机某尺寸的空闲集合（同步/校准入口）。
     *
     * <p>先 DEL 再 SADD 期间集合为空，这段时间会<b>少卖</b>（请求拿到"无位"）而不是超卖；
     * 调用方应在低峰执行。要绝对无缝可改成"写临时 key + RENAME"，本项目不需要这个复杂度。
     */
    public void replaceFreeSet(Long cabinetId, SizeType size, Collection<Long> freeSlotIds) {
        String key = freeKey(cabinetId, size);
        redis.delete(key);
        if (!freeSlotIds.isEmpty()) {
            String[] members = freeSlotIds.stream().map(String::valueOf).toArray(String[]::new);
            redis.opsForSet().add(key, members);
        }
    }

    public long freeCount(Long cabinetId, SizeType size) {
        Long count = redis.opsForSet().size(freeKey(cabinetId, size));
        return count == null ? 0L : count;
    }

    public boolean hasHold(String orderNo) {
        return Boolean.TRUE.equals(redis.hasKey(HOLD_PREFIX + orderNo));
    }

    /**
     * 把格口放回空闲集合（SADD 天然幂等）。
     *
     * <p>给“同步路径取消”用：同步路径的真相在 DB，格口一旦回 FREE，Redis 里就必须有它，
     * 否则这个位置从此“谁也算不到”（少卖）。调用方要容忍本方法失败：它只影响准入精度，
     * 不影响正确性，校准会补回来。
     */
    public void addFree(Long cabinetId, SizeType size, Long slotId) {
        redis.opsForSet().add(freeKey(cabinetId, size), String.valueOf(slotId));
    }

    private String freeKey(Long cabinetId, SizeType size) {
        return FREE_PREFIX + cabinetId + ":" + size.name();
    }
}
