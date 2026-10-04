import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { LocationPicker } from "./LocationPicker";

afterEach(cleanup);
describe("LocationPicker", () => {
  it("uses OpenStreetMap tiles with visible attribution and no API key", () => {
    render(<LocationPicker value={{ lat: 30.0561, lng: 31.3301 }} onChange={() => undefined} />);
    expect(screen.getByText(/OpenStreetMap contributors/)).toBeTruthy();
    expect(document.querySelector<HTMLImageElement>(".leaflet-tile")?.src).toContain("tile.openstreetmap.org");
    expect(document.body.innerHTML).not.toMatch(/api[_-]?key/i);
  });

  it("keeps the same map instance when the selected pin changes", () => {
    const { rerender } = render(<LocationPicker value={{ lat: 30.0561, lng: 31.3301 }} onChange={() => undefined} />);
    const map = screen.getByLabelText("Venue location map");
    const originalPane = map.querySelector(".leaflet-map-pane");

    rerender(<LocationPicker value={{ lat: 30.0444, lng: 31.2357 }} onChange={() => undefined} />);

    expect(map.querySelector(".leaflet-map-pane")).toBe(originalPane);
  });
});
