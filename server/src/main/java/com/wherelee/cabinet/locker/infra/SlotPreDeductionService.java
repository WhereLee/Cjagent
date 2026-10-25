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

@Service
public class SlotPreDeductionService {

    private static final String FREE_PREFIX = "cab:alloc:free:";

    private static final DefaultRedisScript<String> POP_FREE = load("lua/slot-prealloc.lua", String.class);

    private final StringRedisTemplate redis;

    public SlotPreDeductionService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    private static <T> DefaultRedisScript<T> load(String path, Class<T> resultType) {
        DefaultRedisScript<T> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(path));
        script.setResultType(resultType);
        return script;
    }

    public SlotPoolPort.Popped pop(Long cabinetId, List<SizeType> preference) {
        List<String> keys = new ArrayList<>(preference.size());
        for (SizeType size : preference) {
            keys.add(freeKey(cabinetId, size));
        }

        String hit = redis.execute(POP_FREE, keys, String.valueOf(preference.size()));
        if (hit == null) {
            return null;
        }

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

    public void addFree(Long cabinetId, SizeType size, Long slotId) {
        redis.opsForSet().add(freeKey(cabinetId, size), String.valueOf(slotId));
    }

    private String freeKey(Long cabinetId, SizeType size) {
        return FREE_PREFIX + cabinetId + ":" + size.name();
    }
}
