import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { afterEach, describe, expect, it } from "vitest";
import { createAdminApiClient } from "../../api";
import { AlertsScreen } from "../alerts";
import { EmergenciesScreen } from "../emergencies";
import { OwnerReviewsScreen } from "../owner-review";
import { VenueAccountsScreen } from "../venue-accounts";

afterEach(cleanup);
const meta = { correlationId: "c-1", serverTime: "2026-11-25T10:00:00Z" };
const json = (status: number, body: unknown) => new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

describe("E7-86 Admin operations", () => {
  it("keeps the disable idempotency key for a retry and renews it when the reason changes", async () => {
    const writes: Request[] = []; let writeAttempt = 0;
    const account = { id: "a-1", venueId: "v-1", venueName: "Arena", loginIdentifier: "arena", accountStatus: "ENABLED", languagePreference: "en" };
    const api = createAdminApiClient({ apiBaseUrl: "http://api" }, () => "token", async (request) => {
      if (request.method === "GET") return json(200, { data: { items: [account], nextCursor: null }, meta });
      writes.push(request.clone()); writeAttempt += 1;
      if (writeAttempt === 1 || writeAttempt === 3) throw new TypeError("network");
      return json(200, { data: { ...account, accountStatus: "DISABLED" }, meta });
    });
    render(<MemoryRouter><VenueAccountsScreen api={api} /></MemoryRouter>);
    fireEvent.click(await screen.findByRole("button", { name: "Disable account" }));
    expect(screen.getByText(/returns to the customer's Wallet or as Points/i)).toBeTruthy();
    expect(screen.queryByText(/card/i)).toBeNull();
    fireEvent.change(screen.getByLabelText("Reason"), { target: { value: "Owner request" } });
    fireEvent.click(screen.getAllByRole("button", { name: "Disable account" })[1]!);
    await screen.findByText("The server could not be reached.");
    fireEvent.click(screen.getAllByRole("button", { name: "Disable account" })[1]!);
    await screen.findByText("Arena was disabled.");
    expect(writes[0]!.headers.get("Idempotency-Key")).toBe(writes[1]!.headers.get("Idempotency-Key"));

    cleanup(); writeAttempt = 2;
    render(<MemoryRouter><VenueAccountsScreen api={api} /></MemoryRouter>);
    fireEvent.click(await screen.findByRole("button", { name: "Disable account" }));
    fireEvent.change(screen.getByLabelText("Reason"), { target: { value: "First reason" } });
    fireEvent.click(screen.getAllByRole("button", { name: "Disable account" })[1]!);
    await screen.findByText("The server could not be reached.");
    const firstKey = writes[2]!.headers.get("Idempotency-Key");
    fireEvent.change(screen.getByLabelText("Reason"), { target: { value: "Changed reason" } });
    fireEvent.click(screen.getAllByRole("button", { name: "Disable account" })[1]!);
    await waitFor(() => expect(writes).toHaveLength(4));
    expect(writes[3]!.headers.get("Idempotency-Key")).not.toBe(firstKey);
  });

  it("keeps owner reviews and emergencies read-only and links rows to the venue", async () => {
    const api = createAdminApiClient({ apiBaseUrl: "http://api" }, () => "token", async (request) => request.url.includes("owner-reviews")
      ? json(200, { data: { items: [{ id: "r-1", venueId: "v-1", venueName: "Arena", resourceId: "room-1", resourceName: "Room 1", enteredAt: "2026-11-25T10:00:00Z", resolution: null, resolvedAt: null }], nextCursor: null }, meta })
      : json(200, { data: { items: [{ id: "e-1", venueId: "v-1", venueName: "Arena", resourceId: null, resourceName: null, scope: "VENUE", status: "ACTIVE", reason: "Power cut", declaredAt: "2026-11-25T10:00:00Z", expectedEndAt: "2026-11-25T12:00:00Z", endedAt: null, convertedTo: null }], nextCursor: null }, meta }));
    const view = render(<MemoryRouter><OwnerReviewsScreen api={api} /></MemoryRouter>);
    expect((await screen.findByRole("link", { name: "Arena" })).getAttribute("href")).toBe("/venues/v-1");
    expect(screen.queryByRole("button", { name: /resolve/i })).toBeNull();
    view.rerender(<MemoryRouter><EmergenciesScreen api={api} /></MemoryRouter>);
    expect(await screen.findByText("Power cut")).toBeTruthy();
    expect(screen.queryByRole("button", { name: /end|convert|resolve/i })).toBeNull();
  });

  it("links an Owner PIN long-lock alert to its venue and only offers acknowledgement", async () => {
    const requests: Request[] = [];
    const alert = { id: "al-1", kind: "OWNER_PASSWORD_LONG_LOCK", severity: "SEV_2", subjectType: "VENUE_ACCOUNT", subjectId: "a-1", payload: { lockouts: 3 }, correlationId: "c-1", createdAt: "2026-11-25T10:00:00Z", acknowledgedAt: null, acknowledgedByAdminId: null };
    const api = createAdminApiClient({ apiBaseUrl: "http://api" }, () => "token", async (request) => { requests.push(request); if (request.url.includes("venue-accounts")) return json(200, { data: { items: [{ id: "a-1", venueId: "v-1", venueName: "Arena", loginIdentifier: "arena", accountStatus: "ENABLED", languagePreference: "en" }], nextCursor: null }, meta }); if (request.method === "POST") return json(200, { data: { ...alert, acknowledgedAt: "2026-11-25T10:10:00Z", acknowledgedByAdminId: "admin-1" }, meta }); return json(200, { data: { items: [alert], nextCursor: null }, meta }); });
    render(<MemoryRouter><AlertsScreen api={api} /></MemoryRouter>);
    expect((await screen.findByRole("link", { name: "Open venue" })).getAttribute("href")).toBe("/venues/v-1");
    expect(screen.queryByRole("button", { name: /delete/i })).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "Acknowledge" }));
    fireEvent.change(screen.getByLabelText("Acknowledgement note (optional)"), { target: { value: "Support contacted" } });
    fireEvent.click(screen.getAllByRole("button", { name: "Acknowledge" })[1]!);
    await waitFor(() => expect(requests.some((request) => request.method === "POST")).toBe(true));
    expect(screen.queryByText("Owner PIN long-locked")).toBeNull();
  });

  it("renders alert payload facts as readable labels without exposing internal exceptions", async () => {
    const alert = {
      id: "al-2", kind: "OUTBOX_DEAD_LETTER", severity: "SEV_2", subjectType: "OTP_CHALLENGE", subjectId: "otp-1",
      payload: {
        eventId: 69,
        eventType: "PhoneOtpIssued",
        aggregateType: "OTP_CHALLENGE",
        aggregateId: "otp-1",
        correlationId: "correlation-1",
        lastError: "ps.e7gz.messaging.api.PermanentDeliveryFailure: internal implementation detail",
      },
      correlationId: "correlation-1", createdAt: "2026-11-25T10:00:00Z", acknowledgedAt: null, acknowledgedByAdminId: null,
    };
    const api = createAdminApiClient({ apiBaseUrl: "http://api" }, () => "token", async (request) => request.url.includes("venue-accounts")
      ? json(200, { data: { items: [], nextCursor: null }, meta })
      : json(200, { data: { items: [alert], nextCursor: null }, meta }));

    render(<MemoryRouter><AlertsScreen api={api} /></MemoryRouter>);

    expect(await screen.findByText("Event type")).toBeTruthy();
    expect(screen.getByText("Phone OTP issued")).toBeTruthy();
    expect(screen.getByText("Aggregate type")).toBeTruthy();
    expect(screen.getByText("OTP challenge")).toBeTruthy();
    expect(screen.getByText("Correlation ID")).toBeTruthy();
    expect(screen.queryByText(/PermanentDeliveryFailure|internal implementation detail/u)).toBeNull();
  });
});
