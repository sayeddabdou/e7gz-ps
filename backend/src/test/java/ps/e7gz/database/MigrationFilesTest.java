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
     * ends with the model's own function block, byte for byte: job_finish takes SUPERSEDED, a later due time and a next
     * run (attempt back to 0), and the guard lets due_at and attempt change that way only inside job_finish.
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
                .contains("attempt=CASE WHEN p_next_run THEN 0 ELSE j.attempt END")
                .contains("NEW.attempt=0 AND NEW.status='PENDING' AND OLD.status='LEASED'")
                .contains("REVOKE ALL ON FUNCTION technical.job_finish(uuid,text,text,text,timestamptz,boolean) FROM PUBLIC;");
    }

    /**
     * v1.1.34 (E7-48, PCR-007): the migration is the model's own block, byte for byte; the guard is the V1 definition
     * with exactly one line added (ACTIVE -> CANCELLED / VENUE_CANCELLED_MANUAL_BOOKING for MANUAL only), and the
     * commit-time provenance check is a deferred constraint trigger. No other object is touched.
     */
    @Test
    void manualActiveCancelMigrationIsTheModelsV1134Block() throws IOException {
        String model = docs("E7GZ-PS-Database-v1.1.27-REVIEW-FIXES.dbm");
        String open = "<genericsql name=\"v1_1_34_manual_booking_active_cancel\">\n\t<definition> <![CDATA[\n";
        int start = model.indexOf(open);
        assertThat(start).as("v1.1.34 block in the .dbm").isPositive();
        String block = model.substring(start + open.length(), model.indexOf("]]> </definition>", start));
        String migration = classpath("db/migration/V202610102240__manual_booking_active_cancel.sql");
        assertThat(migration).isEqualTo(block);

        String guardOpen = "CREATE OR REPLACE FUNCTION booking.guard_booking_transition()";
        String baselineGuard = function(classpath("db/migration/V1__baseline.sql"), guardOpen);
        String migratedGuard = function(migration, guardOpen);
        String added = "    OR (NEW.source='MANUAL' AND r='VENUE_CANCELLED_MANUAL_BOOKING')"
                + "  -- PCR-007; actor checked at commit (below)\n";
        assertThat(migratedGuard.replace(added, "")).as("the guard is V1's, plus one line").isEqualTo(baselineGuard);
        assertThat(migratedGuard).containsOnlyOnce(added);

        assertThat(migration).contains("verifies the RECORDED actor provenance, not an independently authenticated");
        String sql = migration.substring(migration.indexOf(guardOpen));
        assertThat(sql)
                .contains("CREATE CONSTRAINT TRIGGER tr_booking_manual_active_cancel_provenance\n"
                        + "AFTER UPDATE OF status ON booking.booking\nDEFERRABLE INITIALLY DEFERRED")
                .contains("REVOKE ALL ON FUNCTION booking.assert_manual_active_cancel_provenance() FROM PUBLIC;")
                .doesNotContain("tr_03_booking_transition_guard")
                .doesNotContain("begin_command")
                .doesNotContain("ALTER TABLE")
                .doesNotContain("set_config");
    }

    /**
     * v1.1.35 (E7-58 D1): the migration is the model's own block, byte for byte; ex_customer_no_overlap keeps its name,
     * columns, operators and method, and only its predicate gains the AWAITING exemption. No other object is touched.
     */
    @Test
    void lateConfirmAwaitingOverlapMigrationIsTheModelsV1135Block() throws IOException {
        String model = docs("E7GZ-PS-Database-v1.1.27-REVIEW-FIXES.dbm");
        String open = "<genericsql name=\"v1_1_35_late_confirm_awaiting_overlap\">\n\t<definition> <![CDATA[\n";
        int start = model.indexOf(open);
        assertThat(start).as("v1.1.35 block in the .dbm").isPositive();
        String block = model.substring(start + open.length(), model.indexOf("]]> </definition>", start));
        String migration = classpath("db/migration/V202610131300__late_confirm_awaiting_overlap.sql");
        assertThat(migration).isEqualTo(block);

        String baseline = classpath("db/migration/V1__baseline.sql");
        String exclusion = "    EXCLUDE USING gist (\n        customer_id WITH =,\n        commitment_period WITH &&\n    )\n";
        assertThat(baseline).containsOnlyOnce("ADD CONSTRAINT ex_customer_no_overlap\n" + exclusion
                + "    WHERE (commitment_period IS NOT NULL);");
        String sql = migration.substring(migration.indexOf("ALTER TABLE booking.booking"));
        assertThat(sql)
                .startsWith("ALTER TABLE booking.booking\n    DROP CONSTRAINT ex_customer_no_overlap;\n")
                .containsOnlyOnce("ADD CONSTRAINT ex_customer_no_overlap\n" + exclusion
                        + "    WHERE (commitment_period IS NOT NULL AND status <> 'AWAITING_CUSTOMER_CONFIRMATION');")
                .containsOnlyOnce("DROP ")
                .doesNotContain("FUNCTION")
                .doesNotContain("TRIGGER")
                .doesNotContain("derive_commitment_period")
                .doesNotContain("ck_booking_commitment_period_shape")
                .doesNotContain("UPDATE ")
                .doesNotContain("GRANT");
    }

    /**
     * v1.1.36 (E7-56): the migration is the model's own block, byte for byte; it adds the one SECURITY DEFINER producer
     * of Admin Alerts with a fixed search_path and changes no table, trigger or grant (the security baseline grants
     * EXECUTE; e7gz_app_runtime keeps no INSERT or UPDATE on admin.admin_alert).
     */
    @Test
    void adminRaiseAlertMigrationIsTheModelsV1136Block() throws IOException {
        String model = docs("E7GZ-PS-Database-v1.1.27-REVIEW-FIXES.dbm");
        String open = "<genericsql name=\"v1_1_36_admin_raise_alert\">\n\t<definition> <![CDATA[\n";
        int start = model.indexOf(open);
        assertThat(start).as("v1.1.36 block in the .dbm").isPositive();
        String block = model.substring(start + open.length(), model.indexOf("]]> </definition>", start));
        String migration = classpath("db/migration/V202610150400__admin_raise_alert.sql");
        assertThat(migration).isEqualTo(block);

        String sql = migration.substring(migration.indexOf("CREATE FUNCTION"));
        assertThat(sql)
                .startsWith("CREATE FUNCTION admin.raise_alert(p_kind text, p_severity text, p_subject_type text, "
                        + "p_subject_id uuid,\n")
                .contains("RETURNS uuid LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp")
                .containsOnlyOnce("INSERT INTO admin.admin_alert(")
                .doesNotContain("acknowledged")
                .doesNotContain("UPDATE ")
                .doesNotContain("DROP ")
                .doesNotContain("ALTER ")
                .doesNotContain("GRANT")
                .doesNotContain("REVOKE")
                .doesNotContain("TRIGGER");
    }

    /**
     * v1.1.37 (E7-113): the migration is the model's own block, byte for byte; the table model has the column, its
     * check and the partial unique index; each of the four functions is its previous definition plus exactly the
     * superseded-session lines, and both constraints keep their names with a current-sessions predicate.
     */
    @Test
    void operatingSessionSupersessionMigrationIsTheModelsV1137Block() throws IOException {
        String model = docs("E7GZ-PS-Database-v1.1.27-REVIEW-FIXES.dbm");
        String open = "<genericsql name=\"v1_1_37_operating_session_supersession\">\n\t<definition> <![CDATA[\n";
        int start = model.indexOf(open);
        assertThat(start).as("v1.1.37 block in the .dbm").isPositive();
        String block = model.substring(start + open.length(), model.indexOf("]]> </definition>", start));
        String migration = classpath("db/migration/V202610171400__operating_session_supersession.sql");
        assertThat(migration).isEqualTo(block);

        int tableStart = model.indexOf("<table name=\"operating_session\" layers");
        String table = model.substring(tableStart, model.indexOf("</table>", tableStart));
        assertThat(table).contains("<column name=\"superseded_at\">")
                .contains("<constraint name=\"ck_operating_session_superseded_after_generation\"")
                .doesNotContain("<constraint name=\"uq_operating_session_day\"");
        assertThat(model).contains("<index name=\"uq_operating_session_day\" table=\"venue.operating_session\"")
                .contains("<predicate> <![CDATA[superseded_at IS NULL]]> </predicate>");
        assertThat(migration).contains("ADD COLUMN superseded_at timestamp with time zone;")
                .contains("DROP CONSTRAINT uq_operating_session_day;")
                .contains("CREATE UNIQUE INDEX uq_operating_session_day ON venue.operating_session\n"
                        + "    USING btree (venue_id, operating_day)\n    WHERE superseded_at IS NULL;")
                .contains("ADD CONSTRAINT ex_session_no_overlap\n"
                        + "    EXCLUDE USING gist (venue_id WITH =, session_period WITH &&)\n"
                        + "    WHERE (superseded_at IS NULL);")
                .doesNotContain("UPDATE ")
                .doesNotContain("DISABLE TRIGGER")
                .doesNotContain("GRANT");

        String baseline = classpath("db/migration/V1__baseline.sql");
        String identifiers = classpath("db/migration/V202609291200__guard_error_identifiers.sql");
        assertAddedLinesOnly(baseline, migration, "CREATE OR REPLACE FUNCTION venue.guard_operating_session()",
                " -- v1.1.37: supersession is set once, on a referenced session only, and a superseded session never "
                        + "changes again.\n"
                        + " IF TG_OP='UPDATE' AND OLD.superseded_at IS NOT NULL AND NEW IS DISTINCT FROM OLD THEN\n"
                        + "   RAISE EXCEPTION 'A superseded Operating Session is history and never changes';\n"
                        + " END IF;\n"
                        + " IF TG_OP='UPDATE' AND NEW.superseded_at IS NOT NULL AND OLD.superseded_at IS NULL"
                        + " AND NOT v_referenced THEN\n"
                        + "   RAISE EXCEPTION 'Only a referenced Operating Session is superseded; an unreferenced one is "
                        + "regenerated';\n"
                        + " END IF;\n");
        assertAddedLinesOnly(baseline, migration, "CREATE OR REPLACE FUNCTION booking.validate_booking_insert_snapshot()",
                "        IF os.superseded_at IS NOT NULL THEN  -- v1.1.37: a new booking never snapshots superseded "
                        + "history\n"
                        + "            RAISE EXCEPTION 'a new booking cannot snapshot a superseded Operating Session';\n"
                        + "        END IF;\n",
                "              AND s.superseded_at IS NULL  -- v1.1.37: only a current session is published\n");
        assertAddedLinesOnly(baseline, migration, "CREATE OR REPLACE FUNCTION operations.guard_emergency_lifecycle()",
                "       AND s.superseded_at IS NULL  -- v1.1.37: the current session, never a superseded historical one\n");
        String intake = "CREATE OR REPLACE FUNCTION booking.validate_online_booking_intake()";
        assertThat(function(migration, intake)).isEqualTo(function(identifiers, intake).replace(
                "s.session_period @> NEW.created_at\n", "s.session_period @> NEW.created_at AND s.superseded_at IS NULL\n"));
    }

    /** {@code open}'s definition in {@code migration} is its definition in {@code earlier} plus exactly {@code lines}. */
    /**
     * v1.1.39 (E7-66 PR 3, PCR-009): the migration is the model's own block, byte for byte; the table model has the
     * nullable refund_id and the new check; assert_user and guard_audit_origin are their V1 definitions plus exactly
     * the Wallet-payment lines; no grant (the security baseline grants the new producer).
     */
    @Test
    void walletBookingFeePaymentMigrationIsTheModelsV1139Block() throws IOException {
        String model = docs("E7GZ-PS-Database-v1.1.27-REVIEW-FIXES.dbm");
        String open = "<genericsql name=\"v1_1_39_wallet_booking_fee_payment\">\n\t<definition> <![CDATA[\n";
        int start = model.indexOf(open);
        assertThat(start).as("v1.1.39 block in the .dbm").isPositive();
        String block = model.substring(start + open.length(), model.indexOf("]]> </definition>", start));
        String migration = classpath("db/migration/V202610241200__wallet_booking_fee_payment.sql");
        assertThat(migration).isEqualTo(block);

        int tableStart = model.indexOf("<table name=\"wallet_transaction\" layers");
        String table = model.substring(tableStart, model.indexOf("</table>", tableStart));
        assertThat(table).contains("<column name=\"refund_id\">")
                .contains("<constraint name=\"ck_wallet_tx_source\"")
                .contains("cause='BOOKING_FEE_PAYMENT' AND amount<0 AND refund_id IS NULL AND balance_after>=0")
                .doesNotContain("ck_wallet_tx_launch_source");
        assertThat(migration).contains("ALTER TABLE loyalty.wallet_transaction ALTER COLUMN refund_id DROP NOT NULL;")
                .contains("DROP CONSTRAINT ck_wallet_tx_launch_source;")
                .contains("CREATE OR REPLACE FUNCTION loyalty.pay_fee_from_wallet(p_booking uuid)")
                .doesNotContain("GRANT")
                .doesNotContain("DISABLE TRIGGER");

        String baseline = classpath("db/migration/V1__baseline.sql");
        // one block inserted into the Wallet loop, from its comment to its CONTINUE; everything else is V1's
        String first = "   -- v1.1.39 (PCR-009): a Wallet payment of a Booking Fee is bound";
        String last = "     CONTINUE;\n   END IF;\n";
        String added = migration.substring(migration.indexOf(first), migration.indexOf(last) + last.length());
        assertThat(added).contains("IF rr.cause='BOOKING_FEE_PAYMENT' THEN")
                .contains("rc.provider='WALLET'")
                .contains("a.action='BOOKING_FEE_WALLET_PAYMENT'")
                .contains("o.event_type='LOYALTY_WALLET_LEDGER_COMMITTED'");
        assertAddedLinesOnly(baseline, migration, "CREATE OR REPLACE FUNCTION loyalty.assert_user(p_user uuid)",
                added);
        String audit = "CREATE OR REPLACE FUNCTION loyalty.guard_audit_origin()";
        assertAddedLinesOnly(baseline.substring(baseline.lastIndexOf(audit)), migration, audit,
                " -- v1.1.39 (PCR-009): the Wallet payment audit is private-producer only too.\n"
                        + " IF NEW.action='BOOKING_FEE_WALLET_PAYMENT' THEN\n"
                        + "   PERFORM loyalty.require_write('audit.audit_event',NEW.id::text);\n"
                        + " END IF;\n");
    }

    private static void assertAddedLinesOnly(String earlier, String migration, String open, String... lines) {
        String migrated = function(migration, open);
        String stripped = migrated;
        for (String line : lines) {
            assertThat(migrated).as(open).containsOnlyOnce(line);
            stripped = stripped.replace(line, "");
        }
        assertThat(stripped).as("%s is its previous definition plus the v1.1.37 lines", open)
                .isEqualTo(function(earlier, open));
    }

    /** One {@code CREATE OR REPLACE FUNCTION ... $function$;} definition, from its first line to its end. */
    private static String function(String sql, String open) {
        int start = sql.indexOf(open);
        assertThat(start).as(open).isNotNegative();
        assertThat(sql.indexOf(open, start + 1)).as("%s defined once", open).isNegative();
        return sql.substring(start, sql.indexOf("$function$;\n", start) + "$function$;\n".length());
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
