package com.wherelee.cabinet;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 暂存柜 SaaS 底座启动类。
 *
 * <p>分层约定（详见 docs/架构约定.md）：
 * <ul>
 *   <li>{@code interfaces} 对外接口层，按端划分 admin / mini，两端互不共用 Controller</li>
 *   <li>{@code application} 用例编排与事务边界</li>
 *   <li>{@code domain} 实体、枚举、领域服务、仓储接口</li>
 *   <li>{@code infrastructure} mapper、缓存、消息、外部 SDK、多租户等技术实现</li>
 *   <li>{@code common} 与业务无关的通用能力（响应体、异常、链路、注解）</li>
 * </ul>
 */
@SpringBootApplication
public class CabinetServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(CabinetServerApplication.class, args);
    }
}
