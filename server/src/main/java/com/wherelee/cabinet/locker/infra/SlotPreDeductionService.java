package com.wherelee.cabinet.locker.infra;

import com.wherelee.cabinet.locker.api.SizeType;
import com.wherelee.cabinet.locker.api.SlotPoolPort;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 格口预占（Redis 集合模型）。
 *
 * <p><b>它只负责"准入"，不是库存真相源</b>：真相在 {@code biz_compartment.status} 与
 * {@code uk_order_active_slot}。预占的意义是把竞争挡在数据库之前——热点柜机上所有请求
 * 都去撞同一批候选行时，"谁抢到"在 Redis 的单线程原子性里就有了答案，DB 侧只剩一次
 * 不会失败的条件更新（第 9 刀压测算出的瓶颈正是"连接池 10 × 事务 1.6s"）。
 *
 * <p>为什么是集合而不是计数：计数会漂移——重复归还凭空造出可卖位（等于超卖），
 * 崩溃让计数长期偏低（少卖）。集合成员只能由 {@link #replaceFreeSet} 从 DB 真相同步，
 * <b>多卖在结构上做不到</b>，少卖靠再次同步收敛。
 *
 * <p>已知代价（这套设计的账，第 13 刀调度结清）：
 * <ul>
 *   <li>弹出之后、订单事务提交之前进程崩溃 → 该格口"悬空"（既没卖给别人也不在集合里）。
 *       方向是<b>少卖</b>不是超卖，靠 {@code FREE_SET_SYNC} 按 DB 真相重建收敛；</li>
 *   <li>Redis 与 DB 之间没有事务，二者短暂不一致是设计内状态，不是 bug；</li>
 *   <li>Redis 故障必须 <b>fail-closed</b>（拿不到就拒单）。它与限流相反：限流挂了放行只是少一层保护，
 *       预占挂了放行等于把全部压力直接砸到热点行上，那才是雪崩。</li>
 * </ul>
 *
 * <p>这里曾经还有一套"占位标记 + 租约 TTL"（{@code cab:alloc:hold:*} 加一个负责收尾的 lua）。
 * 它是为异步落库设计的：那时预占与落库隔着一次 MQ 往返，需要标记说明"这个位正在被谁处理"，
 * 也需要 TTL 让崩掉的预占自动过期。改成同一个请求、同一个事务里"弹出 + 条件更新"之后，
 * 中间没有任何窗口，标记失去了保护对象；而事务回滚时标记早已被删掉，那时要补的是
 * {@link #addFree}（DB 侧格口已回 FREE，集合里就该有它），不是"归还预占"。
 * 留着它每单要多三次 Redis 往返，而 prealloc 存在的理由就是快。
 */
@Service
public class SlotPreDeductionService {

    private static final String FREE_PREFIX = "cab:alloc:free:";

    private static final DefaultRedisScript<String> POP_FREE = load("lua/slot-prealloc.lua", String.class);

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
     * 按偏好顺序弹出一个具体格口。
     *
     * @param preference {@code SizeType.acceptanceOrder(required)} 的顺序，即“最小满足优先”
     * @return 弹到的格口；{@code null} 表示无空闲
     */
    public SlotPoolPort.Popped pop(Long cabinetId, List<SizeType> preference) {
        List<String> keys = new ArrayList<>(preference.size());
        for (SizeType size : preference) {
            keys.add(freeKey(cabinetId, size));
        }

        String hit = redis.execute(POP_FREE, keys, String.valueOf(preference.size()));
        if (hit == null) {
            return null;
        }

        // 脚本返回 "序号:格口ID"，序号是 1-based 的偏好下标
        int sep = hit.indexOf(':');
        if (sep <= 0) {
            throw new IllegalStateException("预占脚本返回格式异常: " + hit);
        }
        int index = Integer.parseInt(hit.substring(0, sep)) - 1;
        if (index < 0 || index >= preference.size()) {
            throw new IllegalStateException("预占脚本返回越界序号: " + hit);
        }
        return new SlotPoolPort.Popped(preference.get(index), Long.valueOf(hit.substring(sep + 1)));
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

    /**
     * 把格口放回空闲集合（SADD 天然幂等）。
     *
     * <p>给“格口空出来”的路径用：同步路径的真相在 DB，格口一旦回 FREE，Redis 里就必须有它，
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
