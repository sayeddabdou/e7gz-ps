import { describe, expect, it } from "vitest";

const productionSources = import.meta.glob(["./**/*.ts", "./**/*.tsx", "./**/*.css", "!./**/*.test.ts", "!./**/*.test.tsx"], {
  eager: true,
  import: "default",
  query: "?raw",
}) as Record<string, string>;

describe("Arabic RTL source review", () => {
  it("uses logical properties and avoids blanket icon flipping", () => {
    const source = Object.entries(productionSources)
      .map(([path, contents]) => `${path}\n${contents}`)
      .join("\n");

    expect(source).not.toMatch(/\b(?:margin|padding|border)(?:Left|Right)\b/u);
    expect(source).not.toMatch(/\b(?:margin|padding|border)-(?:left|right)\b/u);
    expect(source).not.toMatch(/\b(?:left|right)\s*:/u);
    expect(source).not.toContain("scaleX(");
  });
});
