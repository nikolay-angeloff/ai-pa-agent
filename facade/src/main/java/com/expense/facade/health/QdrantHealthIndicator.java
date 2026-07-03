package com.expense.facade.health;

import io.qdrant.client.QdrantClient;
import org.springframework.boot.actuate.health.AbstractHealthIndicator;
import org.springframework.boot.actuate.health.Health;
import org.springframework.stereotype.Component;

@Component
public class QdrantHealthIndicator extends AbstractHealthIndicator {

    private final QdrantClient qdrantClient;

    public QdrantHealthIndicator(QdrantClient qdrantClient) {
        this.qdrantClient = qdrantClient;
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) throws Exception {
        var reply = qdrantClient.healthCheckAsync().get();
        builder.up().withDetail("version", reply.getVersion());
    }
}
