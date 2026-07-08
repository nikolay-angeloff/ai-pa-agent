package com.expense.facade.extraction.service;

import com.expense.facade.agent.dto.AgentProcessResponse;
import com.expense.facade.document.entity.Document;
import com.expense.facade.document.entity.DocumentStatus;
import com.expense.facade.document.entity.DocumentType;
import com.expense.facade.document.repository.DocumentRepository;
import com.expense.facade.extraction.dto.ExtractionDetail;
import com.expense.facade.extraction.dto.ExtractionResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Maps an AgentProcessResponse onto a Document and persists it. Shared by
 * the initial extraction run (POST /extract/run) and the HITL approve flow
 * (POST /documents/{id}/approve) — both call the same agent contract and
 * must land on the same Document/DocumentStatus transitions.
 */
@Service
public class AgentResponseProcessor {

    private static final Logger log = LoggerFactory.getLogger(AgentResponseProcessor.class);

    private final DocumentRepository documentRepository;
    private final ObjectMapper objectMapper;

    public AgentResponseProcessor(DocumentRepository documentRepository, ObjectMapper objectMapper) {
        this.documentRepository = documentRepository;
        this.objectMapper = objectMapper;
    }

    public ExtractionDetail apply(Document doc, AgentProcessResponse resp) {
        doc.setAgentThreadId(resp.threadId());

        if (resp.error() != null && !resp.error().isBlank()) {
            log.error("Agent error for doc {}: {}", doc.getId(), resp.error());
            doc.setStatus(DocumentStatus.FAILED);
            documentRepository.save(doc);
            return new ExtractionDetail(doc.getId(), "failed", null);
        }

        if (resp.hitlRequired()) {
            // Graph paused — persist what was extracted so far so the review
            // UI has something to show; dashboard resumes via /documents/{id}/approve.
            ExtractionResult partial = toExtractionResult(resp);
            doc.setStatus(DocumentStatus.AWAITING_REVIEW);
            saveExtractedData(doc, partial);
            documentRepository.save(doc);
            log.info("Doc {} awaiting HITL review (thread={})", doc.getId(), resp.threadId());
            return new ExtractionDetail(doc.getId(), "awaiting_review", partial);
        }

        if ("unknown".equals(resp.documentType())) {
            doc.setStatus(DocumentStatus.SKIPPED);
            documentRepository.save(doc);
            log.info("Doc {} skipped — not an expense document", doc.getId());
            return new ExtractionDetail(doc.getId(), "skipped", null);
        }

        ExtractionResult result = toExtractionResult(resp);
        doc.setStatus(DocumentStatus.EXTRACTED);
        doc.setType(toDocumentType(resp.documentType()));
        doc.setRawText(buildEmbeddingText(result));
        saveExtractedData(doc, result);
        documentRepository.save(doc);

        log.info("Extracted doc {} → type={} merchant={} amount={} conf={}",
                doc.getId(), result.documentType(), result.merchant(),
                result.amount(), result.confidence());
        return new ExtractionDetail(doc.getId(), "extracted", result);
    }

    private void saveExtractedData(Document doc, ExtractionResult result) {
        try {
            doc.setExtractedData(objectMapper.writeValueAsString(result));
        } catch (JsonProcessingException ex) {
            log.warn("Could not serialize extraction result for doc {}", doc.getId());
        }
    }

    private ExtractionResult toExtractionResult(AgentProcessResponse resp) {
        Map<String, Object> f = resp.extractedFields() != null ? resp.extractedFields() : Map.of();
        BigDecimal amount = null;
        if (f.get("amount") instanceof Number n) {
            amount = BigDecimal.valueOf(n.doubleValue());
        }
        BigDecimal confidence = resp.confidence() != null
                ? BigDecimal.valueOf(resp.confidence())
                : BigDecimal.ZERO;
        return new ExtractionResult(
                resp.documentType(),
                (String) f.get("merchant"),
                amount,
                (String) f.get("currency"),
                (String) f.get("date"),
                confidence
        );
    }

    private DocumentType toDocumentType(String raw) {
        if (raw == null) return DocumentType.UNKNOWN;
        return switch (raw.toLowerCase()) {
            case "receipt" -> DocumentType.RECEIPT;
            case "invoice" -> DocumentType.INVOICE;
            default        -> DocumentType.UNKNOWN;
        };
    }

    private String buildEmbeddingText(ExtractionResult r) {
        return "%s at %s for %s %s on %s".formatted(
                r.documentType(), r.merchant(), r.amount(), r.currency(), r.date());
    }
}
