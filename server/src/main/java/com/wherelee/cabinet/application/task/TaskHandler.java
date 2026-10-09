package com.wherelee.cabinet.application.task;

import com.wherelee.cabinet.domain.entity.BizDelayTask;
import com.wherelee.cabinet.domain.enums.TaskType;

/**
 * 任务处理器 SPI。
 *
 * <p>约定：实现必须<b>自身可重入</b>（同一任务被重复执行不能造成二次伤害）。
 * 租约把"同时只有一个执行者"保证得很好，但保证不了"一生只执行一次"——
 * 业务在提交前崩溃、租约过期被接管，就会重跑。所以 handler 里的每一步都靠条件更新/唯一索引收口，
 * 而不是靠"我应该不会被重复调用"这个假设。
 */
public interface TaskHandler {

    TaskType type();

    /**
     * 是否把“执行 + markDone”关在同一个事务里（默认 true）。
     *
     * <p>默认开是因为大多数 handler 都是“纯写库”：同事务能让“业务提交了但状态没落成 DONE”
     * 与“状态落成了但业务回滚”两种半现场都不存在。
     *
     * <p><b>但一个要等外部回执的 handler 必须标 false</b>：外层一开事务，内部“等回执不开事务”
     * 的拆法就被外层重新合上了（第 9 刀量过的连接池瓶颈会直接复发）。代价显式承担：
     * <b>标 false 的 handler 必须完全可重入</b>——每一步都是条件更新/幂等键，
     * 因为“业务提交成功、markDone 失败”时下一轮会真的重跑一遍。
     */
    default boolean transactional() {
        return true;
    }

    /**
     * @return 是否需要再排下一轮。周期型任务返回 true；一次性任务（释放、退押金）返回 false。
     *
     * <p>为什么由 handler 回答而不是自己调调度器：handler 反过来注入 {@code DelayTaskService}
     * 会构成真实循环依赖（worker 收集 handler），那不只是一个装配报错——它说明职责错位：
     * <b>“干完要不要再干”是业务的判断，“怎么再排上”是调度的职责</b>，两者分开才不会互相依赖。
     */
    boolean handle(BizDelayTask task);
}
