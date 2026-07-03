package com.expense.facade.expense;

public record IngestSummary(int ingested, int skipped, int failed) {}
