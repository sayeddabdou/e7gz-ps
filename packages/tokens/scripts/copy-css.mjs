import { copyFile, mkdir } from "node:fs/promises";
import { URL } from "node:url";

await mkdir(new URL("../dist", import.meta.url), { recursive: true });
await copyFile(new URL("../src/tokens.css", import.meta.url), new URL("../dist/tokens.css", import.meta.url));
