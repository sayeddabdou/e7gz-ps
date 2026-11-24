import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { messages, MessagesContext } from "../../i18n";
import { OperationsScreen } from "./OperationsScreen";

const resource = { id: "11111111-1111-4111-8111-111111111111", name: "Room Alpha", activity: "PLAYSTATION", consoleType: "PS5", baseState: "ACTIVE", operationalRestriction: "NONE" };
const maintenance = { id: "22222222-2222-4222-8222-222222222222", resourceId: resource.id, startAt: "2026-12-08T08:00:00Z", endAt: "2026-12-08T14:00:00Z", reason: "Controller replacement", ownerPasswordUsed: false, createdFromEmergencyId: null };
const closure = { id: "33333333-3333-4333-8333-333333333333", kind: "PARTIAL", startAt: "2026-12-10T10:00:00Z", endAt: "2026-12-11T00:00:00Z", reason: "Private event" };
const emergency = { id: "44444444-4444-4444-8444-444444444444", scope: "VENUE", status: "ACTIVE", resourceId: null, reason: "Power cut", declaredAt: "2026-12-09T19:12:00Z", expectedEndAt: "2026-12-09T21:00:00Z", endedAt: null, convertedTo: null };
const review = { id: "55555555-5555-4555-8555-555555555555", resourceId: resource.id, enteredAt: "2026-12-09T21:00:00Z", resolvedAt: null, resolution: null };
const data = (value: unknown) => ({ data: { data: value, meta: { correlationId: "c", serverTime: "2026-12-08T07:00:00Z" } } });
const failure = (code: string, details?: unknown) => ({ error: { error: { code, ...(details ? { details } : {}) } }, response: { status: code === "OWNER_PASSWORD_REQUIRED" ? 423 : 422 } });

function apiFor(overrides: { GET?: ReturnType<typeof vi.fn>; POST?: ReturnType<typeof vi.fn>; PATCH?: ReturnType<typeof vi.fn>; DELETE?: ReturnType<typeof vi.fn> } = {}) {
  const GET = overrides.GET ?? vi.fn(async (path: string) => path.endsWith("/resources") ? data([resource])
    : path.endsWith("/maintenance") ? data([maintenance])
      : path.endsWith("/closures") ? data([closure])
        : path.endsWith("/emergencies") ? data([emergency])
          : path.endsWith("/owner-reviews") ? data([review])
            : data({ timezone: "Africa/Cairo" }));
  return { GET, POST: overrides.POST ?? vi.fn(async () => data({})), PATCH: overrides.PATCH ?? vi.fn(async () => data({})), DELETE: overrides.DELETE ?? vi.fn(async () => ({ response: { status: 204 } })) };
}

function show(api: ReturnType<typeof apiFor>, language: "en" | "ar" = "en") {
  return render(<MessagesContext.Provider value={messages[language]}><OperationsScreen api={api as never} onOpenHoursChange={vi.fn()} /></MessagesContext.Provider>);
}

afterEach(cleanup);

