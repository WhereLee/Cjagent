package com.wherelee.cabinet.platform.mq;

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
