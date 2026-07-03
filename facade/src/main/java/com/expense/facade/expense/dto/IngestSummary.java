package com.expense.facade.expense.dto;

public record IngestSummary(int ingested, int skipped, int failed) {}
