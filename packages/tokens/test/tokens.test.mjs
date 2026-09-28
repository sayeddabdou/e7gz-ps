import { readFile } from "node:fs/promises";
import { URL } from "node:url";
import test from "node:test";
import assert from "node:assert/strict";
import { color, radius, spacing } from "../dist/index.js";
import { nativeLogicalSpacing, nativeTokens } from "../dist/native.js";
import { logicalSpacing, webShadow } from "../dist/web.js";

test("keeps prototype spot-check values in the shared primitives", () => {
  assert.equal(color.brand.primary, "#1668E3");
  assert.equal(color.brand.venue, "#1656E8");
  assert.equal(spacing["3xl"], 16);
  assert.equal(radius["4xl"], 18);
  assert.equal(webShadow.action, "0 8px 18px rgba(22, 104, 227, 0.28)");
});

test("exports logical spacing for web and native", () => {
  assert.deepEqual(logicalSpacing.marginInlineStart(12), { marginInlineStart: 12 });
  assert.deepEqual(logicalSpacing.marginInlineEnd(12), { marginInlineEnd: 12 });
  assert.deepEqual(nativeLogicalSpacing.marginInlineStart(12), { marginStart: 12 });
  assert.deepEqual(nativeLogicalSpacing.marginInlineEnd(12), { marginEnd: 12 });
});

test("native tokens contain no CSS-only values", () => {
  const serialized = JSON.stringify(nativeTokens);
  assert.doesNotMatch(serialized, /var\(|px\b|rgba?\(|box-shadow|margin-inline/u);
});

test("build includes CSS variables and logical-property helpers", async () => {
  const css = await readFile(new URL("../dist/tokens.css", import.meta.url), "utf8");
  assert.match(css, /--e7gz-color-brand-primary:\s*#1668e3/iu);
  assert.match(css, /margin-inline-start:/u);
  assert.match(css, /margin-inline-end:/u);
  assert.doesNotMatch(css, /margin-left:|margin-right:/u);
});
