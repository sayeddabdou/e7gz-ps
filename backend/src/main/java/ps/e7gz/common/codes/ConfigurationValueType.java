package ps.e7gz.common.codes;

import io.swagger.v3.oas.annotations.media.Schema;

/** Value type of a configuration key - {@code ck_configuration_key_values} (value_type). */
@Schema(enumAsRef = true)
public enum ConfigurationValueType {
    INTEGER,
    NUMERIC,
    BOOLEAN,
    TEXT
}