describe("OperationsScreen", () => {
  it("shows only retry when the initial operations load fails", async () => {
    const GET = vi.fn(async () => failure("RATE_LIMITED"));
    show(apiFor({ GET }));

    expect(await screen.findByText("Too many attempts. Try again later.")).toBeTruthy();
    expect(screen.getByRole("button", { name: "Retry" })).toBeTruthy();
    expect(screen.queryByRole("button", { name: "Add maintenance" })).toBeNull();
    expect(screen.queryByRole("button", { name: "Add closure" })).toBeNull();
  });

  it.each([
    ["en", "Maintenance, closures & emergencies", "Owner review required"],
    ["ar", "الصيانة والإغلاقات والطوارئ", "مراجعة المالك مطلوبة"],
  ] as const)("renders all requirement-driven sections in %s", async (language, title, banner) => {
    show(apiFor(), language);
    expect(await screen.findByRole("heading", { name: title })).toBeTruthy();
    expect(screen.getByRole("heading", { name: banner })).toBeTruthy();
    expect(screen.getAllByText("Room Alpha").length).toBeGreaterThan(0);
  });

  it("asks for a fresh Owner PIN only after maintenance answers OWNER_PASSWORD_REQUIRED", async () => {
    const POST = vi.fn().mockResolvedValueOnce(failure("OWNER_PASSWORD_REQUIRED")).mockResolvedValueOnce(data(maintenance));
    show(apiFor({ POST }));
    await screen.findByRole("heading", { name: "Maintenance, closures & emergencies" });

    fireEvent.click(screen.getByRole("button", { name: "Add maintenance" }));
    fireEvent.change(screen.getByLabelText("Starts"), { target: { value: "2026-12-12T08:00" } });
    fireEvent.change(screen.getByLabelText("Ends"), { target: { value: "2026-12-12T10:00" } });
    fireEvent.change(screen.getByLabelText("Reason"), { target: { value: "Replace controller" } });
    fireEvent.click(screen.getByRole("button", { name: "Save" }));

    expect(await screen.findByRole("dialog", { name: "Confirm and save" })).toBeTruthy();
    expect(POST.mock.calls[0]![1].body.ownerPassword).toBeUndefined();
    fireEvent.change(screen.getByLabelText("Owner PIN"), { target: { value: "1234" } });
    fireEvent.click(screen.getByRole("button", { name: "Confirm action" }));
    await waitFor(() => expect(POST).toHaveBeenCalledTimes(2));
    expect(POST.mock.calls[1]![1].body.ownerPassword).toBe("1234");
  });

  it("confirms maintenance deletion and never shows an Owner PIN prompt", async () => {
    const DELETE = vi.fn(async () => ({ response: { status: 204 } }));
    show(apiFor({ DELETE }));
    await screen.findByText("Controller replacement");
    fireEvent.click(screen.getAllByRole("button", { name: "Delete" })[0]!);
    expect(DELETE).not.toHaveBeenCalled();
    fireEvent.click(within(screen.getByRole("dialog", { name: "Confirm deletion" })).getByRole("button", { name: "Delete" }));
    await waitFor(() => expect(DELETE).toHaveBeenCalledTimes(1));
    expect(screen.queryByLabelText("Owner PIN")).toBeNull();
  });

  it("edits a maintenance window in venue local time without shifting its API instants", async () => {
    const PATCH = vi.fn(async (path: string, options: { body: unknown }) => { void path; void options; return data(maintenance); });
    show(apiFor({ PATCH }));
    await screen.findByText("Controller replacement");

    fireEvent.click(screen.getAllByRole("button", { name: "Edit" })[0]!);
    expect((screen.getByLabelText("Starts") as HTMLInputElement).value).toBe("2026-12-08T10:00");
    expect((screen.getByLabelText("Ends") as HTMLInputElement).value).toBe("2026-12-08T16:00");
    fireEvent.click(screen.getByRole("button", { name: "Save" }));

    await waitFor(() => expect(PATCH).toHaveBeenCalledTimes(1));
    expect(PATCH.mock.calls[0]![1].body).toEqual(expect.objectContaining({
      startAt: "2026-12-08T08:00:00.000Z",
      endAt: "2026-12-08T14:00:00.000Z",
    }));
  });

  it("shows closure impact and Wallet/Points refund wording before a protected save", async () => {
    const preview = { previewToken: "preview-1", ownerPasswordRequired: true, affected: [{ bookingId: "booking-1", resourceId: resource.id, startAt: "2026-12-12T18:00:00Z", endAt: "2026-12-12T20:00:00Z", protectedCancellation: true }] };
    const POST = vi.fn(async (path: string) => path.endsWith("/preview") ? data(preview) : data(closure));
    show(apiFor({ POST }));
    await screen.findByRole("heading", { name: "Venue closures" });

    fireEvent.click(screen.getByRole("button", { name: "Add closure" }));
    fireEvent.change(screen.getByLabelText("Starts"), { target: { value: "2026-12-12T18:00" } });
    fireEvent.change(screen.getByLabelText("Ends"), { target: { value: "2026-12-12T20:00" } });
    fireEvent.change(screen.getByLabelText("Reason"), { target: { value: "Private event" } });
    fireEvent.click(screen.getByRole("button", { name: "Preview impact" }));

    expect(await screen.findByText("booking-1")).toBeTruthy();
    expect(screen.getByText(/returns as Points/u)).toBeTruthy();
    expect(screen.getByText(/customer's Wallet/u)).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Confirm and save" }));
    expect(await screen.findByLabelText("Owner PIN")).toBeTruthy();
  });

  it("refreshes a closure preview when its affected set changes at commit", async () => {
    const first = { previewToken: "preview-1", ownerPasswordRequired: false, affected: [] };
    const refreshed = { previewToken: "preview-2", ownerPasswordRequired: false, affected: [{ bookingId: "booking-new", resourceId: resource.id, startAt: "2026-12-12T18:00:00Z", endAt: "2026-12-12T20:00:00Z", protectedCancellation: false }] };
    let previewCalls = 0;
    const POST = vi.fn(async (path: string) => {
      if (path.endsWith("/preview")) { previewCalls += 1; return data(previewCalls === 1 ? first : refreshed); }
      return failure("AFFECTED_SET_CHANGED_REFRESH_PREVIEW");
    });
    show(apiFor({ POST }));
    await screen.findByRole("heading", { name: "Venue closures" });

    fireEvent.click(screen.getByRole("button", { name: "Add closure" }));
    fireEvent.change(screen.getByLabelText("Starts"), { target: { value: "2026-12-12T20:00" } });
    fireEvent.change(screen.getByLabelText("Ends"), { target: { value: "2026-12-12T22:00" } });
    fireEvent.change(screen.getByLabelText("Reason"), { target: { value: "Private event" } });
    fireEvent.click(screen.getByRole("button", { name: "Preview impact" }));
    expect(await screen.findByText("No bookings are affected.")).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Confirm and save" }));

    expect(await screen.findByText("booking-new")).toBeTruthy();
    expect(screen.getByText(/Review the refreshed preview/u)).toBeTruthy();
    expect(previewCalls).toBe(2);
  });

  it("edits a closure without shifting its venue-local times", async () => {
    const POST = vi.fn(async (path: string, options: { body: { proposal: unknown } }) => { void path; void options; return data({ previewToken: "preview-edit", ownerPasswordRequired: false, affected: [] }); });
    show(apiFor({ POST }));
    await screen.findByText("Private event");

    fireEvent.click(screen.getAllByRole("button", { name: "Edit" })[1]!);
    expect((screen.getByLabelText("Starts") as HTMLInputElement).value).toBe("2026-12-10T12:00");
    expect((screen.getByLabelText("Ends") as HTMLInputElement).value).toBe("2026-12-11T02:00");
    fireEvent.click(screen.getByRole("button", { name: "Preview impact" }));

    await waitFor(() => expect(POST).toHaveBeenCalledTimes(1));
    expect(POST.mock.calls[0]![1].body.proposal).toEqual(expect.objectContaining({
      startAt: "2026-12-10T10:00:00.000Z",
      endAt: "2026-12-11T00:00:00.000Z",
    }));
  });

  it("declares and ends emergencies without an Owner PIN prompt", async () => {
    const POST = vi.fn(async () => data(emergency));
    show(apiFor({ POST }));
    await screen.findByRole("heading", { name: "Emergencies" });

    fireEvent.click(screen.getByRole("button", { name: "Declare venue emergency" }));
    fireEvent.change(screen.getByLabelText("Expected end"), { target: { value: "2026-12-12T20:00" } });
    fireEvent.change(screen.getByLabelText("Reason"), { target: { value: "Power cut" } });
    fireEvent.click(screen.getByRole("button", { name: "Save" }));
    await waitFor(() => expect(POST).toHaveBeenCalled());
    expect(screen.queryByLabelText("Owner PIN")).toBeNull();
  });

  it("extends through preview and commit without asking for an Owner PIN", async () => {
    const preview = { previewToken: "extend-preview", ownerPasswordRequired: true, affected: [] };
    const POST = vi.fn(async (path: string) => path.endsWith("/preview") ? data(preview) : data(emergency));
    show(apiFor({ POST }));
    await screen.findByRole("heading", { name: "Emergencies" });

    fireEvent.click(screen.getByRole("button", { name: "Extend" }));
    fireEvent.click(screen.getByRole("button", { name: "Preview impact" }));
    expect(await screen.findByText("No bookings are affected.")).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Confirm and save" }));

    await waitFor(() => expect(POST).toHaveBeenCalledTimes(2));
    expect(POST.mock.calls.map((call) => call[0])).toEqual([
      "/api/v1/venue/emergencies/{emergencyId}/extend/preview",
      "/api/v1/venue/emergencies/{emergencyId}/extend",
    ]);
    expect(screen.queryByLabelText("Owner PIN")).toBeNull();
  });

  it("refreshes an emergency preview when its affected set changes at commit", async () => {
    const first = { previewToken: "extend-1", ownerPasswordRequired: false, affected: [] };
    const refreshed = { previewToken: "extend-2", ownerPasswordRequired: false, affected: [{ bookingId: "emergency-booking-new", resourceId: resource.id, startAt: "2026-12-12T18:00:00Z", endAt: "2026-12-12T20:00:00Z", protectedCancellation: false }] };
    let previewCalls = 0;
    const POST = vi.fn(async (path: string) => {
      if (path.endsWith("/preview")) { previewCalls += 1; return data(previewCalls === 1 ? first : refreshed); }
      return failure("AFFECTED_SET_CHANGED_REFRESH_PREVIEW");
    });
    show(apiFor({ POST }));
    await screen.findByRole("heading", { name: "Emergencies" });

    fireEvent.click(screen.getByRole("button", { name: "Extend" }));
    fireEvent.click(screen.getByRole("button", { name: "Preview impact" }));
    expect(await screen.findByText("No bookings are affected.")).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Confirm and save" }));

    expect(await screen.findByText("emergency-booking-new")).toBeTruthy();
    expect(screen.getByText(/Review the refreshed preview/u)).toBeTruthy();
    expect(previewCalls).toBe(2);
  });

  it("asks for an Owner PIN when a conversion preview requires it", async () => {
    const preview = { previewToken: "convert-preview", ownerPasswordRequired: true, affected: [] };
    const POST = vi.fn(async (path: string) => path.endsWith("/preview") ? data(preview) : data(emergency));
    show(apiFor({ POST }));
    await screen.findByRole("heading", { name: "Emergencies" });

    fireEvent.click(screen.getByRole("button", { name: "Convert" }));
    fireEvent.click(screen.getByRole("button", { name: "Preview impact" }));
    expect(await screen.findByText("This impact requires a fresh Owner PIN to save.")).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Confirm and save" }));
    expect(await screen.findByLabelText("Owner PIN")).toBeTruthy();
  });

  it("reloads the lists when an operation reports OPERATIONAL_STATE_CHANGED", async () => {
    const POST = vi.fn(async () => failure("OPERATIONAL_STATE_CHANGED"));
    const api = apiFor({ POST });
    show(api);
    await screen.findByRole("heading", { name: "Emergencies" });
    expect(api.GET).toHaveBeenCalledTimes(6);

    fireEvent.click(screen.getByRole("button", { name: "End emergency" }));
    expect(await screen.findByText("This operational state changed. Refresh and look again.")).toBeTruthy();
    await waitFor(() => expect(api.GET).toHaveBeenCalledTimes(12));
  });

  it("localizes the closure preview workflow and refund wording in Arabic", async () => {
    const preview = { previewToken: "preview-ar", ownerPasswordRequired: false, affected: [] };
    const POST = vi.fn(async () => data(preview));
    show(apiFor({ POST }), "ar");
    await screen.findByRole("heading", { name: "إغلاقات المكان" });

    fireEvent.click(screen.getByRole("button", { name: "إضافة إغلاق" }));
    fireEvent.change(screen.getByLabelText("تبدأ"), { target: { value: "2026-12-12T20:00" } });
    fireEvent.change(screen.getByLabelText("تنتهي"), { target: { value: "2026-12-12T22:00" } });
    fireEvent.change(screen.getByLabelText("السبب"), { target: { value: "حدث خاص" } });
    fireEvent.click(screen.getByRole("button", { name: "معاينة التأثير" }));

    expect(await screen.findByText(/محفظة العميل/u)).toBeTruthy();
    expect(screen.getByText(/لا يتم رد أي مبلغ إلى بطاقة/u)).toBeTruthy();
  });

  it("reuses the declaration key on a network retry and renews it after the intent changes", async () => {
    const POST = vi.fn().mockRejectedValueOnce(new Error("offline")).mockResolvedValue(data(emergency));
    show(apiFor({ POST }));
    await screen.findByRole("heading", { name: "Emergencies" });

    fireEvent.click(screen.getByRole("button", { name: "Declare venue emergency" }));
    fireEvent.change(screen.getByLabelText("Expected end"), { target: { value: "2026-12-12T20:00" } });
    fireEvent.change(screen.getByLabelText("Reason"), { target: { value: "Power cut" } });
    fireEvent.click(screen.getByRole("button", { name: "Save" }));
    await waitFor(() => expect(POST).toHaveBeenCalledTimes(1));
    const firstKey = POST.mock.calls[0]![1].params.header["Idempotency-Key"];

    fireEvent.click(screen.getByRole("button", { name: "Save" }));
    await waitFor(() => expect(POST).toHaveBeenCalledTimes(2));
    expect(POST.mock.calls[1]![1].params.header["Idempotency-Key"]).toBe(firstKey);

    fireEvent.click(screen.getByRole("button", { name: "Declare venue emergency" }));
    fireEvent.change(screen.getByLabelText("Expected end"), { target: { value: "2026-12-13T20:00" } });
    fireEvent.change(screen.getByLabelText("Reason"), { target: { value: "Water leak" } });
    fireEvent.click(screen.getByRole("button", { name: "Save" }));
    await waitFor(() => expect(POST).toHaveBeenCalledTimes(3));
    expect(POST.mock.calls[2]![1].params.header["Idempotency-Key"]).not.toBe(firstKey);
  });

  it("lists blocking reservations and does not leave a blocked resolution executable", async () => {
    const POST = vi.fn(async () => failure("OWNER_REVIEW_RESOLUTION_BLOCKED", { blockingReservations: [{ bookingId: "booking-blocked", period: "[2026-12-12T18:00:00Z,2026-12-12T20:00:00Z)", kind: "MAINTENANCE" }] }));
    show(apiFor({ POST }));
    await screen.findByRole("heading", { name: "Owner review required" });

    fireEvent.click(screen.getByRole("button", { name: "Resolve" }));
    fireEvent.click(within(screen.getByRole("dialog", { name: "Owner review required" })).getByRole("button", { name: "Resolve" }));
    fireEvent.change(await screen.findByLabelText("Owner PIN"), { target: { value: "1234" } });
    fireEvent.click(screen.getByRole("button", { name: "Confirm action" }));

    expect(await screen.findByText("booking-blocked")).toBeTruthy();
    expect(screen.getByText(/Resolve these reservations/u)).toBeTruthy();
    expect(screen.getByText("booking-blocked").closest("li")?.textContent).toContain("Maintenance");
    expect(screen.queryByText(/\[2026/u)).toBeNull();
    expect(screen.queryByLabelText("Owner PIN")).toBeNull();
    expect((within(screen.getByRole("dialog", { name: "Owner review required" })).getByRole("button", { name: "Resolve" }) as HTMLButtonElement).disabled).toBe(true);
  });
});
