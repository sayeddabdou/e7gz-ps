package ps.e7gz.support;

import static ps.e7gz.support.PostgresTestContainer.APP_RUNTIME;
import static ps.e7gz.support.PostgresTestContainer.DATABASE;
import static ps.e7gz.support.PostgresTestContainer.MIGRATOR;

import java.util.UUID;
import org.postgresql.Driver;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import ps.e7gz.common.command.CommandContext;

/**
 * TEST-ONLY: a transaction that fails at COMMIT, as the database's deferred guards do (a {@code DEFERRABLE INITIALLY
 * DEFERRED} constraint trigger), raising {@code P0001} with a Product guard identifier as CONSTRAINT - or with none, an
 * internal invariant. Created by the owner role and dropped on close; no production trigger is touched or disabled.
 */
public final class CommitFault implements AutoCloseable {

    public static final String MESSAGE = "test-injected commit fault";

    private static final JdbcClient OWNER = JdbcClient.create(new SimpleDriverDataSource(new Driver(),
            PostgresTestContainer.jdbcUrl(DATABASE), MIGRATOR, MIGRATOR));

    private final String name;
    private final String table;
    private final boolean scratch;

    private CommitFault(String name, String table, boolean scratch) {
        this.name = name;
        this.table = table;
        this.scratch = scratch;
    }

    /**
     * Fails the COMMIT of any transaction that inserts or updates a row of {@code table} whose {@code column} is
     * {@code value} - so only this test's rows, never another test's.
     *
     * @param guard the {@code gd_…} identifier raised as CONSTRAINT; {@code null} for none
     */
    public static CommitFault onRowsOf(String table, String column, UUID value, String guard) {
        String name = uniqueName();
        createFunction(name, guard);
        OWNER.sql("CREATE CONSTRAINT TRIGGER " + name + " AFTER INSERT OR UPDATE ON " + table
                + " DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION public." + name
                + "('" + column + "', '" + value + "')").update();
        return new CommitFault(name, table, false);
    }

    /**
     * Fails the COMMIT of any transaction that calls {@link #armIn} on it: a scratch table of its own, which the app
     * runtime role may insert into.
     *
     * @param guard the {@code gd_…} identifier raised as CONSTRAINT; {@code null} for none
     */
    public static CommitFault scratch(String guard) {
        String name = uniqueName();
        String table = "public." + name;
        createFunction(name, guard);
        OWNER.sql("CREATE TABLE " + table + " (id integer)").update();
        OWNER.sql("GRANT INSERT ON " + table + " TO " + APP_RUNTIME).update();
        OWNER.sql("CREATE CONSTRAINT TRIGGER " + name + " AFTER INSERT ON " + table
                + " DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION public." + name + "()").update();
        return new CommitFault(name, table, true);
    }

    /** Writes the scratch row in the running command's transaction: it then fails at COMMIT. */
    public void armIn(CommandContext context) {
        if (!scratch) {
            throw new IllegalStateException("only a scratch CommitFault is armed by a write of its own");
        }
        context.jdbc().sql("INSERT INTO " + table + " (id) VALUES (1)").update();
    }

    @Override
    public void close() {
        if (scratch) {
            OWNER.sql("DROP TABLE IF EXISTS " + table).update();
        } else {
            OWNER.sql("DROP TRIGGER IF EXISTS " + name + " ON " + table).update();
        }
        OWNER.sql("DROP FUNCTION IF EXISTS public." + name + "()").update();
    }

    private static String uniqueName() {
        return "e7_test_commit_fault_" + UUID.randomUUID().toString().replace("-", "");
    }

    /** Raises for every row when the trigger has no arguments, else only when NEW's column equals the value. */
    private static void createFunction(String name, String guard) {
        String raise = guard == null ? "RAISE EXCEPTION '" + MESSAGE + "'"
                : "RAISE EXCEPTION USING ERRCODE = 'P0001', MESSAGE = '" + MESSAGE + "', CONSTRAINT = '" + guard + "'";
        OWNER.sql("CREATE FUNCTION public." + name + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                + "IF TG_NARGS = 0 OR to_jsonb(NEW) ->> TG_ARGV[0] = TG_ARGV[1] THEN " + raise + "; END IF; "
                + "RETURN NULL; END $$").update();
    }
}
