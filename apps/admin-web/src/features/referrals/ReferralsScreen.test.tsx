import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { createAdminApiClient } from "../../api";
import { ReferralsScreen } from "./ReferralsScreen";

afterEach(cleanup);
const meta = { correlationId: "c-1", serverTime: "2026-11-17T10:00:00Z" };
const json = (status: number, body: unknown) => new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

describe("ReferralsScreen", () => {
  it("filters referrals and reports applied and unrecovered reversal amounts", async () => {
    const requests: Request[] = [];
    const referral = { referralId: "11111111-1111-4111-8111-111111111111", code: "ABC123", referrerUserId: "22222222-2222-4222-8222-222222222222", referrerUsername: "sara", referredUserId: "33333333-3333-4333-8333-333333333333", referredUsername: "omar", signedUpAt: "2026-11-01T00:00:00Z", status: "REWARDED", rewardPointsApplied: 1000, rewardedAt: "2026-11-01T00:05:00Z", invalidatedAt: null, invalidationKind: null };
    const api = createAdminApiClient({ apiBaseUrl: "http://api" }, () => "token", async (request: Request) => {
      requests.push(request);
      if (request.method === "POST") return json(200, { data: { referral: { ...referral, status: "INVALID", invalidatedAt: "2026-11-17T10:00:00Z", invalidationKind: "ADMIN_FRAUD" }, reversal: { transactionId: "t-1", userId: referral.referrerUserId, amount: -300, requestedAmount: -1000, unrecoveredAmount: 700, balanceAfter: 0, cause: "COMPENSATION", occurredAt: "2026-11-17T10:00:00Z", adminId: "a-1", bookingId: null, referralId: referral.referralId, reason: "Fraud" } }, meta });
      return json(200, { data: { items: [referral], nextCursor: null }, meta });
    });
    render(<ReferralsScreen api={api} />);
    fireEvent.change(await screen.findByLabelText("Status"), { target: { value: "REWARDED" } });
    fireEvent.change(screen.getByLabelText("Search users"), { target: { value: "omar" } });
    fireEvent.click(screen.getByRole("button", { name: "Search" }));
    await waitFor(() => expect(requests.at(-1)!.url).toContain("status=REWARDED"));
    expect(requests.at(-1)!.url).toContain("q=omar");

    fireEvent.click(screen.getByRole("button", { name: "Invalidate" }));
    fireEvent.change(screen.getByLabelText("Reason"), { target: { value: "Fraud" } });
    fireEvent.click(screen.getByRole("button", { name: "Confirm invalidation" }));
    expect((await screen.findByRole("status")).textContent).toContain("1,000 points requested");
    expect(screen.getByRole("status").textContent).toContain("700 unrecovered");
  });

  it("closes the invalidation form and reloads after ADMIN_ACTION_NOT_ALLOWED", async () => {
    let gets = 0;
    const referral = { referralId: "11111111-1111-4111-8111-111111111111", code: "ABC123", referrerUserId: "22222222-2222-4222-8222-222222222222", referrerUsername: "sara", referredUserId: "33333333-3333-4333-8333-333333333333", referredUsername: "omar", signedUpAt: "2026-11-01T00:00:00Z", status: "PENDING", rewardPointsApplied: null, rewardedAt: null, invalidatedAt: null, invalidationKind: null };
    const api = createAdminApiClient({ apiBaseUrl: "http://api" }, () => "token", async (request: Request) => {
      if (request.method === "POST") return json(409, { error: { code: "ADMIN_ACTION_NOT_ALLOWED" }, meta });
      gets += 1;
      return json(200, { data: { items: [referral], nextCursor: null }, meta });
    });
    render(<ReferralsScreen api={api} />);
    fireEvent.click(await screen.findByRole("button", { name: "Invalidate" }));
    fireEvent.change(screen.getByLabelText("Reason"), { target: { value: "Duplicate referral" } });
    fireEvent.click(screen.getByRole("button", { name: "Confirm invalidation" }));
    expect((await screen.findByRole("alert")).textContent).toContain("Refresh and look again");
    await waitFor(() => expect(gets).toBe(2));
    expect(screen.queryByRole("form", { name: "Invalidate referral ABC123" })).toBeNull();
    expect(screen.queryByDisplayValue("Duplicate referral")).toBeNull();
  });
});
