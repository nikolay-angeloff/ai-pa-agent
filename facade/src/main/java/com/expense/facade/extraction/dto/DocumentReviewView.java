package com.expense.facade.extraction.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/** A Document awaiting HITL review, with the agent's low-confidence extraction. */
public record DocumentReviewView(
        UUID id,
        String subject,
        String sender,
        OffsetDateTime receivedAt,
        ExtractionResult extractedFields
) {}
