package com.expense.facade.agent.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/** Matches agents/main.py ProcessResponse (snake_case). */
public record AgentProcessResponse(
        @JsonProperty("thread_id")        String threadId,
        @JsonProperty("document_type")    String documentType,
        @JsonProperty("extracted_fields") Map<String, Object> extractedFields,
        Double confidence,
        @JsonProperty("hitl_required")    boolean hitlRequired,
        String error
) {}
