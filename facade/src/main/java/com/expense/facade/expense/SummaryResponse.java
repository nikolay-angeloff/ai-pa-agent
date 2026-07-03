package com.expense.facade.expense;

import java.math.BigDecimal;
import java.util.List;

public record SummaryResponse(
        BigDecimal grandTotal,
        List<CategorySummary> byCategory
) {}
