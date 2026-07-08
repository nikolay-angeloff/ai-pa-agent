package com.expense.facade.insight;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Daily anomaly scan. Cron is configurable (app.insight.cron) so it can be
 * tightened for local testing without a code change — default is 08:00
 * server time, once a day.
 */
@Component
public class InsightScheduler {

    private static final Logger log = LoggerFactory.getLogger(InsightScheduler.class);

    private final InsightService insightService;

    public InsightScheduler(InsightService insightService) {
        this.insightService = insightService;
    }

    @Scheduled(cron = "${app.insight.cron:0 0 8 * * *}")
    public void scheduledScan() {
        log.info("Running scheduled insight scan");
        insightService.run();
    }
}
