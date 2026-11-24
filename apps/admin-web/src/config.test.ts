import { describe, expect, it } from "vitest";
import { readConfig } from "./config";

describe("Platform Admin configuration", () => {
  it("requires both public legal URLs and reads them from Vite settings", () => {
    expect(readConfig({ VITE_API_BASE_URL: "http://localhost:8080" })).toEqual({
      ok: false,
      missing: ["VITE_LEGAL_PRIVACY_URL", "VITE_LEGAL_TERMS_URL"],
    });
    expect(readConfig({
      VITE_API_BASE_URL: " http://localhost:8080 ",
      VITE_LEGAL_PRIVACY_URL: " https://legal.example/privacy ",
      VITE_LEGAL_TERMS_URL: " https://legal.example/terms ",
    })).toEqual({
      ok: true,
      config: {
        apiBaseUrl: "http://localhost:8080",
        privacyPolicyUrl: "https://legal.example/privacy",
        termsUrl: "https://legal.example/terms",
      },
    });
  });

  it("rejects legal links that are not public HTTPS URLs", () => {
    expect(readConfig({
      VITE_API_BASE_URL: "http://localhost:8080",
      VITE_LEGAL_PRIVACY_URL: "http://legal.example/privacy",
      VITE_LEGAL_TERMS_URL: "javascript:alert(1)",
    })).toEqual({
      ok: false,
      missing: ["VITE_LEGAL_PRIVACY_URL", "VITE_LEGAL_TERMS_URL"],
    });
  });
});
