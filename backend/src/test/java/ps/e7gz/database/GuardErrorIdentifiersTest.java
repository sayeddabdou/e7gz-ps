package ps.e7gz.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static ps.e7gz.support.PostgresTestContainer.MIGRATOR;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.postgresql.util.PSQLException;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.PostgreSQLContainer;
import ps.e7gz.common.error.ApiErrorCode;
import ps.e7gz.common.error.DatabaseErrorCodes;
import ps.e7gz.support.PostgresTestContainer;

/**
 * E7-12 / physical model v1.1.28: the guard identifiers of {@code V202609291200__guard_error_identifiers.sql}.
 *
 * <ol>
 *   <li><b>Migration.</b> An existing v1.1.27 database (Flyway stopped at V1) upgraded to the latest version and a fresh
 *       install end with identical function definitions; the upgrade changes nothing but the 14 function bodies —
 *       owner, grants, SECURITY DEFINER, search_path, signatures, triggers and tables stay as they were.</li>
 *   <li><b>Guards.</b> Every mapped guard is triggered for real: SQLSTATE {@code P0001}, the {@code gd_…} identifier in
 *       the CONSTRAINT field, the unchanged message, and the {@link ApiErrorCode} it maps to. Unmapped identifiers,
 *       internal invariants and the deliberately combined OTP guard stay unexpected failures. Known exclusion and
 *       unique constraints map by name.</li>
 * </ol>
 * Each guard case runs in its own transaction on a disposable copy of the migrated database and is rolled back; like
 * the physical model's own scripts, a case inserts prerequisite rows with {@code session_replication_role = replica}
 * and exercises a trigger on a structural copy of its table where the real table has unrelated triggers.
 */
class GuardErrorIdentifiersTest {

    private static final String UPGRADE_DB = "e7gz_guard_upgrade";
    private static final String FRESH_DB = "e7gz_guard_fresh";
    private static final String CASES_DB = "e7gz_guard_cases";

    /** The 14 functions the migration restates (name → identity arguments, for regprocedure). */
    static final Map<String, String> FUNCTIONS = new LinkedHashMap<>();

    static {
        FUNCTIONS.put("booking.validate_online_booking_intake", "()");
        FUNCTIONS.put("booking.validate_booking_creation_venue", "()");
        FUNCTIONS.put("inventory.guard_resource_reservation_cross_class", "()");
        FUNCTIONS.put("attendance.validate_attendance_action_write", "()");
        FUNCTIONS.put("tournament.prepare_tournament_insert", "()");
        FUNCTIONS.put("tournament.guard_tournament_participant", "()");
        FUNCTIONS.put("iam.guard_user_account_update", "()");
        FUNCTIONS.put("iam.guard_otp_challenge", "()");
        FUNCTIONS.put("geo.guard_area_deactivation", "()");
        FUNCTIONS.put("venue.guard_venue_area", "()");
        FUNCTIONS.put("operations.guard_maintenance_window", "()");
        FUNCTIONS.put("review.create_review", "(uuid,uuid,smallint,text,uuid)");
        FUNCTIONS.put("review.report_review", "(uuid,uuid,text,text,uuid)");
        FUNCTIONS.put("technical.idempotency_begin", "(text,uuid,text,text,text,timestamptz,uuid)");
    }

    // Seed identities (committed once in the cases database).
    static final String U1 = "11111111-1111-1111-1111-111111111111"; // verified, operational VERIFIED player
    static final String U2 = "22222222-2222-2222-2222-222222222222"; // verified
    static final String U3 = "33333333-3333-3333-3333-333333333333"; // verified
    static final String U4 = "44444444-4444-4444-4444-444444444444"; // phone NOT verified
    static final String A1 = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    static final String AR = "a0000000-0000-0000-0000-000000000001";  // active, holds ACTIVE V1
    static final String AR2 = "a0000000-0000-0000-0000-000000000002"; // active, holds INACTIVE V2
    static final String V1 = "b1111111-1111-1111-1111-111111111111";  // ACTIVE
    static final String V2 = "b2222222-2222-2222-2222-222222222222";  // INACTIVE
    static final String VA1 = "c1111111-1111-1111-1111-111111111111";
    static final String VA2 = "c2222222-2222-2222-2222-222222222222";
    static final String R1 = "d0000000-0000-0000-0000-000000000001";
    static final String TPL = "d1111111-1111-1111-1111-111111111111";
    static final String OS1 = "e1111111-1111-1111-1111-111111111111";

    private static PostgreSQLContainer<?> db;
    private static Map<String, FunctionState> v1State;
    private static Map<String, FunctionState> upgradedState;
    private static Map<String, FunctionState> freshState;
    private static String v1Structure;
    private static String upgradedStructure;

    @BeforeAll
    static void buildDatabases() throws Exception {
        db = PostgresTestContainer.get();
        for (String name : List.of(CASES_DB, UPGRADE_DB, FRESH_DB)) {
            psql("DROP DATABASE IF EXISTS " + name);
        }
        psql("CREATE DATABASE " + UPGRADE_DB + " TEMPLATE template0");
        psql("CREATE DATABASE " + FRESH_DB + " TEMPLATE template0");

        // An existing v1.1.27 database, then upgraded by exactly this migration (later migrations change the schema on
        // purpose and are checked by their own tests).
        flyway(UPGRADE_DB).target(MigrationVersion.fromVersion("1")).load().migrate();
        v1State = functionStates(UPGRADE_DB);
        v1Structure = structure(UPGRADE_DB);
        flyway(UPGRADE_DB).target(MigrationVersion.fromVersion("202609291200")).load().migrate();
        upgradedState = functionStates(UPGRADE_DB);
        upgradedStructure = structure(UPGRADE_DB);

        // A fresh install.
        flyway(FRESH_DB).load().migrate();
        freshState = functionStates(FRESH_DB);

        psql("CREATE DATABASE " + CASES_DB + " TEMPLATE " + FRESH_DB);
        seed();
    }

    // ------------------------------------------------------------------------------------------------ migration

    @Test
    void upgradeAndFreshInstallEndWithTheSameFunctions() {
        assertThat(upgradedState.keySet()).containsExactlyElementsOf(FUNCTIONS.keySet());
        FUNCTIONS.keySet().forEach(name ->
                assertThat(upgradedState.get(name)).as(name).isEqualTo(freshState.get(name)));
    }

    @Test
    void theUpgradeChangesOnlyTheFunctionBodies() {
        FUNCTIONS.keySet().forEach(name -> {
            FunctionState before = v1State.get(name);
            FunctionState after = upgradedState.get(name);
            assertThat(after.attributes()).as(name + ": owner, grants, security, search_path, signature")
                    .isEqualTo(before.attributes());
            assertThat(before.definition()).as(name + " before").doesNotContain("gd_");
            assertThat(after.definition()).as(name + " after").contains("USING CONSTRAINT");
            assertThat(messages(after.definition())).as(name + ": every message text kept")
                    .isEqualTo(messages(before.definition()));
        });
        // No table, column, constraint, index or trigger changed.
        assertThat(upgradedStructure).isEqualTo(v1Structure);
    }

    @Test
    void theUpgradeIsRecordedAsASuccessfulMigration() throws SQLException {
        assertThat(query(UPGRADE_DB, "SELECT string_agg(version || ':' || success, ',' ORDER BY installed_rank) "
                + "FROM flyway.flyway_schema_history WHERE version IS NOT NULL"))
                // Later migrations (E7-23 onward) follow; this one must be applied, in order, and nothing may fail.
                .startsWith("1:true,202609291200:true")
                .doesNotContain(":false");
    }

    // ------------------------------------------------------------------------------------------------ identity

    @Test
    void usernameCooldown() throws SQLException {
        assertGuard(failure(
                        "UPDATE iam.user_account SET username='ahmed_1' WHERE id='" + U1 + "'",
                        "UPDATE iam.user_account SET username='ahmed_2' WHERE id='" + U1 + "'"),
                "gd_username_cooldown", ApiErrorCode.USERNAME_COOLDOWN_ACTIVE, "Username change cooldown is active");
    }

