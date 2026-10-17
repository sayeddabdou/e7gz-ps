package ps.e7gz.admin.api;

/**
 * Alert severity (arch 11 section 5.4; PO decision 4 Oct 2026, E7-32 review). Stored as its name in the open-text
 * {@code admin.admin_alert.severity}, which also holds the abuse signal's {@code RISK}.
 */
public enum AdminAlertSeverity {
    SEV_1,
    SEV_2,
    SEV_3
}
