package ps.e7gz.venue.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Operating Session generation ({@code e7gz.operating-sessions.*}).
 *
 * @param horizonDays how many operating days ahead sessions are materialized: a deployment setting, default 120 days,
 *                    not a Product booking limit (ADR-009, arch 01 §14 A2, PO decision 26 Sep 2026)
 * @param extendCron  when the worker extends every venue's horizon by the new day (nightly, Africa/Cairo)
 */
@ConfigurationProperties("e7gz.operating-sessions")
public record OperatingSessionProperties(
        @DefaultValue("120") int horizonDays,
        @DefaultValue("0 15 3 * * *") String extendCron) {

    public OperatingSessionProperties {
        if (horizonDays < 1) {
            throw new IllegalArgumentException("e7gz.operating-sessions.horizon-days must be at least 1");
        }
    }
}