    @Test
    void usernameTakenIsTheUniqueIndexByName() throws SQLException {
        Failure f = failure("UPDATE iam.user_account SET username='AHMED' WHERE id='" + U2 + "'");
        assertThat(f.sqlState()).isEqualTo(DatabaseErrorCodes.UNIQUE_VIOLATION);
        assertThat(f.constraint()).isEqualTo("ux_user_username_normalized");
        assertThat(DatabaseErrorCodes.translate(f.exception())).contains(ApiErrorCode.USERNAME_TAKEN);
    }

    @Test
    void otpResendTooSoon() throws SQLException {
        assertGuard(failure(otp("+201099990001", "now()"), otp("+201099990001", "now()")),
                "gd_otp_resend_too_soon", ApiErrorCode.OTP_RESEND_TOO_SOON, "OTP resend is available 60 seconds");
    }

    @Test
    void otpHourlyLimit() throws SQLException {
        String d = "+201099990002";
        assertGuard(failure(otp(d, "now()-interval '50 minutes'"), otp(d, "now()-interval '40 minutes'"),
                        otp(d, "now()-interval '30 minutes'"), otp(d, "now()-interval '20 minutes'"),
                        otp(d, "now()-interval '10 minutes'"), otp(d, "now()")),
                "gd_otp_hourly_limit", ApiErrorCode.OTP_HOURLY_LIMIT_REACHED, "Maximum 5 OTP sends per hour");
    }

    /**
     * "No longer usable" combines expired, superseded, consumed and out-of-attempts, and no code or precedence is
     * documented for all of them: it stays one unmapped failure (the E7-13 verify flow decides from the locked row).
     */
    @Test
    void anUnusableOtpStaysUnmappedEvenWhenItsCausesOverlap() throws SQLException {
        String d = "+201099990003";
        // Out of attempts AND expired at once (arranged with the guard off), then one more verification.
        assertUnmapped(failure(otp(d, "now()-interval '6 minutes'"), "SET LOCAL session_replication_role = replica",
                        "UPDATE iam.otp_challenge SET attempts=5 WHERE destination='" + d + "'",
                        "SET LOCAL session_replication_role = origin",
                        "UPDATE iam.otp_challenge SET consumed_at=now() WHERE destination='" + d + "'"),
                "OTP is no longer usable");
        // Each cause alone gives the same unmapped failure.
        String e = "+201099990004";
        assertUnmapped(failure(otp(e, "now()-interval '6 minutes'"),
                "UPDATE iam.otp_challenge SET consumed_at=now() WHERE destination='" + e + "'"), "OTP is no longer usable");
        String f = "+201099990005";
        String attempt = "UPDATE iam.otp_challenge SET attempts=attempts+1 WHERE destination='" + f + "'";
        assertUnmapped(failure(otp(f, "now()"), attempt, attempt, attempt, attempt, attempt, attempt),
                "OTP is no longer usable");
    }

    // ------------------------------------------------------------------------------------------------ areas

    @Test
    void areaWithActiveVenuesCannotBeDeactivated() throws SQLException {
        assertGuard(failure("UPDATE geo.area SET is_active=false WHERE id='" + AR + "'"),
                "gd_area_has_active_venues", ApiErrorCode.AREA_HAS_ACTIVE_VENUES, "Area cannot be deactivated");
    }

    @Test
    void newVenueCannotBeAssignedToAnInactiveArea() throws SQLException {
        assertGuard(failure("UPDATE geo.area SET is_active=false WHERE id='" + AR2 + "'",
                        "INSERT INTO venue.venue(id,area_id,name,address,location,marketplace_status,timezone,phone) VALUES"
                                + "(gen_random_uuid(),'" + AR2 + "','V9','a',ST_GeogFromText('POINT(31 30)'),'ONBOARDING',"
                                + "'Africa/Cairo','+20223456789')"),
                "gd_venue_area_inactive_assignment", ApiErrorCode.AREA_INACTIVE, "Only an ACTIVE Area");
    }

    @Test
    void venueInAnInactiveAreaCannotBecomeActive() throws SQLException {
        assertGuard(failure("UPDATE geo.area SET is_active=false WHERE id='" + AR2 + "'",
                        "UPDATE venue.venue SET marketplace_status='ACTIVE' WHERE id='" + V2 + "'"),
                "gd_venue_area_inactive_status", ApiErrorCode.AREA_INACTIVE, "A Venue in an inactive Area");
    }

    /** Both Area rules apply at once: the assignment check still comes first, as in v1.1.27. */
    @Test
    void overlappingAreaRulesKeepTheirOrder() throws SQLException {
        assertGuard(failure("INSERT INTO geo.area(id,name,is_active) VALUES('a0000000-0000-0000-0000-000000000009','Z',true)",
                        "UPDATE geo.area SET is_active=false WHERE id='a0000000-0000-0000-0000-000000000009'",
                        "UPDATE venue.venue SET area_id='a0000000-0000-0000-0000-000000000009', "
                                + "marketplace_status='ACTIVE' WHERE id='" + V2 + "'"),
                "gd_venue_area_inactive_assignment", ApiErrorCode.AREA_INACTIVE, "Only an ACTIVE Area");
    }

    // ------------------------------------------------------------------------------------------------ bookings

    @Test
    void onlineBookingLeadTime() throws SQLException {
        assertGuard(failure(probe("booking.booking", "booking.validate_online_booking_intake", "INSERT"),
                        onlineProbe(V1, "now()", "now()+interval '14 minutes'")),
                "gd_online_booking_lead_time", ApiErrorCode.BOOKING_LEAD_TIME_TOO_SHORT, "at least 15 minutes");
    }

    @Test
    void onlineBookingIntakeWindow() throws SQLException {
        assertGuard(failure(probe("booking.booking", "booking.validate_online_booking_intake", "INSERT"),
                        onlineProbe(V1, "now()-interval '3 hours'", "now()+interval '30 minutes'")),
                "gd_online_booking_intake_window", ApiErrorCode.ONLINE_INTAKE_WINDOW_CLOSED,
                "requires a current Operating Session");
    }

    /** FR-BOK-11 condition 7 has no documented code yet: identified, but still an unexpected failure. */
    @Test
    void onlineBookingInAnInactiveAreaIsIdentifiedButUnmapped() throws SQLException {
        String ar3 = "a0000000-0000-0000-0000-000000000003";
        String v3 = "b3333333-3333-3333-3333-333333333333";
        Failure f = failure(
                "INSERT INTO geo.area(id,name,is_active) VALUES('" + ar3 + "','Z3',true)",
                "INSERT INTO venue.venue(id,area_id,name,address,location,marketplace_status,timezone,phone) VALUES('" + v3
                        + "','" + ar3 + "','V3','a',ST_GeogFromText('POINT(31 30)'),'ONBOARDING','Africa/Cairo','+20223456789')",
                "SET LOCAL session_replication_role = replica",
                "UPDATE geo.area SET is_active=false WHERE id='" + ar3 + "'",
                "SET LOCAL session_replication_role = origin",
                session(v3, "d3333333-3333-3333-3333-333333333333"),
                probe("booking.booking", "booking.validate_online_booking_intake", "INSERT"),
                onlineProbe(v3, "now()", "now()+interval '30 minutes'"));
        assertThat(f.constraint()).isEqualTo("gd_online_booking_area_inactive");
        assertUnmapped(f, "Venue Area to be ACTIVE");
    }

    @Test
    void onlineBookingAtAVenueThatIsNotActive() throws SQLException {
        assertGuard(failure(probe("booking.booking", "booking.validate_booking_creation_venue", "INSERT"),
                        "INSERT INTO public.probe(id,venue_id,source,status,created_at,start_at) VALUES(gen_random_uuid(),'"
                                + V2 + "','ONLINE','PENDING',now(),now()+interval '1 hour')"),
                "gd_online_booking_venue_not_active", ApiErrorCode.VENUE_NOT_ELIGIBLE_FOR_NEW_ACTIVITY,
                "requires Venue Marketplace ACTIVE");
    }

