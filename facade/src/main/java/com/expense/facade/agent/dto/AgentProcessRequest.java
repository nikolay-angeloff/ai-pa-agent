package com.expense.facade.agent.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Matches agents/main.py ProcessRequest (snake_case). */
public record AgentProcessRequest(
        @JsonProperty("document_id")     String documentId,
        @JsonProperty("raw_storage_path") String rawStoragePath,
        String subject,
        String sender
) {}
