import type { ReactNode } from "react";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { createDashboardApiClient } from "../../api";
import { messages, MessagesContext } from "../../i18n";
import { OpeningHoursEditor } from "./OpeningHoursEditor";
import { OwnerPasswordDialog, toPinDigits } from "./OwnerPasswordDialog";
import { PhotoManager } from "./PhotoManager";
import { PricingEditor } from "./PricingEditor";
import { ResourceList } from "./ResourceList";
import { VenueProfile } from "./VenueProfile";

afterEach(cleanup);

const meta = { correlationId: "c-1", serverTime: "2026-10-03T12:00:00Z" };
const response = (data: unknown, status = 200) => new Response(status === 204 ? undefined : JSON.stringify(data), { status, headers: { "Content-Type": "application/json" } });
const ok = (data: unknown, status = 200) => response({ data, meta }, status);
const failure = (code: string, status: number) => response({ error: { code }, meta }, status);

function renderWithApi(ui: (api: ReturnType<typeof createDashboardApiClient>) => ReactNode, fetch: (request: Request) => Promise<Response>, language: "en" | "ar" = "en") {
  const requests: Request[] = [];
  const api = createDashboardApiClient({ apiBaseUrl: "http://127.0.0.1:4010" }, () => "token", async (request: Request) => {
    requests.push(request);
    return fetch(request);
  });
  render(<MessagesContext.Provider value={messages[language]}>{ui(api)}</MessagesContext.Provider>);
  return requests;
}

describe("OwnerPasswordDialog", () => {
  it("never remembers the password between protected actions", async () => {
    const confirm = vi.fn(async () => true);
    const { rerender } = render(<MessagesContext.Provider value={messages.en}><OwnerPasswordDialog actionLabel="Save" open onCancel={() => {}} onConfirm={confirm} /></MessagesContext.Provider>);
    const input = screen.getByLabelText("Owner PIN") as HTMLInputElement;
    fireEvent.change(input, { target: { value: "1357" } });
    fireEvent.submit(screen.getByRole("dialog"));
    await waitFor(() => expect(confirm).toHaveBeenCalledWith("1357"));

    rerender(<MessagesContext.Provider value={messages.en}><OwnerPasswordDialog actionLabel="Save" open={false} onCancel={() => {}} onConfirm={confirm} /></MessagesContext.Provider>);
    rerender(<MessagesContext.Provider value={messages.en}><OwnerPasswordDialog actionLabel="Save" open onCancel={() => {}} onConfirm={confirm} /></MessagesContext.Provider>);
    expect((screen.getByLabelText("Owner PIN") as HTMLInputElement).value).toBe("");
  });

  it("takes only 4 digits, and Arabic digits count as 0-9", () => {
    expect(toPinDigits("12ab34")).toBe("1234");
    expect(toPinDigits("\u0661\u0662\u0663\u0664")).toBe("1234");
    expect(toPinDigits("123456")).toBe("1234");
    render(<MessagesContext.Provider value={messages.en}><OwnerPasswordDialog actionLabel="Save" open onCancel={() => {}} onConfirm={async () => true} /></MessagesContext.Provider>);
    const input = screen.getByLabelText("Owner PIN") as HTMLInputElement;
    fireEvent.change(input, { target: { value: "9a8b7c6d5" } });
    expect(input.value).toBe("9876");
    expect(input.inputMode).toBe("numeric");
  });
});

describe("ResourceList protected actions", () => {
  it("creates a server-named room with activity, console and a fresh Owner Password", async () => {
    const requests = renderWithApi((api) => <ResourceList api={api} />, async (request) => {
      if (request.method === "GET") return ok([]);
      return ok({ id: "r-1", activity: "PLAYSTATION", name: "Room 1", consoleType: "PS5", baseState: "ACTIVE", operationalRestriction: "NONE" }, 201);
    });

    expect(await screen.findByText("No resources yet.")).toBeTruthy();
    expect(screen.queryByLabelText(/room name/iu)).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "Add resource" }));
    fireEvent.change(screen.getByLabelText("Owner PIN"), { target: { value: "1111" } });
    fireEvent.click(screen.getByRole("button", { name: "Confirm action" }));

    await waitFor(() => expect(requests.some((request) => request.method === "POST")).toBe(true));
    const create = requests.find((request) => request.method === "POST");
    expect(await create?.clone().json()).toEqual({ activity: "PLAYSTATION", consoleType: "PS5", ownerPassword: "1111" });
  });
});

