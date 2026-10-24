import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { createDashboardApiClient } from "../../api";
import { messages, MessagesContext } from "../../i18n";
import { OverviewScreen } from "./OverviewScreen";

afterEach(() => {
  cleanup();
  vi.useRealTimers();
});

const meta = { correlationId: "c-1", serverTime: "2026-10-13T00:30:00Z" };
const overview = {
  todayRevenue: { amount: "1234.50", currency: "EGP" },
  monthRevenue: { amount: "9876.00", currency: "EGP" },
  todayBookings: 37,
  pendingRequests: 4,
};
const schedule = { businessDate: "2026-10-12", closed: false, resources: [] };
const json = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

function renderOverview({
  language = "en",
  overviewResponses,
}: {
  language?: "en" | "ar";
  overviewResponses?: Response[];
} = {}) {
  const requests: Request[] = [];
  let overviewCall = 0;
  const api = createDashboardApiClient(
    { apiBaseUrl: "http://127.0.0.1:4010" },
    () => "mock-access-token",
    async (request: Request) => {
      requests.push(request);
      if (request.url.endsWith("/api/v1/venue/overview")) {
        return overviewResponses?.[overviewCall++] ?? json(200, { data: overview, meta });
      }
      if (request.url.endsWith("/api/v1/venue/availability")) {
        return json(200, { data: schedule, meta });
      }
      throw new Error(`Unexpected request: ${request.url}`);
    },
  );
  const onOpenPendingRequests = vi.fn();
  const onOpenManualBooking = vi.fn();
  const element = (bookingChangeVersion: number) => (
    <MessagesContext.Provider value={messages[language]}>
      <OverviewScreen
        api={api}
        bookingChangeVersion={bookingChangeVersion}
        direction={language === "ar" ? "rtl" : "ltr"}
        onOpenManualBooking={onOpenManualBooking}
        onOpenPendingRequests={onOpenPendingRequests}
      />
    </MessagesContext.Provider>
  );
  const view = render(element(0));
  return {
    onOpenManualBooking,
    onOpenPendingRequests,
    requests,
    updateBookingChangeVersion: (version: number) => view.rerender(element(version)),
  };
}

describe("Dashboard overview", () => {
  it("shows the four API values unchanged and uses the server business date for the first schedule load", async () => {
    vi.useFakeTimers({ toFake: ["Date"] }).setSystemTime(new Date("2026-10-13T12:00:00Z"));
    const { requests } = renderOverview();

    expect(await screen.findByText("1234.50 EGP")).toBeTruthy();
    expect(screen.getByText("9876.00 EGP")).toBeTruthy();
    expect(screen.getByText("37")).toBeTruthy();
    expect(screen.getByText("4")).toBeTruthy();
    expect(await screen.findByText("October 12, 2026")).toBeTruthy();

    const availability = requests.find((request) => request.url.includes("/api/v1/venue/availability"));
    expect(availability?.url).toBe("http://127.0.0.1:4010/api/v1/venue/availability");
    expect(new URL(availability!.url).search).toBe("");
  });

  it("opens existing request and manual-booking flows and omits the Sprint 6 tournament action", async () => {
    const { onOpenManualBooking, onOpenPendingRequests } = renderOverview();

    await screen.findByText("1234.50 EGP");
    fireEvent.click(screen.getByRole("button", { name: "View pending requests" }));
    fireEvent.click(screen.getByRole("button", { name: "Create manual booking" }));

    expect(onOpenPendingRequests).toHaveBeenCalledTimes(1);
    expect(onOpenManualBooking).toHaveBeenCalledTimes(1);
    expect(screen.queryByRole("button", { name: /tournament/iu })).toBeNull();
  });

  it("maps an overview failure and retries without hiding the independently loaded schedule", async () => {
    renderOverview({ overviewResponses: [
      json(403, { error: { code: "FORBIDDEN" }, meta }),
      json(200, { data: overview, meta }),
    ] });

    expect((await screen.findByRole("alert")).textContent).toContain("This action is not available for this account.");
    expect(await screen.findByText("October 12, 2026")).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Retry overview" }));
    expect(await screen.findByText("1234.50 EGP")).toBeTruthy();
  });

  it("refreshes the numbers and today's schedule when a live booking signal arrives", async () => {
    const changed = {
      ...overview,
      todayRevenue: { amount: "1400.00", currency: "EGP" },
      todayBookings: 38,
      pendingRequests: 5,
    };
    const { requests, updateBookingChangeVersion } = renderOverview({ overviewResponses: [
      json(200, { data: overview, meta }),
      json(200, { data: changed, meta }),
    ] });

    expect(await screen.findByText("1234.50 EGP")).toBeTruthy();
    updateBookingChangeVersion(1);

    expect(await screen.findByText("1400.00 EGP")).toBeTruthy();
    expect(screen.getByText("38")).toBeTruthy();
    expect(screen.getByText("5")).toBeTruthy();
    await waitFor(() => {
      expect(requests.filter((request) => request.url.endsWith("/api/v1/venue/overview"))).toHaveLength(2);
      expect(requests.filter((request) => request.url.endsWith("/api/v1/venue/availability"))).toHaveLength(2);
    });
  });

  it("renders the overview in Arabic RTL with logical styles", async () => {
    renderOverview({ language: "ar" });

    const page = await screen.findByTestId("overview-page");
    expect(page.getAttribute("dir")).toBe("rtl");
    expect(page.getAttribute("style")).not.toMatch(/(?:^|-)left|(?:^|-)right/u);
    expect(screen.getByRole("heading", { name: "نظرة عامة" })).toBeTruthy();
    expect(screen.getByRole("button", { name: "عرض الطلبات المعلقة" })).toBeTruthy();
    await waitFor(() => expect(screen.getByText("١٢ أكتوبر ٢٠٢٦")).toBeTruthy());
  });
});
