import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { createDashboardApiClient } from "../../api";
import { messages, MessagesContext } from "../../i18n";
import { AvailabilitySchedule } from "./AvailabilitySchedule";

afterEach(cleanup);

const meta = { correlationId: "c-1", serverTime: "2026-10-12T18:00:00Z" };
const json = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

function renderSchedule(
  fetch: (request: Request) => Promise<Response>,
  language: "en" | "ar" = "en",
) {
  const requests: Request[] = [];
  const api = createDashboardApiClient(
    { apiBaseUrl: "http://127.0.0.1:4010" },
    () => "mock-access-token",
    async (request: Request) => {
      requests.push(request);
      return fetch(request);
    },
  );
  render(
    <MessagesContext.Provider value={messages[language]}>
      <AvailabilitySchedule
        api={api}
        direction={language === "ar" ? "rtl" : "ltr"}
        initialDate="2026-10-12"
      />
    </MessagesContext.Provider>,
  );
  return requests;
}

const schedule = {
  businessDate: "2026-10-12",
  resources: [
    {
      resourceId: "c2d3e4f5-a6b7-4c8d-9e0f-1a2b3c4d5e6f",
      name: "Room 1",
      activity: "PLAYSTATION",
      consoleType: "PS5",
      slots: [
        { bookingId: null, startAt: "2026-10-12T09:00:00Z", endAt: "2026-10-12T10:00:00Z", state: "FREE" },
        { bookingId: "11111111-1111-4111-8111-111111111111", startAt: "2026-10-12T10:00:00Z", endAt: "2026-10-12T11:00:00Z", state: "PENDING" },
        { bookingId: "22222222-2222-4222-8222-222222222222", startAt: "2026-10-12T11:00:00Z", endAt: "2026-10-12T12:00:00Z", state: "AWAITING_CUSTOMER_CONFIRMATION" },
        { bookingId: "33333333-3333-4333-8333-333333333333", startAt: "2026-10-12T12:00:00Z", endAt: "2026-10-12T13:00:00Z", state: "CONFIRMED" },
        { bookingId: "44444444-4444-4444-8444-444444444444", startAt: "2026-10-12T13:00:00Z", endAt: "2026-10-12T14:00:00Z", state: "ACTIVE" },
        { bookingId: "55555555-5555-4555-8555-555555555555", startAt: "2026-10-12T14:00:00Z", endAt: "2026-10-12T15:00:00Z", state: "MANUAL" },
        { bookingId: null, startAt: "2026-10-12T15:00:00Z", endAt: "2026-10-12T16:00:00Z", state: "TOURNAMENT_HOLD" },
        { bookingId: null, startAt: "2026-10-12T16:00:00Z", endAt: "2026-10-12T17:00:00Z", state: "MAINTENANCE" },
        { bookingId: null, startAt: "2026-10-12T17:00:00Z", endAt: "2026-10-12T18:00:00Z", state: "CLOSURE" },
      ],
    },
  ],
};

describe("AvailabilitySchedule", () => {
  it("renders only contract schedule states and requests the selected business date", async () => {
    const requests = renderSchedule(async () => json(200, { data: schedule, meta }));

    expect(await screen.findByRole("heading", { name: "Availability schedule" })).toBeTruthy();
    expect(screen.getByText("Room 1")).toBeTruthy();
    for (const label of [
      "Free",
      "Pending",
      "Awaiting customer confirmation",
      "Confirmed",
      "Active",
      "Manual",
      "Tournament hold",
      "Maintenance",
      "Closure",
    ]) expect(screen.getAllByText(label).length).toBeGreaterThan(0);

    expect(requests[0]?.url).toBe("http://127.0.0.1:4010/api/v1/venue/availability?date=2026-10-12");
    expect(requests[0]?.headers.get("Authorization")).toBe("Bearer mock-access-token");
  });

  it("keeps a cross-midnight slot on the server business date and formats Cairo instants", async () => {
    renderSchedule(async () => json(200, {
      data: {
        businessDate: "2026-10-12",
        resources: [{
          ...schedule.resources[0],
          slots: [{
            bookingId: "66666666-6666-4666-8666-666666666666",
            startAt: "2026-10-12T20:30:00Z",
            endAt: "2026-10-12T22:30:00Z",
            state: "CONFIRMED",
          }],
        }],
      },
      meta,
    }));

    expect(await screen.findByText("11:30 PM – 1:30 AM")).toBeTruthy();
    expect(screen.getByText("October 12, 2026")).toBeTruthy();
  });

  it("navigates by business date without using the device timezone", async () => {
    const requests = renderSchedule(async (request) => {
      const date = new URL(request.url).searchParams.get("date");
      return json(200, { data: { businessDate: date, resources: [] }, meta });
    });

    await screen.findByText("No resources are scheduled for this business date.");
    fireEvent.click(screen.getByRole("button", { name: "Next business date" }));
    await waitFor(() => expect(requests).toHaveLength(2));
    expect(requests[1]?.url).toContain("date=2026-10-13");

    fireEvent.change(screen.getByLabelText("Business date"), { target: { value: "2026-10-15" } });
    await waitFor(() => expect(requests).toHaveLength(3));
    expect(requests[2]?.url).toContain("date=2026-10-15");
  });

  it("mirrors the time grid and localizes contract states in Arabic", async () => {
    renderSchedule(async () => json(200, { data: schedule, meta }), "ar");

    const grid = await screen.findByTestId("availability-grid");
    expect(grid.getAttribute("dir")).toBe("rtl");
    expect(grid.getAttribute("style")).not.toMatch(/(?:^|-)left|(?:^|-)right/u);
    expect(screen.getAllByText("بانتظار تأكيد العميل").length).toBeGreaterThan(0);
    expect(screen.getByLabelText("تاريخ يوم التشغيل")).toBeTruthy();
  });

  it("maps API failures through the message catalogue and retries", async () => {
    let calls = 0;
    renderSchedule(async () => ++calls === 1
      ? json(403, { error: { code: "FORBIDDEN" }, meta })
      : json(200, { data: { businessDate: "2026-10-12", resources: [] }, meta }));

    expect((await screen.findByRole("alert")).textContent).toContain("This action is not available for this account.");
    fireEvent.click(screen.getByRole("button", { name: "Retry" }));
    expect(await screen.findByText("No resources are scheduled for this business date.")).toBeTruthy();
    expect(calls).toBe(2);
  });
});
