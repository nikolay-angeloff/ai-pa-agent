package com.expense.facade.extraction.dto;

import java.math.BigDecimal;

/**
 * Human corrections submitted from the HITL review UI. Send null (or all
 * fields null) to accept the agent's extraction as-is. Otherwise send the
 * FULL corrected set — the agent's resume endpoint replaces extracted_fields
 * wholesale rather than merging, so a partial correction would drop the
 * fields left out.
 */
public record ApproveRequest(
        String documentType,
        String merchant,
        BigDecimal amount,
        String currency,
        String date
) {
    public boolean isEmpty() {
        return documentType == null && merchant == null && amount == null
                && currency == null && date == null;
    }
}
