package com.wherelee.cabinet.application.storage;

import com.wherelee.cabinet.domain.enums.SizeType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 兜底分配器：当策略是 {@code prealloc}（没有同步分配器）时占住 bean 位置。
 *
 * <p>存在的意义是<b>把“配置与调用路径不一致”变成一次明确失败</b>，而不是启动期
 * {@code NoSuchBeanDefinitionException} 或更糟的静默降级。prealloc 的分配发生在
 * {@code AsyncStorageOrderAppService}（Redis 弹出 + 消息落库），同步路径根本不该被触达；
 * 真被触达说明有人改了路由却没改这里，那就该炸。
 *
 * <p>用 {@code @ConditionalOnProperty} 而不是 {@code @ConditionalOnMissingBean}：
 * 后者用在组件扫描的类上求值顺序不确定（它是给自动配置里的 @Bean 方法用的），
 * 按配置值条件化才是确定性的。
 */
@Component
@ConditionalOnProperty(name = "cabinet.alloc.strategy", havingValue = "prealloc")
public class UnavailableSlotAllocator implements SlotAllocator {

    @Override
    public AllocatedSlot allocate(Long cabinetId, SizeType required, Long orderId) {
        throw new IllegalStateException(
                "strategy=prealloc 时不存在同步分配器：分配应由 AsyncStorageOrderAppService 完成");
    }

    @Override
    public String strategy() {
        return "prealloc";
    }
}
