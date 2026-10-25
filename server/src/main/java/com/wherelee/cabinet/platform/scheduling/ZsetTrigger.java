package com.wherelee.cabinet.platform.scheduling;

import com.wherelee.cabinet.platform.scheduling.DelayReminder;
import com.wherelee.cabinet.platform.scheduling.DelayTaskService;
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
