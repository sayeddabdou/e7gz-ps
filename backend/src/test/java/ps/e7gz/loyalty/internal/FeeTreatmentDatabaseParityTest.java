package ps.e7gz.loyalty.internal;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import ps.e7gz.common.codes.BookingTerminalReason;
import ps.e7gz.common.codes.FeeTreatment;
import ps.e7gz.loyalty.api.FeeQuery;
import ps.e7gz.support.IntegrationTest;

/**
 * E7-66 (PO decision 23 Oct 2026) - the Java switch and the database decide the same fee treatment for every terminal
 * reason. The database is the one that refunds ({@code loyalty.refund_basis} reads {@code
 * booking.expected_fee_treatment}); {@link FeeQuery} makes FR-PTS-16 a compile-time rule. A reason the database does
 * not know answers NULL there and fails here.
 */
class FeeTreatmentDatabaseParityTest extends IntegrationTest {

    @Autowired
    DataSource appDataSource;

    private JdbcClient db;

    @BeforeEach
    void connect() {
        db = JdbcClient.create(appDataSource);
    }

    @ParameterizedTest
    @EnumSource(BookingTerminalReason.class)
    void theSwitchAndTheDatabaseGiveTheSameTreatment(BookingTerminalReason reason) {
        String database = db.sql("SELECT booking.expected_fee_treatment(:reason)").param("reason", reason.name())
                .query(String.class).list().getFirst();

        assertThat(database).as("booking.expected_fee_treatment('%s')", reason).isNotNull();
        assertThat(FeeQuery.feeTreatmentFor(reason)).isEqualTo(FeeTreatment.valueOf(database));
    }
}