    @Test
    void manualBookingBackdated() throws SQLException {
        assertGuard(failure(probe("booking.booking", "booking.validate_booking_creation_venue", "INSERT"),
                        manualProbe("now()", "now()-interval '1 hour'")),
                "gd_manual_booking_backdated", ApiErrorCode.MANUAL_BOOKING_BACKDATING_NOT_ALLOWED, "never retroactive");
    }

    /** The split keeps the defect part (no server creation instant, or one in the future) without an identifier. */
    @Test
    void manualBookingWithAFutureCreationInstantIsADefectEvenWhenAlsoBackdated() throws SQLException {
        assertUnmapped(failure(probe("booking.booking", "booking.validate_booking_creation_venue", "INSERT"),
                manualProbe("now()+interval '1 day'", "now()")), "never retroactive");
        assertUnmapped(failure(probe("booking.booking", "booking.validate_booking_creation_venue", "INSERT"),
                manualProbe("now()+interval '1 day'", "now()+interval '2 days'")), "never retroactive");
    }

    @ParameterizedTest(name = "{0} over a Booking -> {1}")
    @CsvSource({
            "BOOKING, gd_reservation_conflict_booking, SLOT_NO_LONGER_AVAILABLE",
            "LATE_CONFIRM_HOLD, gd_reservation_conflict_late_confirm, LATE_CONFIRM_RESOURCE_CONFLICT",
            "TOURNAMENT_APPROVAL_HOLD, gd_reservation_conflict_tournament, TOURNAMENT_RESOURCE_CONFLICT",
            "TOURNAMENT_HOLD, gd_reservation_conflict_tournament, TOURNAMENT_RESOURCE_CONFLICT",
            "MAINTENANCE, gd_reservation_conflict_maintenance, MAINTENANCE_OVERLAPS_RESERVATION"})
    void reservationConflictByType(String type, String identifier, ApiErrorCode code) throws SQLException {
        assertGuard(failure(existingBookingReservation(), probe("inventory.resource_reservation",
                        "inventory.guard_resource_reservation_cross_class", "INSERT"), reservationProbe(type)),
                identifier, code, "resource reservation conflict");
    }

    @Test
    void closureConflictIsIdentifiedButUnmapped() throws SQLException {
        Failure f = failure(existingBookingReservation(), probe("inventory.resource_reservation",
                "inventory.guard_resource_reservation_cross_class", "INSERT"), reservationProbe("CLOSURE"));
        assertThat(f.constraint()).isEqualTo("gd_reservation_conflict_closure");
        assertUnmapped(f, "resource reservation conflict");
    }

    @Test
    void doubleBookingExclusionConstraintMapsBySlot() throws SQLException {
        Failure f = failure(existingBookingReservation(), "SET LOCAL session_replication_role = replica",
                reservation("BOOKING", "now()+interval '1 day 30 minutes'", "now()+interval '1 day 90 minutes'"));
        assertThat(f.sqlState()).isEqualTo(DatabaseErrorCodes.EXCLUSION_VIOLATION);
        assertThat(f.constraint()).isEqualTo("ex_no_double_booking");
        assertThat(DatabaseErrorCodes.translate(f.exception())).contains(ApiErrorCode.SLOT_NO_LONGER_AVAILABLE);
    }

    @Test
    void customerOverlapExclusionConstraintMapsToOverlappingCommitment() throws SQLException {
        Failure f = failure("SET LOCAL session_replication_role = replica",
                booking("e0000000-0000-0000-0000-000000000001", "now()+interval '2 days'"),
                booking("e0000000-0000-0000-0000-000000000002", "now()+interval '2 days 30 minutes'"));
        assertThat(f.sqlState()).isEqualTo(DatabaseErrorCodes.EXCLUSION_VIOLATION);
        assertThat(f.constraint()).isEqualTo("ex_customer_no_overlap");
        assertThat(DatabaseErrorCodes.translate(f.exception())).contains(ApiErrorCode.OVERLAPPING_COMMITMENT);
    }

    // ------------------------------------------------------------------------------------------------ attendance

    @ParameterizedTest(name = "{0} outside its window -> {2}")
    @CsvSource({
            "check_in_at, now()+interval '1 hour', gd_check_in_window, CHECK_IN_WINDOW_CLOSED, Check-in must be within",
            "late_present_at, now()-interval '10 minutes', gd_late_present_window, LATE_PRESENT_NOT_AVAILABLE, Late Present must be within",
            "no_show_at, now()-interval '10 minutes', gd_no_show_window, NO_SHOW_NOT_AVAILABLE, No-show must be within"})
    void attendanceWindows(String column, String bookingStart, String identifier, ApiErrorCode code, String message)
            throws SQLException {
        String bookingId = "e0000000-0000-0000-0000-000000000010";
        assertGuard(failure("SET LOCAL session_replication_role = replica", booking(bookingId, bookingStart),
                        "SET LOCAL session_replication_role = origin",
                        probe("attendance.attendance_record", "attendance.validate_attendance_action_write", "INSERT"),
                        "INSERT INTO public.probe(id,booking_id," + column + ",recorded_by_venue_account_id) VALUES("
                                + "gen_random_uuid(),'" + bookingId + "',now(),'" + VA1 + "')"),
                identifier, code, message);
    }

    // ------------------------------------------------------------------------------------------------ tournaments

    @Test
    void tournamentAtAVenueThatIsNotActive() throws SQLException {
        assertGuard(tournamentFailure(tournamentProbe(V2, U1, "now()+interval '1 day'")),
                "gd_tournament_venue_not_active", ApiErrorCode.VENUE_NOT_ELIGIBLE_FOR_NEW_ACTIVITY,
                "New Tournament creation requires Venue Marketplace ACTIVE");
    }

    @Test
    void tournamentBlockedBySellingBlock() throws SQLException {
        assertGuard(tournamentFailure("INSERT INTO venue.venue_selling_block(id,venue_id,cause,active,started_at) "
                                + "VALUES(gen_random_uuid(),'" + V1 + "','EMERGENCY',true,now())",
                        tournamentProbe(V1, U1, "now()+interval '1 day'")),
                "gd_tournament_venue_selling_block", ApiErrorCode.VENUE_SELLING_BLOCK_ACTIVE, "Operational Selling Block");
    }

    @Test
    void tournamentCreationCutoff() throws SQLException {
        assertGuard(tournamentFailure(tournamentProbe(V1, U1, "now()+interval '1 hour'")),
                "gd_tournament_creation_cutoff", ApiErrorCode.TOURNAMENT_CREATION_CUTOFF_PASSED, "creation cutoff");
    }

    /** A deleted account that is also unverified: deleted is reported (FR-ACC-10 applies to any command). */
    @Test
    void tournamentCreatorDeletedWinsOverUnverified() throws SQLException {
        assertGuard(tournamentFailure("SET LOCAL session_replication_role = replica",
                        "UPDATE iam.user_account SET anonymised_at=now(), phone_verified=false, phone_verified_at=NULL, full_name='', email='', "
                                + "password_hash='', phone_e164='anonymised:' || id::text WHERE id='" + U3 + "'",
                        "SET LOCAL session_replication_role = origin",
                        tournamentProbe(V1, U3, "now()+interval '1 day'")),
                "gd_tournament_creator_deleted", ApiErrorCode.ACCOUNT_DELETED, "Account-Verified active User");
    }

    @Test
    void tournamentCreatorUnverified() throws SQLException {
        assertGuard(tournamentFailure(tournamentProbe(V1, U4, "now()+interval '1 day'")),
                "gd_tournament_creator_unverified", ApiErrorCode.PHONE_VERIFICATION_REQUIRED,
                "Account-Verified active User");
    }

