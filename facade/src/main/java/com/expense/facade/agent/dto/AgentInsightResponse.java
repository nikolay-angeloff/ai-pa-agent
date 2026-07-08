package com.expense.facade.agent.dto;

import java.util.List;

/** Matches agents/main.py InsightResponse. */
public record AgentInsightResponse(List<String> insights, String error) {}
