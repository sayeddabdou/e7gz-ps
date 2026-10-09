import { discoveryErrorMessage } from "./errorMessages";

describe("discovery error messages", () => {
  it("maps availability validation fields to specific customer messages", () => {
    expect(discoveryErrorMessage("VALIDATION_FAILED", "durationMinutes")).toBe("Choose a supported duration and try again.");
    expect(discoveryErrorMessage("VALIDATION_FAILED", "startTime")).toBe("Choose a valid start time and try again.");
  });

  it("keeps a safe generic message for other validation fields", () => {
    expect(discoveryErrorMessage("VALIDATION_FAILED", "areaId")).toBe("Check the selected filters and try again.");
  });
});
