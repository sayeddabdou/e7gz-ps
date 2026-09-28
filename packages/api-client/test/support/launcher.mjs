// Starts the real `pnpm mock` launcher (scripts/mock.mjs) the way the root script does, for the mock tests.
import { spawn } from "node:child_process";
import { fileURLToPath } from "node:url";
import path from "node:path";

export const packageDir = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../..");
export const launcher = path.join(packageDir, "scripts/mock.mjs");
export const sampleContract = path.resolve(packageDir, "../../backend/build/generated-contracts/sample.openapi.json");

/** Runs the launcher to completion (for the refusal cases). */
export function runLauncher(args, { timeoutMs = 30_000 } = {}) {
  return new Promise((resolve, reject) => {
    const child = spawn(process.execPath, [launcher, ...args], { cwd: packageDir });
    let output = "";
    child.stdout.on("data", (chunk) => (output += chunk));
    child.stderr.on("data", (chunk) => (output += chunk));
    const timer = setTimeout(() => {
      child.kill();
      reject(new Error(`launcher did not exit within ${timeoutMs} ms:\n${output}`));
    }, timeoutMs);
    child.on("exit", (code) => {
      clearTimeout(timer);
      resolve({ code, output });
    });
  });
}

/**
 * Starts the launcher and resolves once Prism reports it is listening. The test's `t.after` stops it and waits
 * for the process to exit.
 */
export function startLauncher(t, args, { timeoutMs = 60_000 } = {}) {
  return new Promise((resolve, reject) => {
    const child = spawn(process.execPath, [launcher, ...args], { cwd: packageDir });
    const exited = new Promise((done) => child.on("exit", (code) => done(code)));
    t.after(async () => {
      if (child.exitCode === null) {
        child.kill();
      }
      await exited;
    });
    let output = "";
    const timer = setTimeout(() => reject(new Error(`mock not ready within ${timeoutMs} ms:\n${output}`)), timeoutMs);
    const onData = (chunk) => {
      output += chunk;
      const listening = /Prism is listening on (http:\/\/[^\s]+)/.exec(output);
      if (listening) {
        clearTimeout(timer);
        resolve({ baseUrl: listening[1].replace(/\/$/, ""), child, exited, output: () => output });
      }
    };
    child.stdout.on("data", onData);
    child.stderr.on("data", onData);
    child.on("exit", (code) => {
      clearTimeout(timer);
      reject(new Error(`launcher exited with ${code} before it was ready:\n${output}`));
    });
  });
}