describe("PricingEditor", () => {
  it("keeps decimal prices as strings and protects the save with Owner Password", async () => {
    const pricing = {
      defaults: [{ activity: "PLAYSTATION", consoleType: "PS5", playMode: "SINGLE", hourlyPrice: { amount: "150.00", currency: "EGP" } }],
      overrides: [],
    };
    const resources = [{ id: "r-1", activity: "PLAYSTATION", name: "Room 1", consoleType: "PS5", baseState: "ACTIVE", operationalRestriction: "NONE" }];
    const requests = renderWithApi((api) => <PricingEditor api={api} />, async (request) => {
      if (request.method === "GET" && request.url.endsWith("/pricing")) return ok(pricing);
      if (request.method === "GET") return ok(resources);
      return ok(pricing);
    });

    const price = await screen.findByLabelText("PlayStation · PS5 · Single");
    fireEvent.change(price, { target: { value: "170.50" } });
    fireEvent.click(screen.getByRole("button", { name: "Save" }));
    fireEvent.change(screen.getByLabelText("Owner PIN"), { target: { value: "2222" } });
    fireEvent.click(screen.getByRole("button", { name: "Confirm action" }));

    await waitFor(() => expect(requests.some((request) => request.method === "PUT" && request.url.endsWith("/pricing"))).toBe(true));
    const save = requests.find((request) => request.method === "PUT" && request.url.endsWith("/pricing"));
    expect(await save?.clone().json()).toEqual({
      defaults: [{ activity: "PLAYSTATION", consoleType: "PS5", playMode: "SINGLE", hourlyPrice: { amount: "170.50", currency: "EGP" } }],
      ownerPassword: "2222",
    });
  });
});

describe("VenueProfile", () => {
  it("loads editable venue settings and saves only the venue-authorized fields", async () => {
    const requests = renderWithApi((api) => <VenueProfile api={api} onLanguagePreference={() => {}} />, async (request) => request.method === "GET"
      ? ok({ venueId: "v-1", name: "Game Zone", areaId: "a-1", areaName: "Nasr City", address: "12 Test St", location: { lat: 30, lng: 31 }, phone: "+201001234567", timezone: "Africa/Cairo", marketplaceStatus: "ACTIVE", activities: ["PLAYSTATION"], languagePreference: "en" })
      : ok({ venueId: "v-1", name: "New name", areaId: "a-1", areaName: "Nasr City", address: "12 Test St", location: { lat: 30, lng: 31 }, phone: "+201001234567", timezone: "Africa/Cairo", marketplaceStatus: "ACTIVE", activities: ["PLAYSTATION"], languagePreference: "ar" }));

    fireEvent.change(await screen.findByLabelText("Venue name"), { target: { value: "New name" } });
    fireEvent.change(screen.getByLabelText("Dashboard language"), { target: { value: "ar" } });
    fireEvent.click(screen.getByRole("button", { name: "Save" }));
    await screen.findByText("Changes saved.");

    expect(requests[1]?.method).toBe("PATCH");
    expect(await requests[1]?.json()).toEqual({ name: "New name", address: "12 Test St", phone: "+201001234567", languagePreference: "ar" });
    expect(requests[1]?.headers.get("Authorization")).toBe("Bearer token");
  });

  it("renders its complete form in Arabic", async () => {
    renderWithApi((api) => <VenueProfile api={api} onLanguagePreference={() => {}} />, async () => ok({ venueId: "v-1", name: "مكان", areaId: "a-1", areaName: "مدينة نصر", address: "عنوان", location: { lat: 30, lng: 31 }, phone: "+201001234567", timezone: "Africa/Cairo", marketplaceStatus: "ACTIVE", activities: ["PLAYSTATION"], languagePreference: "ar" }), "ar");
    expect(await screen.findByRole("heading", { name: "ملف المكان" })).toBeTruthy();
    expect(screen.getByLabelText("اسم المكان")).toBeTruthy();
  });
});

