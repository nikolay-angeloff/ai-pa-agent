package com.expense.facade.document.entity;

public enum DocumentStatus {
    RAW,
    EXTRACTED,
    AWAITING_REVIEW,  // agent paused for HITL; agentThreadId holds the resume handle
    SKIPPED,          // unknown document type — not an expense
    FAILED
}
