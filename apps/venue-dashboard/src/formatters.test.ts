import { describe, expect, it } from "vitest";
import { formatBusinessDate, formatCountdown, formatDateTime, formatInteger, formatMoney, formatTime, instantToLocalDateTime, localDateTimeToInstant } from "./formatters";

describe("Dashboard formatters", () => {
  it("formats numbers and EGP money consistently for English and Arabic", () => {
    const money = { amount: "1234.5", currency: "EGP" };

    expect(formatMoney(money, "en-US")).toBe("1234.5 EGP");
    expect(formatInteger(1234, "en-US")).toBe("1,234");
    expect(formatMoney(money, "ar-EG")).toBe("١٢٣٤٫٥ EGP");
    expect(formatInteger(1234, "ar-EG")).toBe("١٬٢٣٤");
    expect(formatCountdown(65, "ar-EG")).toBe("٠١:٠٥");
  });

  it("preserves every API money digit without floating-point conversion or rounding", () => {
    expect(formatMoney({ amount: "1234.567890", currency: "EGP" }, "en-US")).toBe("1234.567890 EGP");
    expect(formatMoney({ amount: "1234.567890", currency: "EGP" }, "ar-EG")).toBe("١٢٣٤٫٥٦٧٨٩٠ EGP");
  });

  it("formats server timestamps and business dates in Cairo without device-time defaults", () => {
    expect(formatDateTime("2026-10-12T15:04:00Z", "en-US")).toBe("Oct 12, 2026, 6:04 PM");
    expect(formatTime("2026-10-12T15:04:00Z", "en-US")).toBe("6:04 PM");
    expect(formatBusinessDate("2026-10-12", "en-US")).toBe("October 12, 2026");

    expect(formatDateTime("2026-10-12T15:04:00Z", "ar-EG")).toMatch(/[١٢]/u);
    expect(formatTime("2026-10-12T15:04:00Z", "ar-EG")).toMatch(/[٦٠٤]/u);
    expect(formatBusinessDate("2026-10-12", "ar-EG")).toMatch(/[١٢]/u);
  });

  it("round-trips venue-local form values without using the device time zone", () => {
    expect(instantToLocalDateTime("2026-12-08T08:00:00Z", "Africa/Cairo")).toBe("2026-12-08T10:00");
    expect(localDateTimeToInstant("2026-12-08T10:00", "Africa/Cairo")).toBe("2026-12-08T08:00:00.000Z");
    expect(instantToLocalDateTime("2026-12-08T08:00:00Z", "UTC")).toBe("2026-12-08T08:00");
  });
});
