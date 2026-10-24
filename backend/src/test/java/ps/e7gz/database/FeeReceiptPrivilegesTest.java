package ps.e7gz.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static ps.e7gz.support.PostgresTestContainer.ADMIN_RUNTIME;
import static ps.e7gz.support.PostgresTestContainer.APP_RUNTIME;
import static ps.e7gz.support.PostgresTestContainer.DATABASE;

import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.postgresql.Driver;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import ps.e7gz.support.IntegrationTest;
import ps.e7gz.support.PostgresTestContainer;

/**
 * v1.1.38 (E7-66, Product Owner decision 23 Oct 2026): {@code loyalty.record_fee_receipt} is deferred until the payment
 * gateway (ADR-018). A receipt turns a refund decision into Wallet money, so neither runtime role may record one; the
 * other Booking Fee producers stay callable. Checked against the database migrated by our migrations and security
 * baseline.
 */
class FeeReceiptPrivilegesTest extends IntegrationTest {

    static final String RECEIPT = "loyalty.record_fee_receipt(uuid,numeric,text,text,timestamp with time zone)";

    @ParameterizedTest
    @ValueSource(strings = {APP_RUNTIME, ADMIN_RUNTIME})
    void noRuntimeRoleMayRecordAFeeReceipt(String role) {
        JdbcClient db = connect(role);

        assertThat(executable(db, role, RECEIPT)).isFalse();
        assertThatThrownBy(() -> db.sql("SELECT loyalty.record_fee_receipt(:booking, 5.00, 'P', 'R', now())")
                        .param("booking", UUID.randomUUID()).query().singleRow())
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("permission denied");
    }

    @ParameterizedTest
    @ValueSource(strings = {APP_RUNTIME, ADMIN_RUNTIME})
    void theRefundDecisionAndItsSettlementStayCallable(String role) {
        JdbcClient db = connect(role);

        // every terminal Online Booking still needs its decision (loyalty.assert_booking)
        assertThat(executable(db, role, "loyalty.record_refund(uuid,uuid,uuid)")).isTrue();
        assertThat(executable(db, role, "loyalty.settle_refund(uuid)")).isTrue();
    }

    private static boolean executable(JdbcClient db, String role, String function) {
        return db.sql("SELECT has_function_privilege(:role, CAST(:function AS regprocedure), 'EXECUTE')")
                .param("role", role).param("function", function).query(Boolean.class).single();
    }

    private static JdbcClient connect(String role) {
        return JdbcClient.create(new SimpleDriverDataSource(
                new Driver(), PostgresTestContainer.jdbcUrl(DATABASE), role, role));
    }
}
