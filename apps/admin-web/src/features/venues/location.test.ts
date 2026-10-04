import { describe, expect, it } from "vitest";
import { distanceKm, parseLocation } from "./location";

describe("venue location", () => {
  it.each([
    ["30.0561,31.3301", { lat: 30.0561, lng: 31.3301 }],
    ["https://www.google.com/maps/@30.0561,31.3301,16z", { lat: 30.0561, lng: 31.3301 }],
    ["https://maps.google.com/?q=30.0561,31.3301", { lat: 30.0561, lng: 31.3301 }],
  ])("parses %s without swapping latitude and longitude", (input, expected) => expect(parseLocation(input)).toEqual(expected));

  it.each([
    "junk",
    "meet here 30.0561,31.3301",
    "91,31",
    "30,181",
    "https://maps.app.goo.gl/short-link",
  ])("rejects unsupported location %s", (input) => {
    expect(parseLocation(input)).toBeUndefined();
  });

  it("can warn when a point is more than five kilometres from an area centroid", () => {
    expect(distanceKm({ lat: 30.0561, lng: 31.3301 }, { lat: 30.15, lng: 31.33 })).toBeGreaterThan(5);
  });
});
