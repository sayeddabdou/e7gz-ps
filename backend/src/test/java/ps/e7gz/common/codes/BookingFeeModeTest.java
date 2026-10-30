package ps.e7gz.common.codes;

import static org.assertj.core.api.Assertions.assertThat;
import static ps.e7gz.support.PostgresTestContainer.DATABASE;
import static ps.e7gz.support.PostgresTestContainer.MIGRATOR;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.postgresql.Driver;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import ps.e7gz.support.IntegrationTest;
import ps.e7gz.support.PostgresTestContainer;

/**
 * PCR-010 (v1.1.40): {@link BookingFeeMode} is exactly the allowed values of the {@code booking.fee_mode} configuration
 * key - a registry list, not a {@code CHECK}, so {@link ClosedValueContract} cannot see it - and the launch value is
 * PER_BOOKING.
 */
class BookingFeeModeTest extends IntegrationTest {

    static final JdbcClient OWNER = JdbcClient.create(new SimpleDriverDataSource(new Driver(),
            PostgresTestContainer.jdbcUrl(DATABASE), MIGRATOR, MIGRATOR));

    @Test
    void theEnumIsTheConfigurationKeysAllowedValues() {
        assertThat(OWNER.sql("""
                        SELECT jsonb_array_elements_text(allowed_values) FROM config.configuration_key
                         WHERE key = 'booking.fee_mode'""")
                .query(String.class).list())
                .containsExactlyElementsOf(Arrays.stream(BookingFeeMode.values()).map(Enum::name).toList());
        assertThat(OWNER.sql("""
                        SELECT new_value #>> '{}' FROM config.configuration_version
                         WHERE key = 'booking.fee_mode' AND effective_from = '-infinity'""")
                .query(String.class).single()).isEqualTo(BookingFeeMode.PER_BOOKING.name());
    }
}
