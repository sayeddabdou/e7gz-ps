import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { createAdminApiClient } from "../../api";
import { FeesScreen, GlobalRulesScreen } from "./ConfigurationScreens";

afterEach(cleanup);
const meta = { correlationId: "c-1", serverTime: "2026-11-17T10:00:00Z" };
const json = (status: number, body: unknown) => new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
const money = (amount: string, effectiveFrom = "2026-11-01T00:00:00Z") => ({ value: { amount, currency: "EGP" }, effectiveFrom });

describe("E7-75 configuration", () => {
  it("shows every fee value and effective date, requires a reason, and maps a field error", async () => {
    const requests: Request[] = [];
    const api = createAdminApiClient({ apiBaseUrl: "http://api" }, () => "token", async (request: Request) => {
      requests.push(request);
      if (request.method === "PUT") return json(400, { error: { code: "VALIDATION_FAILED", field: "bookingFee" }, meta });
      return json(200, { data: {
        bookingFeeMode: { value: "PER_BOOKING", effectiveFrom: "2026-11-01T00:00:00Z" },
        bookingFee: money("0.00"), bookingFeePerHour: money("0.00"), tournamentCreationFee: money("0.00"),
      }, meta });
    });
    render(<FeesScreen api={api} />);

    expect(await screen.findAllByDisplayValue("0.00")).toHaveLength(3);
    expect(screen.getAllByText(/Effective from/)).toHaveLength(4);
    expect(screen.getByRole("button", { name: "Save fees" }).hasAttribute("disabled")).toBe(true);
    fireEvent.change(screen.getByLabelText("Reason"), { target: { value: "Enable the seasonal fee" } });
    fireEvent.change(screen.getByLabelText(/Per-booking fee/), { target: { value: "1.00" } });
    fireEvent.click(screen.getByRole("button", { name: "Save fees" }));

    expect((await screen.findByRole("alert")).textContent).toContain("payment collection");
    const put = requests.find((request) => request.method === "PUT")!;
    expect(put.headers.get("Idempotency-Key")).toBeTruthy();
  });

  it("renders the no-show ladder read-only", async () => {
    const api = createAdminApiClient({ apiBaseUrl: "http://api" }, () => "token", async () => json(200, { data: { noShowLadder: [
      { penaltyStage: 0, consecutiveNoShows: 3, banDuration: "P7D", recurring: false },
      { penaltyStage: 2, consecutiveNoShows: 1, banDuration: "P2M", recurring: true },
    ] }, meta }));
    render(<GlobalRulesScreen api={api} />);
    expect((await screen.findByText("7 days")).closest("table")).toBeTruthy();
    expect(screen.getByText("2 months")).toBeTruthy();
    expect(screen.queryByRole("textbox")).toBeNull();
    expect(screen.getByText("Recurring")).toBeTruthy();
  });
});
