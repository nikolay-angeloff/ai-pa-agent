package com.expense.facade.extraction;

import java.util.UUID;

public record ExtractionDetail(
        UUID documentId,
        String status,          // "extracted" | "failed" | "skipped"
        ExtractionResult result // null when status != extracted
) {}
