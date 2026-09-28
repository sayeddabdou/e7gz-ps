// `pnpm test:chain`: the backend → OpenAPI → TypeScript chain (E7-11).
//   1. (--backend only) Gradle runs OpenApiSampleContractTest: sample Spring controllers/DTOs → springdoc →
//      backend/build/generated-contracts/sample.openapi.json. Needs Docker. In CI the file comes from the backend
//      job as an artifact instead.
//   2. openapi-typescript: that contract → test/generated/sample.ts
//   3. tsc -p tsconfig.chain.json: valid calls compile, wrong paths/params/bodies/enum spellings do not
//      (@ts-expect-error, which itself fails when the error disappears).
//   4. node --test test/chain/: the client against injected fetch, and the `pnpm mock` launcher on the sample contract.
import { existsSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import path from "node:path";

const packageDir = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const backend = path.resolve(packageDir, "../../backend");
const sampleContract = path.join(backend, "build/generated-contracts/sample.openapi.json");
const windows = process.platform === "win32";

if (process.argv.includes("--backend")) {
  // Absolute path: Windows may be set not to run programs from the current folder.
  const gradlew = windows ? `"${path.join(backend, "gradlew.bat")}"` : path.join(backend, "gradlew");
  run(gradlew, ["test", "--tests", "ps.e7gz.openapi.OpenApiSampleContractTest", "--quiet"], backend, windows);
}
if (!existsSync(sampleContract)) {
  console.error(`test:chain: ${sampleContract} is missing.`);
  console.error("Run `pnpm test:chain` from the repo root (it runs the backend test first), or `./gradlew test` in backend/.");
  process.exit(1);
}

const bin = (relative) => path.join(packageDir, "node_modules", relative);
run(process.execPath, [bin("openapi-typescript/bin/cli.js"), sampleContract, "-o", "test/generated/sample.ts"], packageDir);
run(process.execPath, [bin("typescript/bin/tsc"), "-p", "tsconfig.chain.json"], packageDir);
run(process.execPath, ["--experimental-strip-types", "--no-warnings", "--test", "test/chain/*.test.ts", "test/chain/*.test.mjs"], packageDir);

function run(command, args, cwd, shell = false) {
  const result = spawnSync(command, args, { cwd, stdio: "inherit", shell });
  if (result.status !== 0) {
    console.error(`test:chain failed: ${command} ${args.join(" ")}`);
    process.exit(result.status ?? 1);
  }
}
