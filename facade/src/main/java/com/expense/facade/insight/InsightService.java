package com.expense.facade.insight;

import com.expense.facade.agent.client.AgentClient;
import com.expense.facade.agent.dto.AgentInsightResponse;
import com.expense.facade.expense.client.WebhookEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Runs the agent svc's anomaly scan and, if it found anything, hands the
 * result to WebhookEventPublisher (WS + Telegram). Facade stays the only
 * writer/orchestrator here — the agent graph is arithmetic-only (see
 * agents/graph/nodes/insight.py), this service just triggers + fans out.
 */
@Service
public class InsightService {

    private static final Logger log = LoggerFactory.getLogger(InsightService.class);

    private final AgentClient agentClient;
    private final WebhookEventPublisher webhookEventPublisher;

    public InsightService(AgentClient agentClient, WebhookEventPublisher webhookEventPublisher) {
        this.agentClient = agentClient;
        this.webhookEventPublisher = webhookEventPublisher;
    }

    public AgentInsightResponse run() {
        AgentInsightResponse resp = agentClient.insights();

        if (resp.error() != null && !resp.error().isBlank()) {
            log.warn("Insight scan failed: {}", resp.error());
            return resp;
        }

        List<String> insights = resp.insights();
        if (insights != null && !insights.isEmpty()) {
            webhookEventPublisher.publishInsight(Map.of("insights", insights));
        }
        return resp;
    }
}
