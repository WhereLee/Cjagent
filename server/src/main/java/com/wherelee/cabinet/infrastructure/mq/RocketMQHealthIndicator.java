package com.wherelee.cabinet.infrastructure.mq;

import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.remoting.protocol.body.ClusterInfo;
import org.apache.rocketmq.tools.admin.DefaultMQAdminExt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.AbstractHealthIndicator;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * RocketMQ 健康检查：走 namesrv 拉集群信息，确认 broker 真的注册上来了。
 *
 * <p>只做 TCP 连通（能连 9876）是不够的：namesrv 活着但 broker 没注册时，生产者发消息会失败。
 * 所以这里查集群信息，把 broker 列表暴露出来。
 *
 * <p>代价说明：每次健康检查都要 start/shutdown 一个 admin 客户端（约几十毫秒）。
 * 底座阶段自检接口是低频调用，可接受；若后续接入 K8s 高频 probe，
 * 应改为带 TTL 的缓存结果或复用长驻 admin 实例。
 */
@Component("rocketMQHealthIndicator")
@ConditionalOnProperty(name = "management.health.rocketmq.enabled", havingValue = "true", matchIfMissing = true)
public class RocketMQHealthIndicator extends AbstractHealthIndicator {

    private final String nameServer;

    public RocketMQHealthIndicator(@Value("${rocketmq.name-server}") String nameServer) {
        this.nameServer = nameServer;
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) {
        DefaultMQAdminExt admin = new DefaultMQAdminExt("cabinet-health-check");
        admin.setNamesrvAddr(nameServer);
        // 每次用独立 instanceName，避免与上一次或业务客户端的实例名冲突
        admin.setInstanceName("cabinet-health-" + System.nanoTime());
        try {
            admin.start();
            ClusterInfo clusterInfo = admin.examineBrokerClusterInfo();
            Map<String, Object> details = new HashMap<>();
            details.put("nameServer", nameServer);
            details.put("clusters", clusterInfo.getClusterAddrTable().keySet());
            details.put("brokers", clusterInfo.getBrokerAddrTable().keySet());
            builder.up().withDetails(details);
        } catch (MQClientException e) {
            builder.down()
                    .withDetail("nameServer", nameServer)
                    .withDetail("error", e.getErrorMessage())
                    .withDetail("responseCode", e.getResponseCode());
        } catch (Exception e) {
            builder.down().withDetail("nameServer", nameServer).withException(e);
        } finally {
            admin.shutdown();
        }
    }
}
