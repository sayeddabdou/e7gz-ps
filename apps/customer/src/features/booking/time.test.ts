import { cairoLocalToInstant, countdownSeconds, countdownSecondsAt, formatCountdown, serverClockOffset } from "./time";

describe("booking time helpers", () => {
  it("converts the selected Cairo wall time without using the device timezone", () => {
    expect(cairoLocalToInstant("2026-10-12", "18:00")).toBe("2026-10-12T15:00:00.000Z");
    expect(cairoLocalToInstant("2026-12-12", "18:00")).toBe("2026-12-12T16:00:00.000Z");
  });

  it("rejects invalid or nonexistent local values", () => {
    expect(cairoLocalToInstant("", "18:00")).toBeUndefined();
    expect(cairoLocalToInstant("2026-02-30", "18:00")).toBeUndefined();
    expect(cairoLocalToInstant("2026-10-12", "25:00")).toBeUndefined();
  });

  it("derives the countdown from server instants", () => {
    expect(countdownSeconds("2026-10-12T15:10:00Z", "2026-10-12T15:08:29.250Z")).toBe(91);
    expect(formatCountdown(91)).toBe("01:31");
    expect(countdownSeconds("2026-10-12T15:08:00Z", "2026-10-12T15:08:29Z")).toBe(0);
  });

  it("recomputes from the advancing server-aligned clock instead of counting interval ticks", () => {
    const deviceAtResponse = Date.parse("2026-10-12T12:00:00Z");
    const offset = serverClockOffset("2026-10-12T15:08:30Z", deviceAtResponse);

    expect(countdownSecondsAt("2026-10-12T15:10:00Z", deviceAtResponse + offset)).toBe(90);
    expect(countdownSecondsAt("2026-10-12T15:10:00Z", deviceAtResponse + 65_000 + offset)).toBe(25);
  });
});
