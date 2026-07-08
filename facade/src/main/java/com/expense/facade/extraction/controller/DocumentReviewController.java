package com.expense.facade.extraction.controller;

import com.expense.facade.agent.client.AgentClient;
import com.expense.facade.agent.dto.AgentProcessResponse;
import com.expense.facade.document.entity.Document;
import com.expense.facade.document.entity.DocumentStatus;
import com.expense.facade.document.repository.DocumentRepository;
import com.expense.facade.expense.service.ExpensePersistenceService;
import com.expense.facade.extraction.dto.ApproveRequest;
import com.expense.facade.extraction.dto.DocumentReviewView;
import com.expense.facade.extraction.dto.ExtractionDetail;
import com.expense.facade.extraction.dto.ExtractionResult;
import com.expense.facade.extraction.service.AgentResponseProcessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * HITL review queue — documents the agent paused on (low extraction
 * confidence, see DocumentStatus.AWAITING_REVIEW) waiting for a human
 * decision from the dashboard before facade persists them as an Expense.
 */
@RestController
@RequestMapping("/documents")
public class DocumentReviewController {

    private static final Logger log = LoggerFactory.getLogger(DocumentReviewController.class);

    private final DocumentRepository documentRepository;
    private final AgentClient agentClient;
    private final AgentResponseProcessor agentResponseProcessor;
    private final ExpensePersistenceService expensePersistenceService;
    private final ObjectMapper objectMapper;

    public DocumentReviewController(DocumentRepository documentRepository,
                                    AgentClient agentClient,
                                    AgentResponseProcessor agentResponseProcessor,
                                    ExpensePersistenceService expensePersistenceService,
                                    ObjectMapper objectMapper) {
        this.documentRepository = documentRepository;
        this.agentClient = agentClient;
        this.agentResponseProcessor = agentResponseProcessor;
        this.expensePersistenceService = expensePersistenceService;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/awaiting-review")
    public ResponseEntity<List<DocumentReviewView>> awaitingReview() {
        List<DocumentReviewView> views = documentRepository.findByStatus(DocumentStatus.AWAITING_REVIEW)
                .stream()
                .map(this::toReviewView)
                .toList();
        return ResponseEntity.ok(views);
    }

    /**
     * Accepts the agent's extraction (or human-corrected fields) and resumes
     * the paused LangGraph thread. On success this immediately triggers
     * ingestExtracted() so the approved expense lands in Postgres + Qdrant
     * right away instead of waiting for the next batch /expenses/ingest run.
     */
    @PostMapping("/{id}/approve")
    @Transactional
    public ResponseEntity<ExtractionDetail> approve(@PathVariable UUID id,
                                                     @RequestBody(required = false) ApproveRequest req) {
        Document doc = requireAwaitingReview(id);

        Map<String, Object> fields = (req != null && !req.isEmpty()) ? toFieldsMap(req) : null;
        AgentProcessResponse resp = agentClient.resume(doc.getAgentThreadId(), true, fields);
        ExtractionDetail detail = agentResponseProcessor.apply(doc, resp);

        if ("extracted".equals(detail.status())) {
            expensePersistenceService.ingestExtracted();
        }
        return ResponseEntity.ok(detail);
    }

    /**
     * Rejects the extraction. The graph is still resumed (approved=false) so
     * its checkpoint reaches END rather than staying interrupted forever, but
     * the document is always marked SKIPPED regardless of what the agent
     * returns — a human "no" must never result in persistence.
     */
    @PostMapping("/{id}/reject")
    @Transactional
    public ResponseEntity<ExtractionDetail> reject(@PathVariable UUID id) {
        Document doc = requireAwaitingReview(id);

        agentClient.resume(doc.getAgentThreadId(), false, null);
        doc.setStatus(DocumentStatus.SKIPPED);
        documentRepository.save(doc);
        log.info("Doc {} rejected by human review", doc.getId());

        return ResponseEntity.ok(new ExtractionDetail(doc.getId(), "skipped", null));
    }

    private Document requireAwaitingReview(UUID id) {
        Document doc = documentRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "document not found"));
        if (doc.getStatus() != DocumentStatus.AWAITING_REVIEW) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "document is not awaiting review (status=" + doc.getStatus() + ")");
        }
        return doc;
    }

    private Map<String, Object> toFieldsMap(ApproveRequest r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("document_type", r.documentType());
        m.put("merchant", r.merchant());
        m.put("amount", r.amount());
        m.put("currency", r.currency());
        m.put("date", r.date());
        return m;
    }

    private DocumentReviewView toReviewView(Document doc) {
        ExtractionResult fields = null;
        if (doc.getExtractedData() != null) {
            try {
                fields = objectMapper.readValue(doc.getExtractedData(), ExtractionResult.class);
            } catch (Exception e) {
                log.warn("Could not deserialize extractedData for doc {}", doc.getId());
            }
        }
        return new DocumentReviewView(doc.getId(), doc.getSubject(), doc.getSender(), doc.getReceivedAt(), fields);
    }
}
