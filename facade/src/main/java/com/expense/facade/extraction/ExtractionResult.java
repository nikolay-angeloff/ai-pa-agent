package com.expense.facade.extraction;

import java.math.BigDecimal;

/**
 * LLM-extracted fields from a receipt/invoice. date is a raw string ("YYYY-MM-DD")
 * to avoid Jackson/LocalDate wiring complexity; parsed to LocalDate in Step 4.
 */
public record ExtractionResult(
        String documentType,   // "receipt" | "invoice" | "unknown"
        String merchant,
        BigDecimal amount,
        String currency,       // ISO 4217
        String date,           // "YYYY-MM-DD"
        BigDecimal confidence  // 0.000 – 1.000
) {}
