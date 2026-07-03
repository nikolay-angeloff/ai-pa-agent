package com.expense.facade.expense.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** Flattened read-only view returned by /expenses/search. */
public record ExpenseView(
        UUID id,
        String merchant,
        BigDecimal amount,
        String currency,
        LocalDate expenseDate,
        String category,
        BigDecimal confidence,
        Double score
) {}
