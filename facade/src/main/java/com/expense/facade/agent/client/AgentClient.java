package com.expense.facade.agent.client;

import com.expense.facade.agent.dto.AgentInsightResponse;
import com.expense.facade.agent.dto.AgentProcessRequest;
import com.expense.facade.agent.dto.AgentProcessResponse;
import com.expense.facade.agent.dto.AgentQueryResponse;
import com.expense.facade.agent.dto.AgentResumeRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

// Java HttpClient defaults to HTTP/2; uvicorn drops request bodies on upgrade.
// Force HTTP/1.1 so requests are sent plaintext without negotiation.

@Component
public class AgentClient {

    private final String agentUrl;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public AgentClient(@Value("${agent.svc.url:http://agents:8000}") String agentUrl,
                       ObjectMapper objectMapper) {
        this.agentUrl   = agentUrl;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    public AgentProcessResponse process(AgentProcessRequest req) {
        return post("/agent/process", req, AgentProcessResponse.class);
    }

    public AgentQueryResponse query(String question) {
        return post("/agent/query", Map.of("question", question), AgentQueryResponse.class);
    }

    public AgentInsightResponse insights() {
        return post("/agent/insights", Map.of(), AgentInsightResponse.class);
    }

    /**
     * Resumes a graph paused at the hitl node (see Document.agentThreadId).
     * fields=null keeps the agent's own extracted values; a non-null map
     * REPLACES them wholesale (the Python hitl_node does not merge partial
     * dicts), so callers must send a complete field set when correcting.
     */
    public AgentProcessResponse resume(String threadId, boolean approved, Map<String, Object> fields) {
        return post("/agent/resume/" + threadId, new AgentResumeRequest(approved, fields), AgentProcessResponse.class);
    }

    private <T> T post(String path, Object body, Class<T> responseType) {
        try {
            String json = objectMapper.writeValueAsString(body);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(agentUrl + path))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 400) {
                throw new RuntimeException("Agent " + path + " returned " + resp.statusCode() + ": " + resp.body());
            }
            return objectMapper.readValue(resp.body(), responseType);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Agent call to " + path + " failed: " + e.getMessage(), e);
        }
    }
}
