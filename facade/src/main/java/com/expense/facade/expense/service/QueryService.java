package com.expense.facade.expense.service;

import com.expense.facade.config.QdrantCollectionInitializer;
import com.expense.facade.expense.dto.CategorySummary;
import com.expense.facade.expense.dto.ExpenseView;
import com.expense.facade.expense.dto.SummaryResponse;
import com.expense.facade.expense.entity.Expense;
import com.expense.facade.expense.repository.ExpenseRepository;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Points.SearchPoints;
import io.qdrant.client.grpc.Points.ScoredPoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class QueryService {

    private static final Logger log = LoggerFactory.getLogger(QueryService.class);

    private final ExpenseRepository expenseRepository;
    private final EmbeddingModel embeddingModel;
    private final QdrantClient qdrantClient;

    public QueryService(ExpenseRepository expenseRepository,
                        EmbeddingModel embeddingModel,
                        QdrantClient qdrantClient) {
        this.expenseRepository = expenseRepository;
        this.embeddingModel = embeddingModel;
        this.qdrantClient = qdrantClient;
    }

    public SummaryResponse summarize(LocalDate from, LocalDate to) {
        List<CategorySummary> rows = expenseRepository.summarizeByDateRange(from, to);
        BigDecimal total = expenseRepository.sumByDateRange(from, to);
        return new SummaryResponse(total != null ? total : BigDecimal.ZERO, rows);
    }

    /** Plain (non-semantic) listing for the dashboard table — newest first. */
    public List<ExpenseView> list(LocalDate from, LocalDate to, int page, int size) {
        return expenseRepository
                .findByExpenseDateBetweenOrderByExpenseDateDesc(from, to, PageRequest.of(page, size))
                .stream()
                .map(e -> new ExpenseView(
                        e.getId(), e.getMerchant(), e.getAmount(),
                        e.getCurrency(), e.getExpenseDate(), e.getCategory(),
                        e.getConfidence(), null))
                .toList();
    }

    public List<ExpenseView> search(String query, int limit) throws Exception {
        float[] vector = embeddingModel.embed(query);

        List<Float> floats = new ArrayList<>(vector.length);
        for (float f : vector) floats.add(f);

        List<ScoredPoint> points = qdrantClient.searchAsync(
                SearchPoints.newBuilder()
                        .setCollectionName(QdrantCollectionInitializer.COLLECTION)
                        .addAllVector(floats)
                        .setLimit(limit)
                        .build()
        ).get();

        if (points.isEmpty()) return List.of();

        Map<UUID, Double> scores = points.stream().collect(Collectors.toMap(
                p -> UUID.fromString(p.getId().getUuid()),
                p -> (double) p.getScore()
        ));

        List<Expense> expenses = expenseRepository.findAllById(scores.keySet());

        return expenses.stream()
                .map(e -> new ExpenseView(
                        e.getId(), e.getMerchant(), e.getAmount(),
                        e.getCurrency(), e.getExpenseDate(), e.getCategory(),
                        e.getConfidence(), scores.get(e.getId())))
                .sorted(Comparator.comparingDouble(v -> -(v.score() != null ? v.score() : 0.0)))
                .toList();
    }
}
