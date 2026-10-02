package ps.e7gz.admin.internal;

import java.io.Console;
import java.util.Arrays;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Runs the first-admin bootstrap when the application starts with the {@code admin-bootstrap} profile, then the
 * application ends (no web server in that profile):
 * <pre>
 * ./gradlew bootRun --args='--spring.profiles.active=admin-bootstrap
 *     --e7gz.admin-bootstrap.email=owner@example.com --e7gz.admin-bootstrap.full-name="First Admin"'
 * </pre>
 * The password is read from the environment variable {@value #PASSWORD_ENV} when it is set (deployments: from the
 * secret store, never a file in the repository), otherwise typed twice at the terminal without echo. It is never a
 * command-line argument, which other users of the machine could see. A failure ends the start with a non-zero exit.
 */
@Component
@Profile(FirstAdminBootstrapRunner.PROFILE)
class FirstAdminBootstrapRunner implements ApplicationRunner {

    static final String PROFILE = "admin-bootstrap";
    static final String PASSWORD_ENV = "E7GZ_ADMIN_BOOTSTRAP_PASSWORD";

    private static final Logger log = LoggerFactory.getLogger(FirstAdminBootstrapRunner.class);

    private final FirstAdminBootstrap bootstrap;
    private final String email;
    private final String fullName;

    FirstAdminBootstrapRunner(FirstAdminBootstrap bootstrap,
                              @Value("${e7gz.admin-bootstrap.email:}") String email,
                              @Value("${e7gz.admin-bootstrap.full-name:}") String fullName) {
        this.bootstrap = bootstrap;
        this.email = email;
        this.fullName = fullName;
    }

    @Override
    public void run(ApplicationArguments args) {
        UUID id = bootstrap.create(email, fullName, readPassword());
        log.info("First Platform Admin created: id={}", id);
    }

    private static char[] readPassword() {
        String fromEnvironment = System.getenv(PASSWORD_ENV);
        if (fromEnvironment != null && !fromEnvironment.isEmpty()) {
            return fromEnvironment.toCharArray();
        }
        Console console = System.console();
        if (console == null) {
            throw new IllegalStateException("No terminal to type the admin password: set " + PASSWORD_ENV);
        }
        char[] first = console.readPassword("New Platform Admin password: ");
        char[] second = console.readPassword("Repeat the password: ");
        try {
            if (first == null || !Arrays.equals(first, second)) {
                throw new IllegalArgumentException("The two passwords do not match");
            }
            return first.clone();
        } finally {
            if (first != null) {
                Arrays.fill(first, '\0');
            }
            if (second != null) {
                Arrays.fill(second, '\0');
            }
        }
    }
}
