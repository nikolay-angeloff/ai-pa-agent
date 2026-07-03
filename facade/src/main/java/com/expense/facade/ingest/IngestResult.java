package com.expense.facade.ingest;

public record IngestResult(int downloaded, int skipped, int failed) {}
