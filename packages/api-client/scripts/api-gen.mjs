// `pnpm api:gen`: backend controllers → openapi.json (springdoc, via Gradle) → src/generated/schema.ts.
// Needs Docker running: the backend context starts against a Testcontainers database.
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import path from "node:path";

const packageDir = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const backend = path.resolve(packageDir, "../../backend");
const windows = process.platform === "win32";

// Absolute path: Windows may be set not to run programs from the current folder.
const gradlew = windows ? `"${path.join(backend, "gradlew.bat")}"` : path.join(backend, "gradlew");
run(gradlew, ["generateOpenApi", "--quiet"], backend, windows);

// Its bin is not in the package exports, so it is found through node_modules (pnpm links it there).
const generator = path.join(packageDir, "node_modules/openapi-typescript/bin/cli.js");
run(process.execPath, [generator, "openapi.json", "-o", "src/generated/schema.ts"], packageDir, false);

function run(command, args, cwd, shell) {
  const result = spawnSync(command, args, { cwd, stdio: "inherit", shell });
  if (result.status !== 0) {
    console.error(`api:gen failed: ${command} ${args.join(" ")}`);
    process.exit(result.status ?? 1);
  }
}
