package com.expense.facade.expense.controller;

import com.expense.facade.expense.dto.IngestSummary;
import com.expense.facade.expense.service.ExpensePersistenceService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/expenses")
public class ExpensePersistenceController {

    private final ExpensePersistenceService service;

    public ExpensePersistenceController(ExpensePersistenceService service) {
        this.service = service;
    }

    /**
     * Picks up EXTRACTED documents, creates Expense rows in Postgres,
     * generates embeddings via OpenAI, and upserts vectors into Qdrant.
     */
    @PostMapping("/ingest")
    public ResponseEntity<IngestSummary> ingest() {
        return ResponseEntity.ok(service.ingestExtracted());
    }
}
