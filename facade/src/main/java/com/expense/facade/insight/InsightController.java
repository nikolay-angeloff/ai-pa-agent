package com.expense.facade.insight;

import com.expense.facade.agent.dto.AgentInsightResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/insights")
public class InsightController {

    private final InsightService insightService;

    public InsightController(InsightService insightService) {
        this.insightService = insightService;
    }

    /** POST /insights/run — manual trigger (dashboard "scan now" button); the
     * scheduler (InsightScheduler) calls the same service on a cron. */
    @PostMapping("/run")
    public ResponseEntity<AgentInsightResponse> run() {
        AgentInsightResponse resp = insightService.run();
        if (resp.error() != null && !resp.error().isBlank()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(resp);
        }
        return ResponseEntity.ok(resp);
    }
}
