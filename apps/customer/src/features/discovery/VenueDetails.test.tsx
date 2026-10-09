import { fireEvent, render, screen, waitFor } from "@testing-library/react-native";
import { Linking } from "react-native";
import type { components } from "@e7gz/api-client";
import { createCustomerApiClient } from "../../api";
import { VenueDetails } from "./VenueDetails";

const config = { apiBaseUrl: "http://127.0.0.1:4010" };
const meta = { correlationId: "c-1", serverTime: "2026-10-12T17:04:11Z" };
const details: components["schemas"]["CustomerVenueDetails"] = {
  id: "v-1",
  name: "Game Zone",
  area: { id: "a-1", name: "Nasr City", nameAr: null },
  address: "12 Test Street",
  location: { lat: 30.05, lng: 31.33 },
  distanceMeters: 1250,
  imageUrls: ["https://files.example.test/hero.jpg", "https://files.example.test/second.jpg"],
  activities: ["PLAYSTATION", "BILLIARDS"],
  selectedActivity: "PLAYSTATION",
  resources: [{ id: "r-1", name: "Room 1", consoleType: "PS5" }],
  prices: [{ consoleType: "PS5", playMode: "SINGLE", hourlyPrice: { amount: "170.50", currency: "EGP" } }],
  openingHours: [{ weekday: 0, closed: false, opensAtLocal: "12:00:00", closesAtLocal: "02:00:00" }],
  nearestAvailableAt: "2026-10-12T18:00:00Z",
  openNow: false,
  opensAt: "2026-10-13T09:00:00Z",
  bookingEnabled: false,
  averageRating: 4.6,
  reviewCount: 38,
};

const json = (data: unknown, status = 200) => new Response(
  JSON.stringify(status < 400 ? { data, meta } : data),
  { status, headers: { "Content-Type": "application/json" } },
);

function renderDetails(data = details) {
  const requests: Request[] = [];
  const api = createCustomerApiClient(config, async (request: Request) => {
    requests.push(request);
    return json(data);
  });
  const rendered = render(<VenueDetails api={api} venueId="v-1" activity="PLAYSTATION" coordinates={{ lat: 30, lng: 31 }} onBack={() => {}} />);
  return { requests, ...rendered };
}

describe("VenueDetails", () => {
  it("renders contract data, exact decimal strings, closed state, and disabled booking", async () => {
    const { requests } = renderDetails();

    expect(await screen.findByText("Game Zone", {}, { timeout: 10_000 })).toBeTruthy();
    expect(screen.getByText(/170\.50 EGP/u)).toBeTruthy();
    expect(screen.getByText("Closed now")).toBeTruthy();
    expect(screen.getByText(/Opens at 12:00 PM/u)).toBeTruthy();
    expect(screen.getByText("Room 1")).toBeTruthy();
    expect(screen.getByText("PS5")).toBeTruthy();
    expect(screen.getByText("Monday: 12:00 PM–2:00 AM")).toBeTruthy();
    expect(screen.getByRole("button", { name: "Booking unavailable" }).props.accessibilityState.disabled).toBe(true);
    expect(requests[0]?.url).toContain("activity=PLAYSTATION");
    expect(requests[0]?.url).toContain("lat=30");
    expect(requests[0]?.url).toContain("lng=31");
    expect(screen.getByText("The venue's phone number becomes available once your booking is created.")).toBeTruthy();
    expect(screen.queryByText("+201001234567")).toBeNull();
  });

  it("shows every signed gallery URL and reloads it with each screen instance", async () => {
    const first = renderDetails();
    expect(await screen.findAllByTestId("venue-photo")).toHaveLength(2);
    expect(screen.getAllByTestId("venue-photo")[0]?.props.source.uri).toBe("https://files.example.test/hero.jpg");
    expect(first.requests).toHaveLength(1);
    first.unmount();

    const refreshed = renderDetails({ ...details, imageUrls: ["https://files.example.test/refreshed.jpg"] });
    expect((await screen.findByTestId("venue-photo")).props.source.uri).toBe("https://files.example.test/refreshed.jpg");
    expect(refreshed.requests).toHaveLength(1);
  });

  it("shows the required placeholder when the venue has no photos", async () => {
    renderDetails({ ...details, imageUrls: [] });
    expect(await screen.findByText("Venue photos unavailable")).toBeTruthy();
  });

  it("reloads details from the server when the selected activity changes", async () => {
    const { requests } = renderDetails();
    expect(await screen.findByText("Game Zone")).toBeTruthy();
    fireEvent.press(screen.getByText("Billiards"));
    await waitFor(() => expect(requests.some((request) => request.url.includes("activity=BILLIARDS"))).toBe(true));
  });

  it("does not display resources when the server returns details for another activity", async () => {
    const api = createCustomerApiClient(config, async () => json(details));
    render(<VenueDetails api={api} venueId="v-1" activity="BILLIARDS" onBack={() => {}} />);

    expect(await screen.findByText("This venue does not offer the selected activity.")).toBeTruthy();
    expect(screen.queryByText("Room 1")).toBeNull();
    expect(screen.queryByText("PS5")).toBeNull();
  });

  it("opens the returned coordinates without exposing a phone number", async () => {
    jest.spyOn(Linking, "openURL").mockResolvedValue(undefined);
    renderDetails();
    fireEvent.press(await screen.findByText("View location"));
    expect(Linking.openURL).toHaveBeenCalledWith("https://www.openstreetmap.org/?mlat=30.05&mlon=31.33#map=17/30.05/31.33");
  });

  it("opens the E7-50 booking flow when booking is enabled", async () => {
    renderDetails({ ...details, bookingEnabled: true });
    fireEvent.press(await screen.findByRole("button", { name: "View availability" }));
    expect(screen.getByRole("header", { name: "Room 1" })).toBeTruthy();
    expect(screen.getByText("Game Zone")).toBeTruthy();
    expect(screen.getByRole("button", { name: "Today, Monday 12 October" })).toBeTruthy();
  });

  it("keeps View availability disabled when the server disables booking", async () => {
    const { requests } = renderDetails({ ...details, bookingEnabled: false });
    const button = await screen.findByRole("button", { name: "Booking unavailable" });

    expect(button.props.accessibilityState.disabled).toBe(true);
    fireEvent.press(button);
    expect(screen.queryByRole("header", { name: "Room 1" })).toBeNull();
    expect(requests).toHaveLength(1);
  });
});
