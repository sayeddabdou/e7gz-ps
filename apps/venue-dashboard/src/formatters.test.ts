import { describe, expect, it } from "vitest";
import { formatBusinessDate, formatCountdown, formatDateTime, formatInteger, formatMoney, formatTime } from "./formatters";

describe("Dashboard formatters", () => {
  it("formats numbers and EGP money consistently for English and Arabic", () => {
    const money = { amount: "1234.5", currency: "EGP" };

    expect(formatMoney(money, "en-US")).toBe("1,234.50 EGP");
    expect(formatInteger(1234, "en-US")).toBe("1,234");
    expect(formatMoney(money, "ar-EG")).toBe("١٬٢٣٤٫٥٠ EGP");
    expect(formatInteger(1234, "ar-EG")).toBe("١٬٢٣٤");
    expect(formatCountdown(65, "ar-EG")).toBe("٠١:٠٥");
  });

  it("formats server timestamps and business dates in Cairo without device-time defaults", () => {
    expect(formatDateTime("2026-10-12T15:04:00Z", "en-US")).toBe("Oct 12, 2026, 6:04 PM");
    expect(formatTime("2026-10-12T15:04:00Z", "en-US")).toBe("6:04 PM");
    expect(formatBusinessDate("2026-10-12", "en-US")).toBe("October 12, 2026");

    expect(formatDateTime("2026-10-12T15:04:00Z", "ar-EG")).toMatch(/[١٢]/u);
    expect(formatTime("2026-10-12T15:04:00Z", "ar-EG")).toMatch(/[٦٠٤]/u);
    expect(formatBusinessDate("2026-10-12", "ar-EG")).toMatch(/[١٢]/u);
  });
});