    @Test
    void tournamentCreatorNotAVerifiedPlayer() throws SQLException {
        assertGuard(tournamentFailure(tournamentProbe(V1, U2, "now()+interval '1 day'")),
                "gd_tournament_creator_not_verified_player", ApiErrorCode.VERIFIED_PLAYER_REQUIRED,
                "Only operational VERIFIED");
    }

    @Test
    void tournamentCreatorBanned() throws SQLException {
        assertGuard(tournamentFailure("SET LOCAL session_replication_role = replica",
                        "INSERT INTO trust.ban_instance(id,user_id,qualification_id,penalty_stage,activated_at,ends_at,duration,"
                                + "status,activation_reason,latest_qualification_id,last_command_at) VALUES(gen_random_uuid(),'"
                                + U1 + "',gen_random_uuid(),1,now()-interval '1 hour',now()-interval '1 hour'+interval '7 days',"
                                + "interval '7 days','ACTIVE','test',gen_random_uuid(),now())",
                        "SET LOCAL session_replication_role = origin",
                        tournamentProbe(V1, U1, "now()+interval '1 day'")),
                "gd_tournament_creator_banned", ApiErrorCode.ACCOUNT_BANNED_FOR_THIS_ACTION, "Banned User");
    }

    @Test
    void tournamentRetryCooldown() throws SQLException {
        String t0 = "f0000000-0000-0000-0000-000000000001";
        String start = "date_trunc('hour', now())+interval '2 days'";
        assertGuard(tournamentFailure("SET LOCAL session_replication_role = replica",
                        tournament(t0, U1, "REJECTED", 8, start),
                        "INSERT INTO tournament.tournament_history(id,tournament_id,sequence,occurred_at,from_status,to_status,"
                                + "terminal_reason,actor_type,actor_id,correlation_id) VALUES(gen_random_uuid(),'" + t0 + "',1,"
                                + "now()-interval '1 minute','PENDING_VENUE_APPROVAL','REJECTED','VENUE_REJECTED_TOURNAMENT',"
                                + "'VENUE','" + VA1 + "',gen_random_uuid())",
                        "SET LOCAL session_replication_role = origin",
                        tournamentProbe(V1, U1, start)),
                "gd_tournament_retry_cooldown", ApiErrorCode.TOURNAMENT_RETRY_COOLDOWN_ACTIVE, "wait 5 minutes");
    }

    @Test
    void tournamentCapacityFull() throws SQLException {
        String t = "f0000000-0000-0000-0000-000000000002";
        assertGuard(tournamentFailure("SET LOCAL session_replication_role = replica",
                        tournament(t, U2, "WAITING_FOR_PLAYERS", 8, "now()+interval '2 days'"),
                        // Eight JOINED participants (existing, non-deleted Users) fill the minimum capacity of 8.
                        "INSERT INTO iam.user_account(id,full_name,username,phone_e164,email,password_hash,phone_verified,"
                                + "phone_verified_at,email_verified,referral_code,joined_at) SELECT gen_random_uuid(),'P'||g,"
                                + "'cap'||g,'+2011000000'||lpad(g::text,2,'0'),'cap'||g||'@x.com','h',true,now(),false,"
                                + "'CAP'||g,now() FROM generate_series(1,8) g",
                        "INSERT INTO tournament.tournament_participant(id,tournament_id,user_id,status,joined_at,"
                                + "status_changed_at) SELECT gen_random_uuid(),'" + t + "',u.id,'JOINED',now(),now() "
                                + "FROM iam.user_account u WHERE u.username LIKE 'cap%'",
                        "SET LOCAL session_replication_role = origin",
                        probe("tournament.tournament_participant", "tournament.guard_tournament_participant", "INSERT"),
                        "INSERT INTO public.probe(id,tournament_id,user_id) VALUES(gen_random_uuid(),'" + t + "','" + U1 + "')"),
                "gd_tournament_capacity_full", ApiErrorCode.TOURNAMENT_CAPACITY_REACHED, "capacity is full");
    }

    // ------------------------------------------------------------------------------------------------ maintenance

    @Test
    void maintenanceOverlappingACommitment() throws SQLException {
        assertGuard(failure(existingBookingReservation(),
                        probe("operations.maintenance_window", "operations.guard_maintenance_window", "INSERT OR UPDATE"),
                        maintenanceProbe("now()+interval '1 day'", "now()+interval '1 day 1 hour'")),
                "gd_maintenance_overlaps_commitment", ApiErrorCode.MAINTENANCE_OVERLAPS_RESERVATION,
                "Maintenance may not overlap");
    }

    @Test
    void maintenanceOverTheRollingThresholdNeedsTheOwnerPassword() throws SQLException {
        assertGuard(failure(probe("operations.maintenance_window", "operations.guard_maintenance_window", "INSERT OR UPDATE"),
                        maintenanceProbe("now()+interval '3 days'", "now()+interval '4 days 1 hour'")),
                "gd_maintenance_owner_password_required", ApiErrorCode.OWNER_PASSWORD_REQUIRED,
                "Owner Password is required: Maintenance exceeds 24 hours");
        assertGuard(failure(probe("operations.maintenance_window", "operations.guard_maintenance_window", "INSERT OR UPDATE"),
                        maintenanceProbe("now()+interval '3 days'", "now()+interval '3 days 1 hour'"),
                        "UPDATE public.probe SET period=tstzrange(now()+interval '3 days', now()+interval '4 days 1 hour','[)')"),
                "gd_maintenance_owner_password_required", ApiErrorCode.OWNER_PASSWORD_REQUIRED,
                "Owner Password is required for a Maintenance edit");
    }

    // ------------------------------------------------------------------------------------------------ reviews

    @Test
    void reviewAlreadyExists() throws SQLException {
        assertGuard(failure(reviewScope(U1), createReview(U1), createReview(U1)),
                "gd_review_already_exists", ApiErrorCode.REVIEW_ALREADY_EXISTS, "already has a current Review");
    }

    @Test
    void reviewCooldown() throws SQLException {
        assertGuard(failure("SET LOCAL session_replication_role = replica",
                        "INSERT INTO review.review_cooldown(id,user_id,venue_id,removed_at,cooldown_until,review_id,"
                                + "moderation_event_id) VALUES(gen_random_uuid(),'" + U2 + "','" + V1 + "',now()-interval '1 day',"
                                + "now()-interval '1 day'+interval '30 days',gen_random_uuid(),gen_random_uuid())",
                        "SET LOCAL session_replication_role = origin", reviewScope(U2), createReview(U2)),
                "gd_review_cooldown", ApiErrorCode.REVIEW_COOLDOWN_ACTIVE, "Review cooldown after Admin removal");
    }

    @Test
    void reviewAuthorUnverified() throws SQLException {
        assertGuard(failure(reviewScope(U4), createReview(U4)),
                "gd_review_author_unverified", ApiErrorCode.PHONE_VERIFICATION_REQUIRED,
                "Only a phone-verified, non-anonymised User");
    }

    /** A deleted account that is also unverified: deleted is reported (FR-ACC-10 applies to any command). */
    @Test
    void reviewAuthorDeletedWinsOverUnverified() throws SQLException {
        assertGuard(failure("SET LOCAL session_replication_role = replica",
                        "UPDATE iam.user_account SET anonymised_at=now(), phone_verified=false, phone_verified_at=NULL, full_name='', email='', "
                                + "password_hash='', phone_e164='anonymised:' || id::text WHERE id='" + U3 + "'",
                        "SET LOCAL session_replication_role = origin", reviewScope(U3), createReview(U3)),
                "gd_review_author_deleted", ApiErrorCode.ACCOUNT_DELETED, "Only a phone-verified, non-anonymised User");
    }

