package com.expense.facade.expense.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Fire-and-forget notifier: tells the webhook service's WebSocket channel
 * about a newly recognized expense (Phase 4 prep — no dashboard consumes
 * this yet, but the channel needs a real trigger to be testable).
 *
 * Best-effort by design: a webhook/dashboard outage must never fail expense
 * ingestion, which is why every exception here is swallowed and logged.
 */
@Component
public class WebhookEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(WebhookEventPublisher.class);

    private final String webhookUrl;
    private final String eventsSecret;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public WebhookEventPublisher(@Value("${webhook.url:http://webhook:3000}") String webhookUrl,
                                 @Value("${events.secret:}") String eventsSecret,
                                 ObjectMapper objectMapper) {
        this.webhookUrl = webhookUrl;
        this.eventsSecret = eventsSecret;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(3))
                .build();
    }

    public void publishExpenseCreated(Object payload) {
        publish("/events/expense-created", payload);
    }

    /** payload = {"insights": [...]} — webhook fans it out to WS + Telegram. */
    public void publishInsight(Object payload) {
        publish("/events/insight", payload);
    }

    private void publish(String path, Object payload) {
        try {
            String json = objectMapper.writeValueAsString(payload);
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(webhookUrl + path))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(3))
                    .POST(HttpRequest.BodyPublishers.ofString(json));
            if (eventsSecret != null && !eventsSecret.isBlank()) {
                builder.header("X-Internal-Secret", eventsSecret);
            }
            HttpResponse<String> resp = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 400) {
                log.warn("Webhook event publish to {} returned {}: {}", path, resp.statusCode(), resp.body());
            }
        } catch (Exception e) {
            log.warn("Webhook event publish to {} failed (non-fatal): {}", path, e.getMessage());
        }
    }
}