describe("OpeningHoursEditor", () => {
  it("localizes weekday schedule controls in Arabic", async () => {
    renderWithApi((api) => <OpeningHoursEditor api={api} />, async () => ok({
      defaultOpen: "10:00:00",
      defaultClose: "23:00:00",
      defaultFullDay: false,
      timezone: "Africa/Cairo",
      version: 1,
      days: Array.from({ length: 7 }, (_, weekday) => ({ weekday, mode: "DEFAULT", customOpen: null, customClose: null })),
    }), "ar");

    expect(await screen.findByLabelText("الاثنين نمط الجدول")).toBeTruthy();
    expect(screen.queryByLabelText(/ mode$/u)).toBeNull();
  });

  it("uses preview then commit, and forces a fresh password after a changed affected set", async () => {
    let previewCalls = 0;
    let commitCalls = 0;
    const requests = renderWithApi((api) => <OpeningHoursEditor api={api} />, async (request) => {
      if (request.url.endsWith("/opening-hours") && request.method === "GET") return ok({ defaultOpen: "10:00:00", defaultClose: "23:00:00", defaultFullDay: false, timezone: "Africa/Cairo", version: 1, days: Array.from({ length: 7 }, (_, weekday) => ({ weekday, mode: "DEFAULT", customOpen: null, customClose: null })) });
      if (request.url.endsWith("/preview")) { previewCalls += 1; return ok({ previewToken: `p-${previewCalls}`, ownerPasswordRequired: true, affected: [{ bookingId: `b-${previewCalls}`, resourceId: "r-1", startAt: "2026-10-04T18:00:00Z", endAt: "2026-10-04T19:00:00Z", protectedCancellation: true }] }); }
      commitCalls += 1;
      return commitCalls === 1 ? failure("AFFECTED_SET_CHANGED_REFRESH_PREVIEW", 409) : ok({ defaultOpen: "10:00:00", defaultClose: "23:00:00", defaultFullDay: false, timezone: "Africa/Cairo", version: 2, days: [] });
    });

    fireEvent.click(await screen.findByRole("button", { name: "Preview changes" }));
    expect(await screen.findByText(/b-1/u)).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Save opening hours" }));
    fireEvent.change(screen.getByLabelText("Owner PIN"), { target: { value: "1111" } });
    fireEvent.click(screen.getByRole("button", { name: "Confirm action" }));

    expect(await screen.findByText(/Bookings changed/u)).toBeTruthy();
    expect(screen.getByText(/b-2/u)).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Save opening hours" }));
    expect((screen.getByLabelText("Owner PIN") as HTMLInputElement).value).toBe("");
    fireEvent.change(screen.getByLabelText("Owner PIN"), { target: { value: "2222" } });
    fireEvent.click(screen.getByRole("button", { name: "Confirm action" }));
    await waitFor(() => expect(commitCalls).toBe(2));

    const commitBodies = await Promise.all(requests.filter((request) => request.url.endsWith("/commit")).map((request) => request.clone().json()));
    expect(commitBodies.map((body) => body.ownerPassword)).toEqual(["1111", "2222"]);
    expect(previewCalls).toBe(2);
  });
});

