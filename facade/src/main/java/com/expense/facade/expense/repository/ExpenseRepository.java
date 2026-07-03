package com.expense.facade.expense.repository;

import com.expense.facade.expense.dto.CategorySummary;
import com.expense.facade.expense.entity.Expense;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface ExpenseRepository extends JpaRepository<Expense, UUID> {

    boolean existsByDocumentId(UUID documentId);

    @Query(value = """
            SELECT COALESCE(category, 'uncategorized') AS category,
                   SUM(amount)                         AS total,
                   COUNT(*)                            AS count
            FROM   expense
            WHERE  expense_date BETWEEN :from AND :to
            GROUP  BY COALESCE(category, 'uncategorized')
            ORDER  BY total DESC
            """, nativeQuery = true)
    List<CategorySummary> summarizeByDateRange(@Param("from") LocalDate from,
                                               @Param("to")   LocalDate to);

    @Query("SELECT SUM(e.amount) FROM Expense e WHERE e.expenseDate BETWEEN :from AND :to")
    BigDecimal sumByDateRange(@Param("from") LocalDate from,
                              @Param("to")   LocalDate to);
}
