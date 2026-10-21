package com.wherelee.cabinet.funds.api;

/**
 * 充值入口（用户端）。
 *
 * <p>为什么只有一行：BFF 只需要“给这个人加钱”这一个动作，通道选择、幂等、入账事务
 * 都是资金服务内部的事。把服务实现类直接注入控制器，会让接口层随资金内部拆分而改包名。
 */
public interface RechargePort {

    RechargeView recharge(Long customerId, long points);
}
