import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { createAdminApiClient } from "../../api";
import { PointsScreen } from "./PointsScreen";

afterEach(cleanup);
const meta = { correlationId: "c-1", serverTime: "2026-11-17T10:00:00Z" };
const json = (status: number, body: unknown) => new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
const setting = (value: number) => ({ value, effectiveFrom: "2026-11-01T00:00:00Z" });

describe("PointsScreen", () => {
  it("requires a reason and sends an idempotency key for an adjustment", async () => {
    const requests: Request[] = [];
    const api = createAdminApiClient({ apiBaseUrl: "http://api" }, () => "token", async (request: Request) => {
      requests.push(request);
      if (request.url.endsWith("/points/settings")) return json(200, { data: { attendedPoints: setting(200), referralPoints: setting(1000), pointsPerFeeUnit: setting(10000), walletRefundsEnabled: { value: false, effectiveFrom: "2026-11-01T00:00:00Z" } }, meta });
      if (request.method === "POST") return json(200, { data: { transactionId: "t-1", userId: "11111111-1111-4111-8111-111111111111", amount: -200, requestedAmount: -200, unrecoveredAmount: 0, balanceAfter: 300, cause: "ADMIN", occurredAt: "2026-11-17T10:00:00Z", adminId: "a-1", bookingId: null, referralId: null, reason: "Correction" }, meta });
      return json(200, { data: { items: [{ userId: "11111111-1111-4111-8111-111111111111", username: "omar", fullName: "Omar", balance: 500, bookingRewards: 500, referralRewards: 0 }], nextCursor: null }, meta });
    });
    render(<PointsScreen api={api} />);
    fireEvent.click(await screen.findByRole("button", { name: "Adjust" }));
    expect(screen.getByRole("button", { name: "Apply adjustment" }).hasAttribute("disabled")).toBe(true);
    expect(screen.getByLabelText(/Amount/).getAttribute("step")).toBe("1");
    fireEvent.change(screen.getByLabelText(/Amount/), { target: { value: "-200" } });
    fireEvent.change(screen.getAllByLabelText("Reason").at(-1)!, { target: { value: "Correction" } });
    fireEvent.click(screen.getByRole("button", { name: "Apply adjustment" }));
    await waitFor(() => expect(requests.some((request) => request.method === "POST")).toBe(true));
    expect(requests.find((request) => request.method === "POST")!.headers.get("Idempotency-Key")).toBeTruthy();
  });

  it("blocks a double adjustment and reuses its key after a network retry", async () => {
    const posts: Request[] = [];
    let rejectFirst!: (reason?: unknown) => void;
    const account = { userId: "11111111-1111-4111-8111-111111111111", username: "omar", fullName: "Omar", balance: 500, bookingRewards: 500, referralRewards: 0 };
    const api = createAdminApiClient({ apiBaseUrl: "http://api" }, () => "token", (request: Request) => {
      if (request.url.endsWith("/points/settings")) return Promise.resolve(json(200, { data: { attendedPoints: setting(200), referralPoints: setting(1000), pointsPerFeeUnit: setting(10000), walletRefundsEnabled: { value: false, effectiveFrom: "2026-11-01T00:00:00Z" } }, meta }));
      if (request.method === "POST") {
        posts.push(request);
        if (posts.length === 1) return new Promise<Response>((_resolve, reject) => { rejectFirst = reject; });
        return Promise.resolve(json(200, { data: { transactionId: "t-1", userId: account.userId, amount: 100, requestedAmount: 100, unrecoveredAmount: 0, balanceAfter: 600, cause: "ADMIN", occurredAt: "2026-11-17T10:00:00Z", adminId: "a-1", bookingId: null, referralId: null, reason: "Correction" }, meta }));
      }
      return Promise.resolve(json(200, { data: { items: [account], nextCursor: null }, meta }));
    });
    render(<PointsScreen api={api} />);
    fireEvent.click(await screen.findByRole("button", { name: "Adjust" }));
    fireEvent.change(screen.getByLabelText(/Amount/), { target: { value: "100" } });
    fireEvent.change(screen.getAllByLabelText("Reason").at(-1)!, { target: { value: "Correction" } });
    const submit = screen.getByRole("button", { name: "Apply adjustment" });
    fireEvent.click(submit);
    fireEvent.click(submit);
    await waitFor(() => expect(posts).toHaveLength(1));
    expect(submit.hasAttribute("disabled")).toBe(true);
    const firstKey = posts[0]!.headers.get("Idempotency-Key");
    rejectFirst(new TypeError("network failed"));
    await screen.findByRole("alert");
    fireEvent.click(screen.getByRole("button", { name: "Apply adjustment" }));
    await waitFor(() => expect(posts).toHaveLength(2));
    expect(posts[1]!.headers.get("Idempotency-Key")).toBe(firstKey);
  });

  it("closes a stale adjustment form after ADMIN_ACTION_NOT_ALLOWED", async () => {
    let accountGets = 0;
    const account = { userId: "11111111-1111-4111-8111-111111111111", username: "omar", fullName: "Omar", balance: 500, bookingRewards: 500, referralRewards: 0 };
    const api = createAdminApiClient({ apiBaseUrl: "http://api" }, () => "token", async (request: Request) => {
      if (request.url.endsWith("/points/settings")) return json(200, { data: { attendedPoints: setting(200), referralPoints: setting(1000), pointsPerFeeUnit: setting(10000), walletRefundsEnabled: { value: false, effectiveFrom: "2026-11-01T00:00:00Z" } }, meta });
      if (request.method === "POST") return json(409, { error: { code: "ADMIN_ACTION_NOT_ALLOWED" }, meta });
      accountGets += 1;
      return json(200, { data: { items: [account], nextCursor: null }, meta });
    });
    render(<PointsScreen api={api} />);
    fireEvent.click(await screen.findByRole("button", { name: "Adjust" }));
    fireEvent.change(screen.getByLabelText(/Amount/), { target: { value: "100" } });
    fireEvent.change(screen.getAllByLabelText("Reason").at(-1)!, { target: { value: "Correction" } });
    fireEvent.click(screen.getByRole("button", { name: "Apply adjustment" }));
    expect((await screen.findByRole("alert")).textContent).toContain("Refresh and look again");
    await waitFor(() => expect(accountGets).toBe(2));
    expect(screen.queryByRole("form", { name: "Adjust points for omar" })).toBeNull();
    expect(screen.queryByDisplayValue("Correction")).toBeNull();
  });
});
