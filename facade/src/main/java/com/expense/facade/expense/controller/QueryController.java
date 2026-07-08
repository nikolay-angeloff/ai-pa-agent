package com.expense.facade.expense.controller;

import com.expense.facade.agent.client.AgentClient;
import com.expense.facade.agent.dto.AgentQueryResponse;
import com.expense.facade.expense.dto.ExpenseView;
import com.expense.facade.expense.dto.QueryRequest;
import com.expense.facade.expense.dto.QueryResponse;
import com.expense.facade.expense.dto.SummaryResponse;
import com.expense.facade.expense.service.QueryService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/expenses")
public class QueryController {

    private final QueryService queryService;
    private final AgentClient agentClient;

    public QueryController(QueryService queryService, AgentClient agentClient) {
        this.queryService = queryService;
        this.agentClient = agentClient;
    }

    /** GET /expenses/summary?from=2024-01-01&to=2024-03-31 */
    @GetMapping("/summary")
    public ResponseEntity<SummaryResponse> summary(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

        return ResponseEntity.ok(queryService.summarize(from, to));
    }

    /**
     * GET /expenses?from=2024-01-01&to=2024-03-31&page=0&size=50 — plain listing (newest
     * first) for the dashboard table. Unlike /search this has no semantic ranking, so
     * `score` is always null on the returned ExpenseView rows.
     */
    @GetMapping
    public ResponseEntity<List<ExpenseView>> list(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {

        return ResponseEntity.ok(queryService.list(from, to, page, size));
    }

    /**
     * POST /expenses/query  — natural-language question delegated to the LangGraph agent.
     * The agent routes to /expenses/summary or /expenses/search internally, then synthesises
     * a plain-language answer.
     */
    @PostMapping("/query")
    public ResponseEntity<QueryResponse> query(@RequestBody QueryRequest req) {
        AgentQueryResponse resp = agentClient.query(req.question());
        if (resp.error() != null && !resp.error().isBlank()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(new QueryResponse(null, resp.error()));
        }
        return ResponseEntity.ok(new QueryResponse(resp.answer(), null));
    }

    /** GET /expenses/search?q=grocery+shopping&limit=5 */
    @GetMapping("/search")
    public ResponseEntity<List<ExpenseView>> search(
            @RequestParam String q,
            @RequestParam(defaultValue = "10") int limit) {
        try {
            return ResponseEntity.ok(queryService.search(q, limit));
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Embedding or search failed: " + e.getMessage(), e);
        }
    }
}
