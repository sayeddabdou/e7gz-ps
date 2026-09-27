package ps.e7gz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import ps.e7gz.common.persistence.DataSourceConfig;
import ps.e7gz.support.IntegrationTest;

class E7gzApplicationTests extends IntegrationTest {

    @Autowired
    Flyway flyway;

    @Autowired
    DataSource appDataSource;

    @Autowired
    @Qualifier(DataSourceConfig.ADMIN_DATA_SOURCE)
    DataSource adminDataSource;

    @Test
    void startsAgainstTheMigratedDatabase() {
        JdbcTemplate app = new JdbcTemplate(appDataSource);
        assertThat(app.queryForObject("select current_user", String.class)).isEqualTo("e7gz_app_runtime");
        assertThat(app.queryForObject(
                "select count(*) from information_schema.tables where table_schema = 'iam'", Integer.class))
                .isPositive();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("1");
    }

    @Test
    void appRoleCannotReadFlywayHistory() {
        assertThatThrownBy(() -> new JdbcTemplate(appDataSource)
                .queryForObject("select count(*) from flyway.flyway_schema_history", Integer.class))
                .rootCause().hasMessageContaining("permission denied");
    }

    @Test
    void adminConnectionUsesTheAdminRole() {
        assertThat(new JdbcTemplate(adminDataSource).queryForObject("select current_user", String.class))
                .isEqualTo("e7gz_admin_runtime");
    }
}
