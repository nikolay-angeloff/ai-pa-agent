package com.expense.facade.expense;

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

    public QueryController(QueryService queryService) {
        this.queryService = queryService;
    }

    /**
     * Aggregates expenses in the given date range.
     * Example: GET /expenses/summary?from=2024-01-01&to=2024-03-31
     */
    @GetMapping("/summary")
    public ResponseEntity<SummaryResponse> summary(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

        return ResponseEntity.ok(queryService.summarize(from, to));
    }

    /**
     * Semantic search over expenses via Qdrant.
     * Example: GET /expenses/search?q=grocery+shopping&limit=5
     */
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
