package ps.e7gz.common.persistence;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * The two runtime connections (arch 04 §5). Flyway uses its own owner connection ({@code spring.flyway.*}).
 */
@Configuration(proxyBeanMethods = false)
public class DataSourceConfig {

    /** Bean name of the Platform Admin connection ({@code e7gz_admin_runtime}). */
    public static final String ADMIN_DATA_SOURCE = "adminDataSource";

    /** {@code e7gz_app_runtime}: every api/worker read and write. */
    @Bean
    @Primary
    @ConfigurationProperties("e7gz.datasource.app")
    public HikariDataSource appDataSource() {
        return new HikariDataSource();
    }

    /** {@code e7gz_admin_runtime}: Platform Admin commands, including the admin-only producers. */
    @Bean(ADMIN_DATA_SOURCE)
    @ConfigurationProperties("e7gz.datasource.admin")
    public HikariDataSource adminDataSource() {
        return new HikariDataSource();
    }
}
