package ps.e7gz.common.command;

import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ps.e7gz.common.persistence.DataSourceConfig;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CommandRetryProperties.class)
public class CommandRunnerConfig {

    /**
     * The runner keeps its own transaction managers (not beans), so Spring Boot's default transaction manager for
     * the app connection is left as it is.
     */
    @Bean
    public CommandRunner commandRunner(DataSource appDataSource,
                                       @Qualifier(DataSourceConfig.ADMIN_DATA_SOURCE) DataSource adminDataSource,
                                       CommandRetryProperties retry) {
        return new CommandRunner(appDataSource, adminDataSource, retry);
    }
}
