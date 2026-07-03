package com.expense.facade.config;

import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Collections.Distance;
import io.qdrant.client.grpc.Collections.VectorParams;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class QdrantCollectionInitializer {

    private static final Logger log = LoggerFactory.getLogger(QdrantCollectionInitializer.class);
    public static final String COLLECTION = "expenses";
    public static final int VECTOR_SIZE = 1536;   // text-embedding-3-small

    private final QdrantClient qdrantClient;

    public QdrantCollectionInitializer(QdrantClient qdrantClient) {
        this.qdrantClient = qdrantClient;
    }

    @PostConstruct
    public void init() throws Exception {
        List<String> existing = qdrantClient.listCollectionsAsync().get();
        if (existing.contains(COLLECTION)) {
            log.info("Qdrant collection '{}' already exists", COLLECTION);
            return;
        }
        qdrantClient.createCollectionAsync(
                COLLECTION,
                VectorParams.newBuilder()
                        .setSize(VECTOR_SIZE)
                        .setDistance(Distance.Cosine)
                        .build()
        ).get();
        log.info("Created Qdrant collection '{}' (size={}, distance=Cosine)", COLLECTION, VECTOR_SIZE);
    }
}
