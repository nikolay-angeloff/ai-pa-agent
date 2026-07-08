package com.expense.facade.agent.dto;

import java.util.Map;

/** Matches agents/main.py ResumeRequest (snake_case). */
public record AgentResumeRequest(
        boolean approved,
        Map<String, Object> fields
) {}