    @Test
    void reviewReportAlreadyOpen() throws SQLException {
        String review = "e0000000-0000-0000-0000-0000000000a1";
        assertGuard(failure(reviewScope(U1, U2),
                        "SET LOCAL session_replication_role = replica",
                        "INSERT INTO review.review(id,user_id,venue_id,rating,comment,status,created_at,updated_at) VALUES('"
                                + review + "','" + U1 + "','" + V1 + "',5,'ok','ACTIVE',now(),now())",
                        "SET LOCAL session_replication_role = origin",
                        report(review, U2), report(review, U2)),
                "gd_review_report_open", ApiErrorCode.REVIEW_REPORT_ALREADY_OPEN, "already has an unresolved report");
    }

    // ------------------------------------------------------------------------------------------------ idempotency

    @Test
    void idempotencyKeyReusedWithADifferentRequest() throws SQLException {
        assertGuard(failure(idempotency("a".repeat(64)), idempotency("b".repeat(64))),
                "gd_idempotency_key_reused", ApiErrorCode.IDEMPOTENCY_KEY_REUSED, "Idempotency key reused");
    }

    // ------------------------------------------------------------------------------------------------ unexpected

    @Test
    void anInternalInvariantGuardStaysUnexpected() throws SQLException {
        assertUnmapped(failure("DELETE FROM geo.area WHERE id='" + AR2 + "'"), "never deleted");
    }

    @Test
    void anUnknownGuardIdentifierStaysUnexpected() throws SQLException {
        Failure f = failure("DO $$ BEGIN RAISE EXCEPTION 'x' USING CONSTRAINT = 'gd_not_in_the_mapping'; END $$");
        assertThat(f.sqlState()).isEqualTo(DatabaseErrorCodes.RAISE_EXCEPTION);
        assertThat(f.constraint()).isEqualTo("gd_not_in_the_mapping");
        assertThat(DatabaseErrorCodes.translate(f.exception())).isEmpty();
    }

    @Test
    void anUnmappedExclusionConstraintStaysUnexpected() throws SQLException {
        String os = "e1111111-1111-1111-1111-1111111111ff";
        Failure f = failure("SET LOCAL session_replication_role = replica",
                "INSERT INTO venue.operating_session(id,venue_id,opening_template_id,operating_day,local_start,local_end,"
                        + "session_period,timezone,generated_from_version,generated_at) VALUES('" + os + "','" + V1 + "','"
                        + TPL + "',current_date+10,now()::timestamp,now()::timestamp+interval '10 hours',"
                        + "tstzrange(now()-interval '1 hour',now()+interval '10 hours','[)'),'Africa/Cairo',0,now())");
        assertThat(f.sqlState()).isEqualTo(DatabaseErrorCodes.EXCLUSION_VIOLATION);
        assertThat(f.constraint()).isEqualTo("ex_session_no_overlap");
        assertThat(DatabaseErrorCodes.translate(f.exception())).isEmpty();
    }

    // ------------------------------------------------------------------------------------------------ reachability
    //
    // gd_online_booking_area_inactive and gd_reservation_conflict_closure stay unexpected failures (HTTP 500). The tests
    // below show why: a valid user action cannot reach either guard, because the rule the application checks first
    // under the command's locks already decides the outcome. If one fires, the Java decision was skipped - a defect.

    /**
     * FR-VEN-47 invariant under concurrency: an inactive Area never holds an ACTIVE Venue. Activating a Venue takes a
     * share lock on its Area row, and deactivating the Area locks that row: whichever commits second is refused.
     */
    @Test
    void anInactiveAreaNeverHoldsAnActiveVenueEvenUnderConcurrency() throws Exception {
        String area = "a7000000-0000-0000-0000-000000000001";
        String venue = "b7000000-0000-0000-0000-000000000001";
        commit("INSERT INTO geo.area(id,name,is_active) VALUES('" + area + "','Race A',true)",
                "INSERT INTO venue.venue(id,area_id,name,address,location,marketplace_status,timezone,phone) VALUES('" + venue
                        + "','" + area + "','Race V','a',ST_GeogFromText('POINT(31 30)'),'ONBOARDING','Africa/Cairo','+20223456789')");

        // Venue activation first: the Area deactivation waits for it, then sees the ACTIVE Venue.
        try (Connection first = open(); Connection second = open()) {
            execute(first, "UPDATE venue.venue SET marketplace_status='ACTIVE' WHERE id='" + venue + "'");
            Waiting deactivation = waiting(second, "UPDATE geo.area SET is_active=false WHERE id='" + area + "'");
            deactivation.awaitBlocked();
            first.commit();
            assertThat(deactivation.failure().constraint()).isEqualTo("gd_area_has_active_venues");
        }
        // Area deactivation first (after the Venue is made INACTIVE again): the activation waits, then is refused.
        commit("UPDATE venue.venue SET marketplace_status='INACTIVE' WHERE id='" + venue + "'");
        try (Connection first = open(); Connection second = open()) {
            execute(first, "UPDATE geo.area SET is_active=false WHERE id='" + area + "'");
            Waiting activation = waiting(second, "UPDATE venue.venue SET marketplace_status='ACTIVE' WHERE id='" + venue + "'");
            activation.awaitBlocked();
            first.commit();
            assertThat(activation.failure().constraint()).isEqualTo("gd_venue_area_inactive_status");
        }
    }

    /**
     * An Online Booking command locks its Venue (tournament.begin_command, arch 04 §2.1) before the application checks
     * FR-BOK-11 condition 5 (Venue ACTIVE) ahead of condition 7 (Area ACTIVE) in the ordered predicate (arch 06 §1.3).
     * While the command holds the lock the Venue cannot leave ACTIVE, so by the invariant above its Area stays active:
     * a booking that passed condition 5 can never reach the Area guard. The guard fires only for a Venue that is not
     * ACTIVE, which condition 5 has already refused (VENUE_NOT_ELIGIBLE_FOR_NEW_ACTIVITY) unless the check was skipped.
     */
    @Test
    void anOnlineBookingThatPassedTheVenueCheckCannotReachTheAreaGuard() throws Exception {
        try (Connection command = open(); Connection admin = open()) {
            execute(command, "SELECT tournament.begin_command(ARRAY['" + U1 + "']::uuid[],ARRAY['" + V1 + "']::uuid[],"
                    + "ARRAY[]::uuid[],ARRAY[]::uuid[],ARRAY[]::uuid[])");
            Waiting statusChange = waiting(admin, "UPDATE venue.venue SET marketplace_status='INACTIVE' WHERE id='" + V1 + "'");
            statusChange.awaitBlocked();
            // Under the lock: the Venue is ACTIVE and its Area is active.
            assertThat(query(command, "SELECT v.marketplace_status || ':' || a.is_active FROM venue.venue v "
                    + "JOIN geo.area a ON a.id = v.area_id WHERE v.id = '" + V1 + "'")).isEqualTo("ACTIVE:true");
            // With both creation triggers in their real order (tr_21 intake, then tr_22 venue), the booking passes.
            execute(command, probe("booking.booking", "booking.validate_online_booking_intake", "INSERT")
                    + "; CREATE TRIGGER t2 BEFORE INSERT ON public.probe FOR EACH ROW "
                    + "EXECUTE FUNCTION booking.validate_booking_creation_venue()");
            execute(command, onlineProbe(V1, "now()", "now()+interval '30 minutes'"));
            command.rollback();
            statusChange.finish(); // rolled back: V1 stays ACTIVE for the other cases
        }
    }

