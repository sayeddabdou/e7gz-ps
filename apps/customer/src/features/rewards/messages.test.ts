import { rewardsErrorMessages } from "./messages";

describe("rewards message catalogue", () => {
  it("maps every API error declared by the four customer read routes", () => {
    expect(Object.keys(rewardsErrorMessages).sort()).toEqual([
      "FORBIDDEN",
      "RATE_LIMITED",
      "UNAUTHENTICATED",
      "VALIDATION_FAILED",
    ]);
    expect(Object.values(rewardsErrorMessages).every(Boolean)).toBe(true);
  });
});
