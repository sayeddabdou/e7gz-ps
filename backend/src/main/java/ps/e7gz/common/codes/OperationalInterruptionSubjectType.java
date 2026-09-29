package ps.e7gz.common.codes;

import io.swagger.v3.oas.annotations.media.Schema;

/** Subject of an operational interruption record - {@code ck_operational_interruption_subject_type}. */
@Schema(enumAsRef = true)
public enum OperationalInterruptionSubjectType {
    BOOKING,
    MANUAL_BOOKING,
    TOURNAMENT
}
