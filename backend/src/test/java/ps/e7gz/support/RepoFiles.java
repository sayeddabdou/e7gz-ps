package ps.e7gz.support;

import java.nio.file.Files;
import java.nio.file.Path;

/** Files outside backend/ that tests read (infra/, docs/). The repo root is set by Gradle. */
public final class RepoFiles {

    private RepoFiles() {
    }

    /** The repository root (falls back to running from backend/). */
    public static Path root() {
        return Path.of(System.getProperty("e7gz.repoRoot", "..")).toAbsolutePath().normalize();
    }

    /** A file relative to the repository root that must already exist. */
    public static Path get(String relative) {
        Path file = root().resolve(relative).normalize();
        if (!Files.exists(file)) {
            throw new IllegalStateException("Missing " + file + " — run tests from the backend/ folder or via Gradle");
        }
        return file;
    }
}
