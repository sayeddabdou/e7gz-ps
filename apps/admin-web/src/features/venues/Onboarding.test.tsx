import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { MemoryRouter } from "react-router-dom";
import type { ApiClient } from "../../api";

vi.mock("./VenueForm", () => ({
  VenueForm: ({ onSaved }: { onSaved: (venue: { id: string }) => void }) => <button type="button" onClick={() => onSaved({ id: "v-1" })}>Create test venue</button>,
}));
vi.mock("./AdminResourceManager", () => ({ AdminResourceManager: () => <h3>Resources integration</h3> }));
vi.mock("./AdminPhotoManager", () => ({ AdminPhotoManager: () => <h3>Photos integration</h3> }));
vi.mock("../venue-accounts/VenueAccountPanel", () => ({ VenueAccountPanel: () => <h3>Account integration</h3> }));

import { Onboarding } from "./Onboarding";

afterEach(cleanup);

describe("Onboarding", () => {
  it("continues from venue creation to resources, photos, account, and review", () => {
    render(<MemoryRouter><Onboarding api={{} as ApiClient} /></MemoryRouter>);
    expect(screen.queryByText(/initial pricing/i)).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "Create test venue" }));
    expect(screen.getByRole("heading", { name: "Resources integration" })).toBeTruthy();
    expect(screen.getByRole("heading", { name: "Photos integration" })).toBeTruthy();
    expect(screen.getByRole("heading", { name: "Account integration" })).toBeTruthy();
    expect(screen.queryByText(/initial pricing/i)).toBeNull();
    expect(screen.getByRole("link", { name: "Review venue and complete onboarding" }).getAttribute("href")).toBe("/venues/v-1");
  });
});
