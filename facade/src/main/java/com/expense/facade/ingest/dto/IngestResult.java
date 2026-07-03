package com.expense.facade.ingest.dto;

public record IngestResult(int downloaded, int skipped, int failed) {}
