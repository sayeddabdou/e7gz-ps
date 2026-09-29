package ps.e7gz.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;
import ps.e7gz.support.RepoFiles;

/**
 * The migrations are exact copies of the physical model (CLAUDE.md §5: never edit V1__baseline.sql).
 * If the model in docs/04-erd/physical changes, the change goes in a NEW migration, and this test tells you.
 */
class MigrationFilesTest {

    @Test
    void baselineIsTheUnchangedPhysicalModelExport() throws IOException {
        assertThat(classpath("db/migration/V1__baseline.sql"))
                .isEqualTo(docs("V1__e7gz_ps_baseline_v1_1_27.sql"));
    }

    @Test
    void securityBaselineIsTheDocumentedOneWithOnlyAHeaderAdded() throws IOException {
        assertThat(classpath("db/migration/R__security_baseline.sql"))
                .contains("${flyway:timestamp}")
                .endsWith(docs("E7GZ-PS-security-baseline-v1.1.27.sql"));
    }

    /** v1.1.28 (E7-12): the guard-identifier migration is the model's own block, byte for byte. */
    @Test
    void guardIdentifierMigrationIsTheModelsV1128Block() throws IOException {
        String model = docs("E7GZ-PS-Database-v1.1.27-REVIEW-FIXES.dbm");
        String open = "<genericsql name=\"v1_1_28_guard_error_identifiers\">\n\t<definition> <![CDATA[\n";
        int start = model.indexOf(open);
        assertThat(start).as("v1.1.28 block in the .dbm").isPositive();
        String block = model.substring(start + open.length(), model.indexOf("]]> </definition>", start));
        assertThat(classpath("db/migration/V202609291200__guard_error_identifiers.sql")).isEqualTo(block);
    }

    private static String classpath(String resource) throws IOException {
        try (InputStream in = MigrationFilesTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertThat(in).as(resource).isNotNull();
            return normalise(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private static String docs(String file) throws IOException {
        return normalise(Files.readString(RepoFiles.get("docs/04-erd/physical/" + file)));
    }

    private static String normalise(String s) {
        return s.replace("\r\n", "\n");
    }
}