    /**
     * A Closure save locks its Venue and every Resource of it (operations.lock_venue_closure_scope) before it resolves
     * the affected set and writes its CLOSURE rows; any new commitment locks its Resource too (the cross-class guard).
     * So a Booking racing a Closure waits and then loses as a slot conflict (SLOT_NO_LONGER_AVAILABLE): it can never
     * slip under the Closure. A commitment that existed before the lock is visible to the Closure command, which must
     * cancel it (FR-VEN-30), abort with AFFECTED_SET_CHANGED_REFRESH_PREVIEW (FR-VEN-26), or be blocked by a Tournament
     * (TOURNAMENT_CONFLICT_BLOCKS_SAVE, FR-VEN-32) before any CLOSURE row is written. gd_reservation_conflict_closure
     * therefore fires only when the Closure command skipped those decisions: a defect, and it cannot tell which of the
     * three documented refusals was skipped, so no single code would be true.
     */
    @Test
    void aBookingRacingAClosureLosesAsASlotConflictNotAsAClosureConflict() throws Exception {
        String venue = "b7000000-0000-0000-0000-000000000002";
        String resource = "d7000000-0000-0000-0000-000000000002";
        String closure = "c7000000-0000-0000-0000-000000000002";
        String start = "date_trunc('hour', now()) + interval '3 days'";
        String end = start + " + interval '4 hours'";
        commit("INSERT INTO venue.venue(id,area_id,name,address,location,marketplace_status,timezone,phone) VALUES('" + venue
                        + "','" + AR + "','Closure V','a',ST_GeogFromText('POINT(31 30)'),'ONBOARDING','Africa/Cairo','+20223456789')",
                "SET session_replication_role = replica",
                "INSERT INTO resource.resource(id,venue_id,activity,name,base_state,operational_restriction) VALUES('"
                        + resource + "','" + venue + "','BILLIARDS','Table C','ACTIVE','NONE')",
                "SET session_replication_role = origin");
        try (Connection closureSave = open(); Connection booking = open()) {
            // The Closure save: its first write locks the Venue and every Resource.
            execute(closureSave, "INSERT INTO operations.venue_closure(id,venue_id,period,kind,reason) VALUES('" + closure
                    + "','" + venue + "',tstzrange(" + start + "," + end + ",'[)'),'PARTIAL','renovation')");
            // A Booking for the same Resource and interval arrives: it waits on the Resource lock.
            execute(booking, probe("inventory.resource_reservation", "inventory.guard_resource_reservation_cross_class", "INSERT"));
            Waiting racing = waiting(booking, "INSERT INTO public.probe(id,resource_id,reservation_type,planned_start,"
                    + "planned_end,planned_range,actual_reservation_start,active_flag) VALUES(gen_random_uuid(),'" + resource
                    + "','BOOKING'," + start + "+interval '1 hour'," + start + "+interval '2 hours',tstzrange(" + start
                    + "+interval '1 hour'," + start + "+interval '2 hours','[)')," + start + "+interval '1 hour',true)");
            racing.awaitBlocked();
            // The Closure writes its CLOSURE guard row for the Resource and commits.
            execute(closureSave, "SET LOCAL session_replication_role = replica");
            execute(closureSave, "INSERT INTO inventory.resource_reservation(id,resource_id,reservation_type,closure_id,"
                    + "planned_start,planned_end,planned_range,actual_reservation_start,active_flag) VALUES(gen_random_uuid(),'"
                    + resource + "','CLOSURE','" + closure + "'," + start + "," + end + ",tstzrange(" + start + "," + end
                    + ",'[)')," + start + ",true)");
            execute(closureSave, "SET LOCAL session_replication_role = origin");
            closureSave.commit();
            Failure lost = racing.failure();
            assertThat(lost.constraint()).isEqualTo("gd_reservation_conflict_booking");
            assertThat(DatabaseErrorCodes.translate(lost.exception())).contains(ApiErrorCode.SLOT_NO_LONGER_AVAILABLE);
        }
    }

    // ================================================================================================ helpers

    record Failure(PSQLException exception) {

        String sqlState() {
            return exception.getSQLState();
        }

        String constraint() {
            return exception.getServerErrorMessage().getConstraint();
        }

        String message() {
            return exception.getServerErrorMessage().getMessage();
        }
    }

    record FunctionState(String definition, String attributes) {
    }

    /** A statement run on its own connection in the background, expected to block on a lock and then fail. */
    private static final class Waiting {

        private final Connection connection;
        private final int pid;
        private final CompletableFuture<PSQLException> result;

        Waiting(Connection connection, String sql) throws SQLException {
            this.connection = connection;
            this.pid = Integer.parseInt(query(connection, "SELECT pg_backend_pid()::text"));
            this.result = CompletableFuture.supplyAsync(() -> {
                try (Statement s = connection.createStatement()) {
                    s.execute(sql);
                    return null;
                } catch (PSQLException e) {
                    return e;
                } catch (SQLException e) {
                    throw new IllegalStateException(e);
                }
            });
        }

        /** Waits until PostgreSQL reports the statement as waiting for a lock (proves the serialization point). */
        void awaitBlocked() throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (System.nanoTime() < deadline) {
                assertThat(result.isDone()).as("statement finished instead of waiting for the lock").isFalse();
                try (Connection c = connect(CASES_DB)) {
                    if ("Lock".equals(query(c, "SELECT coalesce(wait_event_type,'') FROM pg_stat_activity WHERE pid=" + pid))) {
                        return;
                    }
                }
                Thread.sleep(50);
            }
            fail("statement never waited for the lock");
        }

        /** The failure the statement ended with once unblocked; its transaction is rolled back. */
        Failure failure() throws Exception {
            PSQLException e = result.get(20, TimeUnit.SECONDS);
            connection.rollback();
            assertThat((Object) e).as("the unblocked statement must be refused").isNotNull();
            return new Failure(e);
        }

