# tools/db-tests/

The database contract tests — the physical model's own scripts in `docs/04-erd/physical/test_*_v127.sh`
(behaviour, tournament, privileges; 124 checks) — run on every build (E7-10).

They are **not copied here**: `backend/src/test/java/ps/e7gz/database/DatabaseContractTest.java` builds a database with
our Flyway migrations inside the Testcontainers PostgreSQL, gives each script its own fresh copy, runs the script
unchanged from `docs/` and turns every `PASS`/`FAIL` line into a JUnit test. Run them with:

```bash
cd backend && ./gradlew test --tests '*DatabaseContractTest*'
```

When the model gets a new version, update the script names in `DatabaseContractTest.SCRIPTS`.

Owner task: **E7-10**. Owner: Sayed.
