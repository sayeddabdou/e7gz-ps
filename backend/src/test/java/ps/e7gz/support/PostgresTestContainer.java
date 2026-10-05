package ps.e7gz.support;

import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * One PostGIS container for the whole test run (E7-10), set up exactly like {@code docker compose}:
 * same image, same init script (roles + extensions). Started once, stopped by Testcontainers at JVM exit.
 */
public final class PostgresTestContainer {

    public static final String DATABASE = "e7gz_ps";
    public static final String MIGRATOR = "e7gz_migrator";
    public static final String APP_RUNTIME = "e7gz_app_runtime";
    public static final String ADMIN_RUNTIME = "e7gz_admin_runtime";

    private static final PostgreSQLContainer<?> CONTAINER =
            new PostgreSQLContainer<>(DockerImageName.parse("postgis/postgis:16-3.5").asCompatibleSubstituteFor("postgres"))
                    .withDatabaseName("postgres")          // e7gz_ps is created by the init script
                    .withUsername("postgres")
                    .withPassword("postgres")
                    // Testcontainers' default flags plus room for the cached Spring test contexts: each one keeps up to
                    // 13 pooled connections (app 10 + admin 3), past the default limit of 100 (E7-28).
                    .withCommand("postgres", "-c", "fsync=off", "-c", "max_connections=300")
                    .withCopyFileToContainer(
                            MountableFile.forHostPath(RepoFiles.get("infra/local/postgres/init/20_e7gz.sql")),
                            "/docker-entrypoint-initdb.d/20_e7gz.sql");

    static {
        CONTAINER.start();
    }

    private PostgresTestContainer() {
    }

    public static PostgreSQLContainer<?> get() {
        return CONTAINER;
    }

    /** JDBC URL of a database in the container (local role passwords equal the role names). */
    public static String jdbcUrl(String database) {
        return "jdbc:postgresql://%s:%d/%s".formatted(
                CONTAINER.getHost(), CONTAINER.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT), database);
    }
}
