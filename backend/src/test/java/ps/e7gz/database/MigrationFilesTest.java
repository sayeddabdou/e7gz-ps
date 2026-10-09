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

    /** v1.1.29 (E7-23): the abuse-alert trigger runs as its owner; the model's own block, byte for byte. */
    @Test
    void adminAlertTriggerMigrationIsTheModelsV1129Block() throws IOException {
        String model = docs("E7GZ-PS-Database-v1.1.27-REVIEW-FIXES.dbm");
        String open = "<genericsql name=\"v1_1_29_admin_alert_trigger_security_definer\">\n\t<definition> <![CDATA[\n";
        int start = model.indexOf(open);
        assertThat(start).as("v1.1.29 block in the .dbm").isPositive();
        String block = model.substring(start + open.length(), model.indexOf("]]> </definition>", start));
        assertThat(classpath("db/migration/V202610021800__admin_alert_trigger_security_definer.sql")).isEqualTo(block);
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

    /** v1.1.31 (E7-23 PR 3, PCR-005): the photo life-cycle columns, check and index are modelled under the same names. */
    @Test
    void venueImageLifeCycleIsInTheModel() throws IOException {
        String model = docs("E7GZ-PS-Database-v1.1.27-REVIEW-FIXES.dbm");
        String migration = classpath("db/migration/V202610031200__venue_image_upload_and_removal.sql");
        int start = model.indexOf("<table name=\"venue_image\" layers");
        String table = model.substring(start, model.indexOf("</table>", start));

        for (String column : new String[] {"uploaded_at", "processed_at", "removed_at"}) {
            assertThat(table).contains("<column name=\"" + column + "\">");
            assertThat(migration).contains("ADD COLUMN " + column + " timestamp with time zone");
        }
        assertThat(table).contains("<column name=\"created_at\" not-null=\"true\" default-value=\"clock_timestamp()\">")
                .contains("<constraint name=\"ck_venue_image_life_cycle\"")
                .contains("(processed_at IS NULL OR uploaded_at IS NOT NULL) AND (uploaded_at IS NULL OR sort_order IS NOT NULL)");
        assertThat(model).contains("<index name=\"ix_venue_image_visible\" table=\"venue.venue_image\"")
                .contains("<predicate> <![CDATA[uploaded_at IS NOT NULL AND removed_at IS NULL]]> </predicate>");
        assertThat(migration)
                .contains("ADD COLUMN created_at timestamp with time zone NOT NULL DEFAULT clock_timestamp()")
                .contains("CHECK ((processed_at IS NULL OR uploaded_at IS NOT NULL) AND (uploaded_at IS NULL OR sort_order IS NOT NULL))")
                .contains("CREATE INDEX ix_venue_image_visible ON venue.venue_image USING btree (venue_id, sort_order)")
                .contains("WHERE uploaded_at IS NOT NULL AND removed_at IS NULL");
    }

    /**
     * v1.1.32 (E7-30): the Emergency selling-block guard fix is the model's own block, byte for byte, and every
     * table-specific field access (the Emergency scope check and the subject-immutability checks) sits inside its own
     * table's TG_TABLE_SCHEMA test.
     */
    @Test
    void emergencySellingBlockGuardFixIsTheModelsV1132Block() throws IOException {
        String model = docs("E7GZ-PS-Database-v1.1.27-REVIEW-FIXES.dbm");
        String open = "<genericsql name=\"v1_1_32_emergency_selling_block_guard_fix\">\n\t<definition> <![CDATA[\n";
        int start = model.indexOf(open);
        assertThat(start).as("v1.1.32 block in the .dbm").isPositive();
        String block = model.substring(start + open.length(), model.indexOf("]]> </definition>", start));
        String migration = classpath("db/migration/V202610031800__fix_emergency_selling_block_guard.sql");

        assertThat(migration).isEqualTo(block);
        String body = migration.substring(migration.indexOf("CREATE OR REPLACE FUNCTION operations.guard_emergency_selling_block()"));
        assertThat(body)
                .contains("IF TG_TABLE_SCHEMA = 'venue' THEN\n"
                        + "            IF v_scope <> 'VENUE' OR NEW.venue_id IS DISTINCT FROM v_venue THEN")
                .contains("IF TG_TABLE_SCHEMA = 'resource' THEN\n"
                        + "            IF v_scope <> 'RESOURCE' OR NEW.resource_id IS DISTINCT FROM v_resource THEN")
                .doesNotContain("ELSE\n            IF v_scope <> 'RESOURCE'")
                .contains("IF TG_TABLE_SCHEMA = 'venue' THEN\n                IF NEW.venue_id IS DISTINCT FROM OLD.venue_id THEN")
                .contains("IF TG_TABLE_SCHEMA = 'resource' THEN\n                IF NEW.resource_id IS DISTINCT FROM OLD.resource_id THEN")
                .doesNotContain("TG_TABLE_SCHEMA = 'venue' AND")
                .doesNotContain("TG_TABLE_SCHEMA = 'resource' AND")
                .doesNotContain("CREATE TRIGGER")
                .doesNotContain("DROP ");
    }

    /**
     * v1.1.33 (E7-40): the column and table comment are modelled on {@code technical.scheduled_job}, and the migration
     * ends with the model's own function block, byte for byte: job_finish takes SUPERSEDED and a later due time, and the
     * guard lets due_at move on LEASED -> PENDING only inside job_finish.
     */
    @Test
    void scheduledJobFinishAndCorrelationIsTheModelsV1133Block() throws IOException {
        String model = docs("E7GZ-PS-Database-v1.1.27-REVIEW-FIXES.dbm");
        String migration = classpath("db/migration/V202610091200__scheduled_job_finish_and_correlation.sql");
        int tableStart = model.indexOf("<table name=\"scheduled_job\" layers");
        String table = model.substring(tableStart, model.indexOf("</table>", tableStart));
        String open = "<genericsql name=\"v1_1_33_scheduled_job_finish_and_correlation\">\n\t<definition> <![CDATA[\n";
        int start = model.indexOf(open);
        assertThat(start).as("v1.1.33 block in the .dbm").isPositive();
        String block = model.substring(start + open.length(), model.indexOf("]]> </definition>", start));

        assertThat(table).contains("<column name=\"correlation_id\" not-null=\"true\">\n\t\t<type name=\"uuid\"")
                .contains("type/subject/idempotency_key/correlation_id are immutable; due_at moves only through "
                        + "job_rearm or job_finish.");
        assertThat(migration).contains("ADD COLUMN correlation_id uuid;")
                .contains("UPDATE technical.scheduled_job SET correlation_id = id WHERE correlation_id IS NULL;")
                .contains("ALTER COLUMN correlation_id SET NOT NULL;")
                .contains("type/subject/idempotency_key/correlation_id are immutable; due_at moves only through "
                        + "job_rearm or job_finish.")
                .endsWith(block);
        for (String trigger : new String[] {"tr_125_scheduled_job_guard", "tr_guard_tournament_scheduled_job",
                "tr_tournament_job_final_state"}) {
            assertThat(migration).contains("DISABLE TRIGGER " + trigger + ";").contains("ENABLE TRIGGER " + trigger + ";");
        }
        assertThat(block).contains("DROP FUNCTION technical.job_finish(uuid,text,text,text);")
                .contains("p_status NOT IN ('DONE','SUPERSEDED','FAILED','PENDING')")
                .contains("technical.has_write('technical.scheduled_job',OLD.id::text)")
                .contains("REVOKE ALL ON FUNCTION technical.job_finish(uuid,text,text,text,timestamptz) FROM PUBLIC;");
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
