package com.expense.facade.extraction.controller;

import com.expense.facade.agent.client.AgentClient;
import com.expense.facade.agent.dto.AgentProcessRequest;
import com.expense.facade.agent.dto.AgentProcessResponse;
import com.expense.facade.document.entity.Document;
import com.expense.facade.document.entity.DocumentStatus;
import com.expense.facade.document.repository.DocumentRepository;
import com.expense.facade.extraction.dto.ExtractionDetail;
import com.expense.facade.extraction.service.AgentResponseProcessor;
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

    private final AgentClient agentClient;
    private final DocumentRepository documentRepository;
    private final AgentResponseProcessor agentResponseProcessor;

    public ExtractionController(AgentClient agentClient,
                                DocumentRepository documentRepository,
                                AgentResponseProcessor agentResponseProcessor) {
        this.agentClient = agentClient;
        this.documentRepository = documentRepository;
        this.agentResponseProcessor = agentResponseProcessor;
    }

    /**
     * Processes every document with status=RAW via the LangGraph agent.
     * The agent classifies + extracts; AgentResponseProcessor maps the
     * result back to Document fields (facade is the single writer).
     */
    @PostMapping("/run")
    @Transactional
    public ResponseEntity<List<ExtractionDetail>> run() {
        List<Document> rawDocs = documentRepository.findByStatus(DocumentStatus.RAW);
        List<ExtractionDetail> results = new ArrayList<>();

        for (Document doc : rawDocs) {
            try {
                AgentProcessRequest req = new AgentProcessRequest(
                        doc.getId().toString(),
                        doc.getRawStoragePath(),
                        doc.getSubject(),
                        doc.getSender()
                );
                AgentProcessResponse resp = agentClient.process(req);
                results.add(agentResponseProcessor.apply(doc, resp));
            } catch (Exception e) {
                log.error("Extraction failed for doc {}: {}", doc.getId(), e.getMessage());
                doc.setStatus(DocumentStatus.FAILED);
                documentRepository.save(doc);
                results.add(new ExtractionDetail(doc.getId(), "failed", null));
            }
        }

        return ResponseEntity.ok(results);
    }
}