        /** Lets the statement finish (whatever its outcome) and rolls it back. */
        void finish() throws Exception {
            result.get(20, TimeUnit.SECONDS);
            connection.rollback();
        }
    }

    private static Waiting waiting(Connection connection, String sql) throws SQLException {
        return new Waiting(connection, sql);
    }

    /** A connection to the cases database in manual-commit mode. */
    private static Connection open() throws SQLException {
        Connection c = connect(CASES_DB);
        c.setAutoCommit(false);
        return c;
    }

    private static void execute(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    private static String query(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }

    /** Runs and commits the statements (fixtures the reachability tests own; they use their own ids). */
    private static void commit(String... statements) throws SQLException {
        try (Connection c = connect(CASES_DB); Statement s = c.createStatement()) {
            for (String sql : statements) {
                s.execute(sql);
            }
        }
    }

    private static void assertGuard(Failure f, String identifier, ApiErrorCode code, String messageFragment) {
        assertThat(f.sqlState()).as("SQLSTATE").isEqualTo(DatabaseErrorCodes.RAISE_EXCEPTION);
        assertThat(f.constraint()).as("CONSTRAINT diagnostic").isEqualTo(identifier);
        assertThat(f.message()).as("message text unchanged").contains(messageFragment);
        assertThat(DatabaseErrorCodes.translate(f.exception())).as("mapped code").contains(code);
    }

    private static void assertUnmapped(Failure f, String messageFragment) {
        assertThat(f.sqlState()).isEqualTo(DatabaseErrorCodes.RAISE_EXCEPTION);
        assertThat(f.message()).contains(messageFragment);
        assertThat(DatabaseErrorCodes.translate(f.exception())).as("no code: an unexpected failure").isEmpty();
    }

    /** Runs the statements in one transaction; the last one must fail. Always rolled back. */
    private static Failure failure(String... statements) throws SQLException {
        try (Connection c = connect(CASES_DB)) {
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                for (int i = 0; i < statements.length - 1; i++) {
                    s.execute(statements[i]);
                }
                try {
                    s.execute(statements[statements.length - 1]);
                } catch (PSQLException expected) {
                    return new Failure(expected);
                }
                return fail("expected the last statement to fail: " + statements[statements.length - 1]);
            } finally {
                c.rollback();
            }
        }
    }

    /**
     * Tournament guards read the command scope; like the physical model's tournament script, the scope helpers are
     * replaced by the database clock for this transaction only (rolled back with it).
     */
    private static Failure tournamentFailure(String... statements) throws SQLException {
        List<String> all = new ArrayList<>(List.of(
                "CREATE OR REPLACE FUNCTION tournament.require_command_scope(p_tournament_id uuid DEFAULT NULL,"
                        + "p_venue_id uuid DEFAULT NULL,p_resource_id uuid DEFAULT NULL,p_user_id uuid DEFAULT NULL) "
                        + "RETURNS timestamptz LANGUAGE sql AS $$ SELECT now() $$",
                "CREATE OR REPLACE FUNCTION tournament.mark_created_in_command(p_tournament_id uuid) RETURNS void "
                        + "LANGUAGE sql AS $$ SELECT $$"));
        all.addAll(List.of(statements));
        if (all.stream().noneMatch(sql -> sql.contains("CREATE TABLE public.probe"))) {
            all.add(all.size() - 1, probe("tournament.tournament", "tournament.prepare_tournament_insert", "INSERT"));
        }
        return failure(all.toArray(String[]::new));
    }

    /** A structural copy of {@code table} with only {@code function}'s trigger and no NOT NULLs (as the model's scripts). */
    private static String probe(String table, String function, String events) {
        return "CREATE TABLE public.probe (LIKE " + table + " INCLUDING DEFAULTS); "
                + "CREATE TRIGGER t BEFORE " + events + " ON public.probe FOR EACH ROW EXECUTE FUNCTION " + function + "(); "
                + "DO $d$ DECLARE c text; BEGIN FOR c IN SELECT column_name FROM information_schema.columns "
                + "WHERE table_schema='public' AND table_name='probe' AND is_nullable='NO' LOOP "
                + "EXECUTE format('ALTER TABLE public.probe ALTER COLUMN %I DROP NOT NULL',c); END LOOP; END $d$";
    }

    private static String onlineProbe(String venue, String createdAt, String startAt) {
        return "INSERT INTO public.probe(id,venue_id,source,status,created_at,start_at,venue_response_expires_at,"
                + "booking_fee_snapshot) VALUES(gen_random_uuid(),'" + venue + "','ONLINE','PENDING'," + createdAt + ","
                + startAt + "," + createdAt + "+interval '10 minutes',0)";
    }

    private static String manualProbe(String createdAt, String startAt) {
        return "INSERT INTO public.probe(id,venue_id,source,status,created_at,start_at) VALUES(gen_random_uuid(),'" + V1
                + "','MANUAL','CONFIRMED'," + createdAt + "," + startAt + ")";
    }

    private static String existingBookingReservation() {
        return "SET LOCAL session_replication_role = replica; "
                + reservation("BOOKING", "now()+interval '1 day'", "now()+interval '1 day 2 hours'")
                + "; SET LOCAL session_replication_role = origin";
    }

    private static String reservation(String type, String start, String end) {
        return "INSERT INTO inventory.resource_reservation(id,resource_id,reservation_type,booking_id,planned_start,"
                + "planned_end,planned_range,actual_reservation_start,active_flag) VALUES(gen_random_uuid(),'" + R1 + "','"
                + type + "',gen_random_uuid()," + start + "," + end + ",tstzrange(" + start + "," + end + ",'[)'),"
                + start + ",true)";
    }

    private static String reservationProbe(String type) {
        String start = "now()+interval '1 day 30 minutes'";
        String end = "now()+interval '1 day 90 minutes'";
        return "INSERT INTO public.probe(id,resource_id,reservation_type,planned_start,planned_end,planned_range,"
                + "actual_reservation_start,active_flag) VALUES(gen_random_uuid(),'" + R1 + "','" + type + "'," + start + ","
                + end + ",tstzrange(" + start + "," + end + ",'[)')," + start + ",true)";
    }

    /** A valid Online CONFIRMED Booking of U1 on R1 (one hour of Billiards at 100.00). */
    private static String booking(String id, String startAt) {
        return "INSERT INTO booking.booking(id,venue_id,resource_id,customer_id,source,status,activity,"
                + "resource_name_snapshot,start_at,end_at,nominal_booked_minutes,hourly_price_snapshot,venue_price_total,"
                + "booking_fee_snapshot,total_amount,business_date,commitment_period,created_at) VALUES('" + id + "','" + V1
                + "','" + R1 + "','" + U1 + "','ONLINE','CONFIRMED','BILLIARDS','Table 1'," + startAt + "," + startAt
                + "+interval '60 minutes',60,100.00,100.00,0,100.00,current_date,tstzrange(" + startAt + "," + startAt
                + "+interval '60 minutes','[)'),now()-interval '1 day')";
    }

    private static String tournamentProbe(String venue, String creator, String startAt) {
        return "INSERT INTO public.probe(id,venue_id,host_type,creator_user_id,creator_participates,activity,play_mode,"
                + "capacity,start_at,nominal_minutes,visibility,venue_cost_total,creation_fee_snapshot,entry_fee,prize) "
                + "VALUES(gen_random_uuid(),'" + venue + "','PLAYER','" + creator + "',true,'PLAYSTATION','SINGLE',8,"
                + startAt + ",120,'PUBLIC',0,0,0,0)";
    }

    private static String tournament(String id, String creator, String status, int capacity, String startAt) {
        return "INSERT INTO tournament.tournament(id,venue_id,host_type,creator_user_id,creator_participates,activity,"
                + "play_mode,status,capacity,start_at,nominal_minutes,visibility,venue_cost_total,creation_fee_snapshot,"
                + "entry_fee,prize,business_date,operating_session_id,version,created_at,end_at,checkpoint_at,approval_expires_at,"
                + "terminal_reason) VALUES('" + id + "','" + V1
                + "','PLAYER','" + creator + "',true,'PLAYSTATION','SINGLE','" + status + "'," + capacity + "," + startAt
                + ",120,'PUBLIC',0,0,0,0,current_date,'" + OS1 + "',0,now()-interval '10 minutes'," + startAt + "+interval '120 minutes',"
                + startAt + "-interval '1 hour',now()," + ("REJECTED".equals(status) ? "'VENUE_REJECTED_TOURNAMENT'" : "NULL") + ")";
    }

    private static String maintenanceProbe(String start, String end) {
        return "INSERT INTO public.probe(id,resource_id,period,reason,owner_password_used) VALUES(gen_random_uuid(),'" + R1
                + "',tstzrange(" + start + "," + end + ",'[)'),'test',false)";
    }

    private static String reviewScope(String... users) {
        return "SELECT tournament.begin_command(ARRAY['" + String.join("','", users) + "']::uuid[],ARRAY['" + V1
                + "']::uuid[],ARRAY[]::uuid[],ARRAY[]::uuid[],ARRAY[]::uuid[])";
    }

    private static String createReview(String user) {
        return "SELECT review.create_review('" + user + "','" + V1 + "',5::smallint,'ok',gen_random_uuid())";
    }

    private static String report(String review, String reporter) {
        return "SELECT review.report_review('" + review + "','" + reporter + "','abuse',NULL,gen_random_uuid())";
    }

    private static String idempotency(String fingerprint) {
        return "SELECT * FROM technical.idempotency_begin('CUSTOMER','" + U1 + "','POST /api/v1/customer/x','key-1','"
                + fingerprint + "',now()+interval '1 day',gen_random_uuid())";
    }

    private static String otp(String destination, String issuedAt) {
        return "INSERT INTO iam.otp_challenge(id,user_id,purpose,destination,code_hash,issued_at,expires_at,attempts) "
                + "VALUES(gen_random_uuid(),'" + U3 + "','PHONE_VERIFICATION','" + destination + "','c'," + issuedAt + ","
                + issuedAt + "+interval '5 minutes',0)";
    }

    private static String session(String venue, String template) {
        return session(venue, template, "gen_random_uuid()");
    }

    private static String session(String venue, String template, String id) {
        return "INSERT INTO venue.venue_opening_template(id,venue_id,default_local_open,default_local_close,"
                + "default_full_day,version,updated_at) VALUES('" + template + "','" + venue + "','00:00','00:00',true,0,now()); "
                + "INSERT INTO venue.operating_session(id,venue_id,opening_template_id,operating_day,local_start,local_end,"
                + "session_period,timezone,generated_from_version,generated_at) VALUES(" + id + ",'" + venue + "','"
                + template + "',(now() AT TIME ZONE 'Africa/Cairo')::date,(now() AT TIME ZONE 'Africa/Cairo')-interval '2 hours',"
                + "(now() AT TIME ZONE 'Africa/Cairo')+interval '6 hours',tstzrange(now()-interval '2 hours',"
                + "now()+interval '6 hours','[)'),'Africa/Cairo',0,now())";
    }

    /** Base data, committed once: Users, Areas, Venues, Venue Accounts, a Resource and a current Operating Session. */
    private static void seed() throws SQLException {
        try (Connection c = connect(CASES_DB); Statement s = c.createStatement()) {
            s.execute("INSERT INTO iam.user_account(id,full_name,username,phone_e164,email,password_hash,phone_verified,"
                    + "phone_verified_at,email_verified,referral_code,joined_at) VALUES "
                    + "('" + U1 + "','Ahmed','ahmed','+201000000001','a@x.com','h',true,now(),false,'R1',now()),"
                    + "('" + U2 + "','Mona','mona','+201000000002','m@x.com','h',true,now(),false,'R2',now()),"
                    + "('" + U3 + "','Omar','omar','+201000000003','o@x.com','h',true,now(),false,'R3',now()),"
                    + "('" + U4 + "','Nour','nour','+201000000004','n@x.com','h',false,NULL,false,'R4',now())");
            s.execute("INSERT INTO iam.admin_account(id,email,password_hash,full_name,is_active) "
                    + "VALUES('" + A1 + "','admin@x.com','h','Admin',true)");
            s.execute("INSERT INTO geo.area(id,name,is_active) VALUES('" + AR + "','New Cairo',true),('" + AR2 + "','Maadi',true)");
            s.execute("INSERT INTO venue.venue(id,area_id,name,address,location,marketplace_status,timezone,phone) VALUES "
                    + "('" + V1 + "','" + AR + "','V1','addr',ST_GeogFromText('POINT(31.4 30.0)'),'ACTIVE','Africa/Cairo','+20223456781'),"
                    + "('" + V2 + "','" + AR2 + "','V2','addr',ST_GeogFromText('POINT(31.3 29.9)'),'INACTIVE','Africa/Cairo','+20223456782')");
            s.execute("INSERT INTO iam.venue_account(id,venue_id,login_identifier,password_hash,account_status,"
                    + "language_preference) VALUES('" + VA1 + "','" + V1 + "','v1','h','ENABLED','en'),"
                    + "('" + VA2 + "','" + V2 + "','v2','h','ENABLED','en')");
            s.execute("INSERT INTO venue.venue_activity(id,venue_id,activity) VALUES(gen_random_uuid(),'" + V1 + "','PLAYSTATION')");
            s.execute(session(V1, TPL, "'" + OS1 + "'"));
            s.execute("SET session_replication_role = replica");
            s.execute("INSERT INTO resource.resource(id,venue_id,activity,name,base_state,operational_restriction) "
                    + "VALUES('" + R1 + "','" + V1 + "','BILLIARDS','Table 1','ACTIVE','NONE')");
            s.execute("INSERT INTO trust.trust_projection(user_id,vp_operational_status,projection_version) "
                    + "VALUES('" + U1 + "','VERIFIED',0)");
            s.execute("SET session_replication_role = origin");
        }
    }

    private static Map<String, FunctionState> functionStates(String database) throws SQLException {
        Map<String, FunctionState> states = new LinkedHashMap<>();
        try (Connection c = connect(database); Statement s = c.createStatement()) {
            for (Map.Entry<String, String> f : FUNCTIONS.entrySet()) {
                String oid = "'" + f.getKey() + f.getValue() + "'::regprocedure";
                try (ResultSet rs = s.executeQuery("SELECT pg_get_functiondef(" + oid + "), "
                        + "concat_ws(' | ', p.proowner::regrole, p.proacl::text, p.prosecdef, p.proconfig::text, "
                        + "p.provolatile, p.proparallel, p.prolang, pg_get_function_identity_arguments(p.oid), "
                        + "p.prorettype::regtype) FROM pg_proc p WHERE p.oid = " + oid)) {
                    rs.next();
                    states.put(f.getKey(), new FunctionState(rs.getString(1), rs.getString(2)));
                }
            }
        }
        return states;
    }

    /** Every table, column, constraint, index and trigger of the model's schemas, as one comparable text. */
    private static String structure(String database) throws SQLException {
        String schemas = "n.nspname NOT IN ('pg_catalog','information_schema','flyway','pg_toast','public','topology','tiger')";
        Map<String, String> parts = new TreeMap<>();
        parts.put("columns", query(database, "SELECT string_agg(x, ',' ORDER BY x) FROM (SELECT concat_ws(':', "
                + "n.nspname, c.relname, a.attnum, a.attname, format_type(a.atttypid, a.atttypmod), a.attnotnull) x "
                + "FROM pg_attribute a JOIN pg_class c ON c.oid = a.attrelid JOIN pg_namespace n ON n.oid = c.relnamespace "
                + "WHERE a.attnum > 0 AND NOT a.attisdropped AND c.relkind IN ('r','p','v','m') AND " + schemas + ") q"));
        parts.put("constraints", query(database, "SELECT string_agg(x, ',' ORDER BY x) FROM (SELECT concat_ws(':', "
                + "n.nspname, co.conrelid::regclass, co.conname, pg_get_constraintdef(co.oid)) x FROM pg_constraint co "
                + "JOIN pg_namespace n ON n.oid = co.connamespace WHERE " + schemas + ") q"));
        parts.put("indexes", query(database, "SELECT string_agg(indexdef, ',' ORDER BY indexdef) FROM pg_indexes "
                + "WHERE schemaname NOT IN ('pg_catalog','information_schema','flyway','public','topology','tiger')"));
        parts.put("triggers", query(database, "SELECT string_agg(x, ',' ORDER BY x) FROM (SELECT concat_ws(':', "
                + "t.tgrelid::regclass, t.tgname, t.tgfoid::regprocedure, t.tgenabled, t.tgtype) x FROM pg_trigger t "
                + "WHERE NOT t.tgisinternal) q"));
        parts.put("functions", query(database, "SELECT count(*)::text FROM pg_proc p JOIN pg_namespace n "
                + "ON n.oid = p.pronamespace WHERE " + schemas));
        return parts.toString();
    }

    private static List<String> messages(String definition) {
        List<String> found = new ArrayList<>();
        Matcher m = Pattern.compile("RAISE EXCEPTION '((?:[^']|'')*)'").matcher(definition);
        while (m.find()) {
            found.add(m.group(1));
        }
        return found.stream().distinct().sorted().toList();
    }

    private static String query(String database, String sql) throws SQLException {
        try (Connection c = connect(database); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }

    private static Connection connect(String database) throws SQLException {
        return DriverManager.getConnection(PostgresTestContainer.jdbcUrl(database), "postgres", "postgres");
    }

    /** Same migrations and Flyway settings as the application (application.yml, spring.flyway.*). */
    private static org.flywaydb.core.api.configuration.FluentConfiguration flyway(String database) {
        return Flyway.configure()
                .dataSource(PostgresTestContainer.jdbcUrl(database), MIGRATOR, MIGRATOR)
                .initSql("SET ROLE postgres")
                .defaultSchema("flyway")
                .createSchemas(true)
                .locations("classpath:db/migration");
    }

    private static void psql(String sql) throws IOException, InterruptedException {
        ExecResult r = db.execInContainer("psql", "-U", "postgres", "-d", "postgres", "-v", "ON_ERROR_STOP=1", "-c", sql);
        if (r.getExitCode() != 0) {
            throw new IllegalStateException("psql failed: " + sql + "\n" + new String(r.getStderr().getBytes(),
                    StandardCharsets.UTF_8));
        }
    }

}
