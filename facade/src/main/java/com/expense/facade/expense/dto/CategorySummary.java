package com.expense.facade.expense.dto;

import java.math.BigDecimal;

/** Spring Data JPA projection — aliases in the native query must match getter names. */
public interface CategorySummary {
    String getCategory();
    BigDecimal getTotal();
    Long getCount();
}
