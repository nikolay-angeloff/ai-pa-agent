package com.expense.facade.ingest.controller;

import com.expense.facade.ingest.dto.IngestResult;
import com.expense.facade.ingest.service.IngestService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/ingest")
public class IngestController {

    private final IngestService ingestService;

    public IngestController(IngestService ingestService) {
        this.ingestService = ingestService;
    }

    @PostMapping("/run")
    public ResponseEntity<IngestResult> run() {
        return ResponseEntity.ok(ingestService.run());
    }
}
