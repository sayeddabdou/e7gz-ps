package ps.e7gz.common.codes;

import io.swagger.v3.oas.annotations.media.Schema;

/** Scheduled job status - {@code ck_scheduled_job_status}. */
@Schema(enumAsRef = true)
public enum ScheduledJobStatus {
    PENDING,
    PAUSED,
    LEASED,
    DONE,
    SUPERSEDED,
    FAILED
}
