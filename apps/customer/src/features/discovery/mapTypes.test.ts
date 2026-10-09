import { boundsAround, boundsDiffer } from "./mapTypes";

describe("map bounds", () => {
  it("centres the initial bounds on the supplied location", () => {
    const bounds = boundsAround(30.05, 31.25);
    expect(bounds.minLat).toBeCloseTo(29.95);
    expect(bounds.maxLat).toBeCloseTo(30.15);
    expect(bounds.minLng).toBeCloseTo(31.125);
    expect(bounds.maxLng).toBeCloseTo(31.375);
  });

  it("ignores tiny native map movements but accepts meaningful pans", () => {
    const current = { minLat: 30, maxLat: 31, minLng: 31, maxLng: 32 };
    expect(boundsDiffer(current, { minLat: 30.0001, maxLat: 31, minLng: 31, maxLng: 32 })).toBe(false);
    expect(boundsDiffer(current, { minLat: 30.001, maxLat: 31, minLng: 31, maxLng: 32 })).toBe(true);
  });
});
