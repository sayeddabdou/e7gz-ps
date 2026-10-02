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

    /** v1.1.30 (E7-23): what the migration adds is modelled in the .dbm under the same names. */
    @Test
    void venueCreatedAtAndUniqueAreaNamesAreInTheModel() throws IOException {
        String model = docs("E7GZ-PS-Database-v1.1.27-REVIEW-FIXES.dbm");
        String migration = classpath("db/migration/V202610022000__venue_created_at_area_name_unique.sql");
        String venueTable = model.substring(model.indexOf("<table name=\"venue\" layers"),
                model.indexOf("</table>", model.indexOf("<table name=\"venue\" layers")));

        assertThat(venueTable).contains("<column name=\"created_at\" not-null=\"true\" default-value=\"clock_timestamp()\">");
        assertThat(model).contains("<index name=\"ix_venue_created_at\" table=\"venue.venue\"")
                .contains("<index name=\"ux_area_name_normalized\" table=\"geo.area\"");
        assertThat(migration).contains("ADD COLUMN created_at timestamp with time zone NOT NULL DEFAULT clock_timestamp()")
                .contains("CREATE INDEX ix_venue_created_at ON venue.venue USING btree (created_at, id)")
                .contains("CREATE UNIQUE INDEX ux_area_name_normalized ON geo.area USING btree (lower(btrim(name)))");
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
