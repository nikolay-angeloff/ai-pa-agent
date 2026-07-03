package com.expense.facade.extraction.controller;

import com.expense.facade.document.entity.Document;
import com.expense.facade.document.entity.DocumentStatus;
import com.expense.facade.document.entity.DocumentType;
import com.expense.facade.document.repository.DocumentRepository;
import com.expense.facade.extraction.dto.ExtractionDetail;
import com.expense.facade.extraction.dto.ExtractionResult;
import com.expense.facade.extraction.service.ExtractionService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/extract")
public class ExtractionController {

    private static final Logger log = LoggerFactory.getLogger(ExtractionController.class);

    private final ExtractionService extractionService;
    private final DocumentRepository documentRepository;
    private final ObjectMapper objectMapper;

    public ExtractionController(ExtractionService extractionService,
                                DocumentRepository documentRepository,
                                ObjectMapper objectMapper) {
        this.extractionService = extractionService;
        this.documentRepository = documentRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Processes every document with status=RAW. Updates status + type in Postgres.
     * Expense persistence and Qdrant embedding happen in POST /expenses/ingest.
     */
    @PostMapping("/run")
    @Transactional
    public ResponseEntity<List<ExtractionDetail>> run() {
        List<Document> rawDocs = documentRepository.findByStatus(DocumentStatus.RAW);
        List<ExtractionDetail> results = new ArrayList<>();

        for (Document doc : rawDocs) {
            try {
                ExtractionResult result = extractionService.extract(doc);

                doc.setStatus(DocumentStatus.EXTRACTED);
                doc.setType(toDocumentType(result.documentType()));
                doc.setRawText(buildEmbeddingText(result));
                try {
                    doc.setExtractedData(objectMapper.writeValueAsString(result));
                } catch (JsonProcessingException ex) {
                    log.warn("Could not serialize extraction result for doc {}", doc.getId());
                }
                documentRepository.save(doc);

                log.info("Extracted doc {} → type={} merchant={} amount={} confidence={}",
                        doc.getId(), result.documentType(), result.merchant(),
                        result.amount(), result.confidence());

                results.add(new ExtractionDetail(doc.getId(), "extracted", result));

            } catch (Exception e) {
                log.error("Extraction failed for doc {}: {}", doc.getId(), e.getMessage());
                doc.setStatus(DocumentStatus.FAILED);
                documentRepository.save(doc);
                results.add(new ExtractionDetail(doc.getId(), "failed", null));
            }
        }

        return ResponseEntity.ok(results);
    }

    private DocumentType toDocumentType(String raw) {
        if (raw == null) return DocumentType.UNKNOWN;
        return switch (raw.toLowerCase()) {
            case "receipt" -> DocumentType.RECEIPT;
            case "invoice" -> DocumentType.INVOICE;
            default        -> DocumentType.UNKNOWN;
        };
    }

    /** Prose snippet stored on Document.rawText; used as embedding input in /expenses/ingest. */
    private String buildEmbeddingText(ExtractionResult r) {
        return "%s at %s for %s %s on %s".formatted(
                r.documentType(), r.merchant(), r.amount(), r.currency(), r.date());
    }
}
