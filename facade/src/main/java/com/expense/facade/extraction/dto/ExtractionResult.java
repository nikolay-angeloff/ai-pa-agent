package com.expense.facade.extraction.dto;

import java.math.BigDecimal;

/**
 * LLM-extracted fields from a receipt/invoice. date is a raw string ("YYYY-MM-DD")
 * to avoid Jackson/LocalDate wiring complexity; parsed to LocalDate in Step 4.
 */
public record ExtractionResult(
        String documentType,
        String merchant,
        BigDecimal amount,
        String currency,
        String date,
        BigDecimal confidence
) {}
