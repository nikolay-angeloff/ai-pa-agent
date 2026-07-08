package com.expense.facade.expense.service;

import com.expense.facade.config.QdrantCollectionInitializer;
import com.expense.facade.document.entity.Document;
import com.expense.facade.document.entity.DocumentStatus;
import com.expense.facade.document.repository.DocumentRepository;
import com.expense.facade.expense.client.WebhookEventPublisher;
import com.expense.facade.expense.dto.IngestSummary;
import com.expense.facade.expense.entity.Expense;
import com.expense.facade.expense.repository.ExpenseRepository;
import com.expense.facade.extraction.dto.ExtractionResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Points.PointStruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.qdrant.client.PointIdFactory.id;
import static io.qdrant.client.ValueFactory.nullValue;
import static io.qdrant.client.ValueFactory.value;
import static io.qdrant.client.VectorsFactory.vectors;

@Service
public class ExpensePersistenceService {

    private static final Logger log = LoggerFactory.getLogger(ExpensePersistenceService.class);

    private final DocumentRepository documentRepository;
    private final ExpenseRepository expenseRepository;
    private final EmbeddingModel embeddingModel;
    private final QdrantClient qdrantClient;
    private final ObjectMapper objectMapper;
    private final WebhookEventPublisher webhookEventPublisher;

    public ExpensePersistenceService(DocumentRepository documentRepository,
                                     ExpenseRepository expenseRepository,
                                     EmbeddingModel embeddingModel,
                                     QdrantClient qdrantClient,
                                     ObjectMapper objectMapper,
                                     WebhookEventPublisher webhookEventPublisher) {
        this.documentRepository = documentRepository;
        this.expenseRepository = expenseRepository;
        this.embeddingModel = embeddingModel;
        this.qdrantClient = qdrantClient;
        this.objectMapper = objectMapper;
        this.webhookEventPublisher = webhookEventPublisher;
    }

    @Transactional
    public IngestSummary ingestExtracted() {
        List<Document> docs = documentRepository.findByStatus(DocumentStatus.EXTRACTED);
        int ingested = 0, skipped = 0, failed = 0;

        for (Document doc : docs) {
            if (expenseRepository.existsByDocumentId(doc.getId())) {
                skipped++;
                continue;
            }
            try {
                ExtractionResult extraction = objectMapper.readValue(
                        doc.getExtractedData(), ExtractionResult.class);

                if (extraction.amount() == null || extraction.date() == null) {
                    log.warn("Doc {} missing amount or date — skipping", doc.getId());
                    skipped++;
                    continue;
                }

                Expense expense = buildExpense(doc, extraction);
                expenseRepository.save(expense);

                float[] vector = embeddingModel.embed(
                        doc.getRawText() != null ? doc.getRawText() : extraction.merchant());
                upsertToQdrant(expense, extraction, vector);

                log.info("Ingested expense {} (doc={} merchant={} amount={}{})",
                        expense.getId(), doc.getId(), extraction.merchant(),
                        extraction.amount(), extraction.currency());
                ingested++;

                webhookEventPublisher.publishExpenseCreated(Map.of(
                        "id",       expense.getId().toString(),
                        "merchant", extraction.merchant() != null ? extraction.merchant() : "",
                        "amount",   extraction.amount(),
                        "currency", expense.getCurrency(),
                        "date",     expense.getExpenseDate().toString()
                ));

            } catch (Exception e) {
                log.error("Failed to ingest doc {}: {}", doc.getId(), e.getMessage());
                failed++;
            }
        }

        return new IngestSummary(ingested, skipped, failed);
    }

    private Expense buildExpense(Document doc, ExtractionResult r) {
        Expense expense = new Expense();
        expense.setDocument(doc);
        expense.setMerchant(r.merchant());
        expense.setAmount(r.amount());
        expense.setCurrency(r.currency() != null ? r.currency() : "EUR");
        expense.setExpenseDate(parseDate(r.date()));
        expense.setConfidence(r.confidence());
        expense.setRawText(doc.getRawText());
        return expense;
    }

    private LocalDate parseDate(String raw) {
        try {
            return LocalDate.parse(raw);
        } catch (Exception e) {
            return LocalDate.now();
        }
    }

    private void upsertToQdrant(Expense expense, ExtractionResult r, float[] vector)
            throws Exception {
        List<Float> floats = new ArrayList<>(vector.length);
        for (float f : vector) floats.add(f);

        var payload = Map.of(
                "expense_id",  value(expense.getId().toString()),
                "document_id", value(expense.getDocument().getId().toString()),
                "merchant",    r.merchant()  != null ? value(r.merchant())            : nullValue(),
                "amount",      r.amount()    != null ? value(r.amount().doubleValue()) : nullValue(),
                "currency",    r.currency()  != null ? value(r.currency())             : nullValue(),
                "date",        r.date()      != null ? value(r.date())                 : nullValue()
        );

        PointStruct point = PointStruct.newBuilder()
                .setId(id(expense.getId()))
                .setVectors(vectors(floats))
                .putAllPayload(payload)
                .build();

        qdrantClient.upsertAsync(QdrantCollectionInitializer.COLLECTION, List.of(point), null).get();
    }
}
