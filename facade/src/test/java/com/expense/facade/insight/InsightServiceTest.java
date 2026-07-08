package com.expense.facade.insight;

import com.expense.facade.agent.client.AgentClient;
import com.expense.facade.agent.dto.AgentInsightResponse;
import com.expense.facade.expense.client.WebhookEventPublisher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * InsightService is a thin trigger + fan-out layer (see class javadoc) — the
 * anomaly math itself lives in the Python agent svc and is covered there
 * (agents/tests/test_insight.py). These tests only verify the fan-out
 * decision: publish on non-empty insights, stay silent otherwise.
 */
@ExtendWith(MockitoExtension.class)
class InsightServiceTest {

    @Mock
    private AgentClient agentClient;

    @Mock
    private WebhookEventPublisher webhookEventPublisher;

    private InsightService insightService() {
        return new InsightService(agentClient, webhookEventPublisher);
    }

    @Test
    void publishesWhenInsightsFound() {
        List<String> insights = List.of("📈 Похарчи 200.00 през последните 7 дни — 2.0x над обичайното.");
        when(agentClient.insights()).thenReturn(new AgentInsightResponse(insights, null));

        AgentInsightResponse result = insightService().run();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(webhookEventPublisher).publishInsight(captor.capture());
        assertThat(captor.getValue()).containsEntry("insights", insights);
        assertThat(result.insights()).isEqualTo(insights);
        assertThat(result.error()).isNull();
    }

    @Test
    void doesNotPublishWhenNoInsightsFound() {
        when(agentClient.insights()).thenReturn(new AgentInsightResponse(List.of(), null));

        AgentInsightResponse result = insightService().run();

        verify(webhookEventPublisher, never()).publishInsight(org.mockito.ArgumentMatchers.any());
        assertThat(result.insights()).isEmpty();
    }

    @Test
    void doesNotPublishWhenInsightsListIsNull() {
        when(agentClient.insights()).thenReturn(new AgentInsightResponse(null, null));

        insightService().run();

        verify(webhookEventPublisher, never()).publishInsight(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void skipsPublishAndReturnsErrorWhenAgentScanFails() {
        when(agentClient.insights()).thenReturn(new AgentInsightResponse(null, "agent svc timed out"));

        AgentInsightResponse result = insightService().run();

        verify(webhookEventPublisher, never()).publishInsight(org.mockito.ArgumentMatchers.any());
        assertThat(result.error()).isEqualTo("agent svc timed out");
    }
}
