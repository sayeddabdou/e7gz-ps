// `pnpm mock`: a Prism mock server for the committed contract (openapi.json), so the apps can run before the
// backend exists. Answers with each response's example, or one Prism builds from the schema.
//
//   pnpm mock                          serves packages/api-client/openapi.json on http://127.0.0.1:4010
//   pnpm mock --port 4020 --dynamic    other options go to Prism unchanged
//   pnpm mock --spec <file>            serves another OpenAPI file instead (tests use the sample contract)
//
// Prism runs inside this process, so stopping the launcher stops the server too. Ready when Prism prints
// "Prism is listening on http://…". Exits non-zero when the contract is missing, has no endpoints, or Prism fails.
import { existsSync, readFileSync } from "node:fs";
import { createRequire } from "node:module";
import { fileURLToPath, pathToFileURL } from "node:url";
import path from "node:path";

const packageDir = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const productionContract = path.join(packageDir, "openapi.json");

const { spec, prismArgs } = parseArgs(process.argv.slice(2));
const specPath = spec ? path.resolve(spec) : productionContract;

if (!existsSync(specPath)) {
  fail(`mock: contract not found: ${specPath}`);
}
let contract;
try {
  contract = JSON.parse(readFileSync(specPath, "utf8"));
} catch (error) {
  fail(`mock: ${specPath} is not valid JSON (${error.message})`);
}
// Prism refuses a contract without operations; say why instead of printing its stack trace.
if (Object.keys(contract.paths ?? {}).length === 0) {
  fail(
    specPath === productionContract
      ? "mock: openapi.json has no endpoints — regenerate it with `pnpm api:gen`."
      : `mock: ${specPath} has no endpoints.`,
  );
}

const defaults = [];
if (!hasOption(prismArgs, "--host", "-h")) defaults.push("--host", "127.0.0.1");
if (!hasOption(prismArgs, "--port", "-p")) defaults.push("--port", "4010");

const require = createRequire(import.meta.url);
const prismPackage = require.resolve("@stoplight/prism-cli/package.json");
const prismBin = path.join(path.dirname(prismPackage), require(prismPackage).bin.prism);

// Prism's CLI reads process.argv when it loads.
process.argv = [process.execPath, prismBin, "mock", specPath, ...defaults, ...prismArgs];
await import(pathToFileURL(prismBin).href);

function parseArgs(args) {
  const rest = [];
  let specArg;
  for (let i = 0; i < args.length; i++) {
    const arg = args[i];
    if (arg === "--spec") {
      specArg = args[++i];
      if (!specArg) fail("mock: --spec needs a file");
    } else if (arg.startsWith("--spec=")) {
      specArg = arg.slice("--spec=".length);
    } else {
      rest.push(arg);
    }
  }
  return { spec: specArg, prismArgs: rest };
}

function hasOption(args, long, short) {
  return args.some((arg) => arg === long || arg === short || arg.startsWith(`${long}=`));
}

function fail(message) {
  console.error(message);
  process.exit(1);
}
