import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { createAdminApiClient } from "../../api";
import { UserDetailScreen } from "./UsersScreen";

afterEach(cleanup);
const meta = { correlationId: "c-1", serverTime: "2026-11-17T10:00:00Z" };
const json = (body: unknown) => new Response(JSON.stringify(body), { status: 200, headers: { "Content-Type": "application/json" } });

describe("UserDetailScreen", () => {
  it("shows trust, ban and points history and idempotently adjusts trust with a reason", async () => {
    const requests: Request[] = [];
    const api = createAdminApiClient({ apiBaseUrl: "http://api" }, () => "token", async (request: Request) => {
      requests.push(request);
      if (request.url.includes("/points?")) return json({ data: { account: { userId: "11111111-1111-4111-8111-111111111111", username: "omar", fullName: "Omar", balance: 300, bookingRewards: 500, referralRewards: -200 }, items: [{ transactionId: "t-1", userId: "11111111-1111-4111-8111-111111111111", amount: -200, requestedAmount: -200, unrecoveredAmount: 0, balanceAfter: 300, cause: "BOOKING_FEE_PAYMENT", occurredAt: "2026-11-17T09:00:00Z", adminId: null, bookingId: "22222222-2222-4222-8222-222222222222", referralId: null, reason: null }], nextCursor: null }, meta });
      if (request.method === "POST") return json({ data: { userId: "11111111-1111-4111-8111-111111111111", consecutiveNoShows: 0, penaltyStage: 0, currentThreshold: 3, activeBan: null }, meta });
      return json({ data: { user: { userId: "11111111-1111-4111-8111-111111111111", username: "omar", fullName: "Omar", phone: "+201000000001", phoneVerified: true, email: "o@example.com", deleted: false, joinedAt: "2026-10-01T00:00:00Z", completedBookings: 12, consecutiveNoShows: 1, penaltyStage: 0, banned: true, verifiedPlayerStatus: "VERIFIED" }, emailVerified: false, verifiedPlayerQualification: "QUALIFIED", currentThreshold: 3, activeBan: { banId: "33333333-3333-4333-8333-333333333333", penaltyStage: 0, activatedAt: "2026-11-10T00:00:00Z", endsAt: "2026-11-24T00:00:00Z" }, pointsBalance: 300, walletBalance: { amount: "0.00", currency: "EGP" }, referralCode: "ABC123", termsAcceptance: { version: "2026-11-15", acceptedAt: "2026-11-17T10:00:00Z" } }, meta });
    });
    render(<MemoryRouter initialEntries={["/users/11111111-1111-4111-8111-111111111111"]}><Routes><Route path="/users/:userId" element={<UserDetailScreen api={api} />} /></Routes></MemoryRouter>);
    expect(await screen.findByRole("heading", { name: "omar" })).toBeTruthy();
    expect(await screen.findByText("BOOKING FEE PAYMENT")).toBeTruthy();
    expect(screen.getByText("Version 2026-11-15")).toBeTruthy();
    expect(screen.getByText(/Accepted/)).toBeTruthy();
    expect(screen.getByRole("button", { name: "Lift ban" })).toBeTruthy();
    expect(screen.getByRole("button", { name: "Adjust trust" }).hasAttribute("disabled")).toBe(true);
    fireEvent.change(screen.getAllByLabelText("Reason")[0]!, { target: { value: "Venue evidence corrected" } });
    fireEvent.click(screen.getByRole("button", { name: "Adjust trust" }));
    await waitFor(() => expect(requests.some((request) => request.method === "POST")).toBe(true));
    expect(requests.find((request) => request.method === "POST")!.headers.get("Idempotency-Key")).toBeTruthy();
  });

  it("shows when an older account has no recorded Terms or Privacy acceptance", async () => {
    const api = createAdminApiClient({ apiBaseUrl: "http://api" }, () => "token", async (request: Request) => {
      if (request.url.includes("/points?")) return json({ data: { account: { userId: "11111111-1111-4111-8111-111111111111", username: "legacy", fullName: "Legacy User", balance: 0, bookingRewards: 0, referralRewards: 0 }, items: [], nextCursor: null }, meta });
      return json({ data: { user: { userId: "11111111-1111-4111-8111-111111111111", username: "legacy", fullName: "Legacy User", phone: "+201000000001", phoneVerified: true, email: "legacy@example.com", deleted: false, joinedAt: "2026-10-01T00:00:00Z", completedBookings: 0, consecutiveNoShows: 0, penaltyStage: 0, banned: false, verifiedPlayerStatus: "NOT_VERIFIED" }, emailVerified: false, verifiedPlayerQualification: "NOT_QUALIFIED", currentThreshold: 3, activeBan: null, pointsBalance: 0, walletBalance: { amount: "0.00", currency: "EGP" }, referralCode: "LEGACY01", termsAcceptance: null }, meta });
    });
    render(<MemoryRouter initialEntries={["/users/11111111-1111-4111-8111-111111111111"]}><Routes><Route path="/users/:userId" element={<UserDetailScreen api={api} />} /></Routes></MemoryRouter>);
    expect(await screen.findByRole("heading", { name: "legacy" })).toBeTruthy();
    expect(screen.getByText("Nothing recorded")).toBeTruthy();
    expect(screen.getByText("This account predates acceptance recording.")).toBeTruthy();
  });
});
