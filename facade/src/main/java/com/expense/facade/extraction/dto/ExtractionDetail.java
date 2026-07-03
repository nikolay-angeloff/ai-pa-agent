package com.expense.facade.extraction.dto;

import java.util.UUID;

public record ExtractionDetail(
        UUID documentId,
        String status,
        ExtractionResult result
) {}
