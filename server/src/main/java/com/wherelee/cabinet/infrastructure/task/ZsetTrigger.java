package com.wherelee.cabinet.infrastructure.task;

import com.wherelee.cabinet.application.task.DelayReminder;
import com.wherelee.cabinet.application.task.DelayTaskService;
import com.wherelee.cabinet.domain.enums.TaskType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 触发方式二：Redis ZSet 索引（分数 = 到期毫秒）。
 *
 * <p>相比轮询的优势是<b>低延迟不用高频扫表</b>：只取已到期的成员，表再大也不做全表扫描。
 *
 * <p>它的两个短板都写明了：
 * <ul>
 *   <li>Redis 与 DB 没有事务，ZSet 会漂移（写完 DB 崩在 ZADD 前 → 提醒缺失）；
 *       所以本类<b>同时保留一条慢轮询兜底</b>——提醒只决定快不快，轮询决定会不会漏；</li>
 *   <li>取出即删除，取出后崩溃同样丢提醒，仍靠慢轮询补。</li>
 * </ul>
 * 这也是"ZSet 可以做索引、不能做真相源"的具体理由。
 */
@Component
@ConditionalOnProperty(name = "cabinet.scheduler.trigger", havingValue = "zset")
public class ZsetTrigger implements DelayReminder {

    private static final Logger log = LoggerFactory.getLogger(ZsetTrigger.class);
    private static final String ZSET_KEY = "cab:task:zset";
    private static final DefaultRedisScript<List> TAKE = takeScript();

    private final DelayTaskService tasks;
    private final StringRedisTemplate redis;

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static DefaultRedisScript<List> takeScript() {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("lua/task-zset-take.lua"));
        script.setResultType(List.class);
        return script;
    }

    public ZsetTrigger(DelayTaskService tasks, StringRedisTemplate redis) {
        this.tasks = tasks;
        this.redis = redis;
    }

    @Override
    public void remind(TaskType type, String bizKey, LocalDateTime fireAt) {
        redis.opsForZSet().add(ZSET_KEY, type.name() + ":" + bizKey,
                fireAt.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli());
    }

    /** 快通道：只处理到期的提醒，1 秒一跳，延迟远低于轮询。 */
    @Scheduled(fixedDelayString = "${cabinet.scheduler.zset-interval-ms:1000}",
            initialDelayString = "${cabinet.scheduler.zset-interval-ms:1000}")
    @SuppressWarnings("unchecked")
    public void drain() {
        List<String> due = redis.execute(TAKE, List.of(ZSET_KEY),
                String.valueOf(System.currentTimeMillis()), String.valueOf(50));
        if (due != null && !due.isEmpty()) {
            log.debug("ZSet 到期提醒 {} 条，交给 DB 抢占判定", due.size());
            tasks.runDue();
        }
    }

    /** 保底慢轮询：提醒丢了也能被扫到，只是慢。 */
    @Scheduled(fixedDelayString = "${cabinet.scheduler.poll-interval-ms:60000}",
            initialDelayString = "${cabinet.scheduler.poll-interval-ms:60000}")
    public void fallbackPoll() {
        try {
            tasks.runDue();
        } catch (RuntimeException e) {
            log.error("ZSet 兜底轮询异常，下一轮继续", e);
        }
    }

    @Override
    public String mode() {
        return "zset";
    }
}
