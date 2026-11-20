import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { createAdminApiClient } from "../../api";
import { VerifiedPlayersScreen } from "./VerifiedPlayersScreen";

afterEach(cleanup);
const meta = { correlationId: "c-1", serverTime: "2026-11-17T10:00:00Z" };
const json = (status: number, body: unknown) => new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

describe("VerifiedPlayersScreen", () => {
  it("requires a reason, sends idempotency, and refreshes after a 409", async () => {
    const requests: Request[] = [];
    let gets = 0;
    const player = { userId: "11111111-1111-4111-8111-111111111111", username: "omar", fullName: "Omar", completedBookings: 12, qualification: "QUALIFIED", status: "VERIFIED", statusCause: "TRUST_QUALIFICATION", statusSince: "2026-11-01T00:00:00Z" };
    const api = createAdminApiClient({ apiBaseUrl: "http://api" }, () => "token", async (request: Request) => {
      requests.push(request);
      if (request.method === "POST") return json(409, { error: { code: "ADMIN_ACTION_NOT_ALLOWED" }, meta });
      gets += 1;
      return json(200, { data: { items: [player], nextCursor: null }, meta });
    });
    render(<VerifiedPlayersScreen api={api} />);
    fireEvent.click(await screen.findByRole("button", { name: "Suspend" }));
    expect(screen.getByRole("button", { name: "Confirm Suspend" }).hasAttribute("disabled")).toBe(true);
    fireEvent.change(screen.getByLabelText("Reason"), { target: { value: "Investigating suspicious progress" } });
    fireEvent.click(screen.getByRole("button", { name: "Confirm Suspend" }));

    expect((await screen.findByRole("alert")).textContent).toContain("Refresh and look again");
    await waitFor(() => expect(gets).toBe(2));
    expect(screen.queryByRole("form", { name: "suspend omar" })).toBeNull();
    expect(screen.queryByDisplayValue("Investigating suspicious progress")).toBeNull();
    const post = requests.find((request) => request.method === "POST")!;
    expect(post.headers.get("Idempotency-Key")).toBeTruthy();
  });
});