describe("PhotoManager", () => {
  it("adds, reorders and removes photos with no Owner PIN (PCR-006)", async () => {
    let listed = false;
    const calls: string[] = [];
    const requests = renderWithApi((api) => <PhotoManager api={api} uploader={async (_url, _file, progress) => { calls.push("PUT signed"); progress(100); }} />, async (request) => {
      calls.push(`${request.method} ${new URL(request.url).pathname}`);
      const path = new URL(request.url).pathname;
      if (request.method === "GET") return ok(listed ? [{ id: "i-1", contentType: "image/jpeg", smallUrl: "https://images.test/small", mediumUrl: "https://images.test/medium", urlsExpireAt: "2026-10-03T13:00:00Z" }, { id: "i-2", contentType: "image/jpeg", smallUrl: null, mediumUrl: null, urlsExpireAt: null }] : []);
      if (request.method === "POST" && path.endsWith("/images")) return ok({ image: { id: "i-1", contentType: "image/jpeg", smallUrl: null, mediumUrl: null, urlsExpireAt: null }, uploadUrl: "https://upload.test/signed", uploadExpiresAt: "2026-10-03T12:10:00Z" }, 201);
      if (path.endsWith("/confirm")) { listed = true; return ok({ id: "i-1", contentType: "image/jpeg", smallUrl: null, mediumUrl: null, urlsExpireAt: null }); }
      if (request.method === "PUT") return ok([{ id: "i-2", contentType: "image/jpeg", smallUrl: null, mediumUrl: null, urlsExpireAt: null }, { id: "i-1", contentType: "image/jpeg", smallUrl: "https://images.test/small", mediumUrl: "https://images.test/medium", urlsExpireAt: "2026-10-03T13:00:00Z" }]);
      return response(undefined, 204);
    });

    const file = new File(["photo"], "venue.jpg", { type: "image/jpeg" });
    fireEvent.change(screen.getByLabelText("Choose photo"), { target: { files: [file] } });
    fireEvent.click(screen.getByRole("button", { name: "Add photo" }));
    await screen.findByAltText("Venue photos 1");
    expect(screen.queryByRole("dialog")).toBeNull();
    expect(calls.slice(1, 4)).toEqual(["POST /api/v1/venue/images", "PUT signed", "POST /api/v1/venue/images/i-1/confirm"]);

    fireEvent.click(screen.getAllByRole("button", { name: "Move down" })[0]!);
    fireEvent.click(screen.getByRole("button", { name: "Save photo order" }));
    await waitFor(() => expect(requests.some((request) => request.method === "PUT" && request.url.endsWith("/images/order"))).toBe(true));
    const order = requests.find((request) => request.method === "PUT" && request.url.endsWith("/images/order"));
    expect(await order?.clone().json()).toEqual({ imageIds: ["i-2", "i-1"] });

    fireEvent.click(screen.getAllByRole("button", { name: "Remove" })[0]!);
    await waitFor(() => expect(requests.some((request) => request.url.endsWith("/remove"))).toBe(true));
    expect(screen.queryByRole("dialog")).toBeNull();
    expect(screen.getByText("This hides the photo. It is not permanently deleted.")).toBeTruthy();
  });

  it("shows an upload failure and retries only what is missing", async () => {
    let uploads = 0;
    const calls: string[] = [];
    renderWithApi((api) => <PhotoManager api={api} uploader={async () => { uploads += 1; if (uploads === 1) throw new Error("offline"); }} />, async (request) => {
      const path = new URL(request.url).pathname;
      calls.push(`${request.method} ${path}`);
      if (request.method === "GET") return ok([]);
      if (request.method === "POST" && path.endsWith("/images")) return ok({ image: { id: "i-9", contentType: "image/jpeg", smallUrl: null, mediumUrl: null, urlsExpireAt: null }, uploadUrl: "https://upload.test/signed", uploadExpiresAt: "2026-10-03T12:10:00Z" }, 201);
      return ok({ id: "i-9", contentType: "image/jpeg", smallUrl: null, mediumUrl: null, urlsExpireAt: null });
    });

    fireEvent.change(screen.getByLabelText("Choose photo"), { target: { files: [new File(["photo"], "venue.jpg", { type: "image/jpeg" })] } });
    fireEvent.click(screen.getByRole("button", { name: "Add photo" }));
    expect((await screen.findByRole("alert")).textContent).toBeTruthy();

    fireEvent.click(screen.getByRole("button", { name: "Retry upload" }));
    await waitFor(() => expect(calls.some((call) => call.endsWith("/confirm"))).toBe(true));
    expect(uploads).toBe(2);
  });
});
