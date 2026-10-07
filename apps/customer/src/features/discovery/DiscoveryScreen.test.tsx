import { act, fireEvent, render, screen, waitFor } from "@testing-library/react-native";
import { createCustomerApiClient } from "../../api";
import type { LocationAccess } from "./location";
import { DiscoveryScreen } from "./DiscoveryScreen";

const config = { apiBaseUrl: "http://127.0.0.1:4010" };
const meta = { correlationId: "c-1", serverTime: "2026-10-12T17:04:11Z" };
const area = { id: "a-1", name: "Nasr City", nameAr: null };
const venue = {
  id: "v-1",
  name: "Game Zone",
  area: { id: "a-1", name: "Nasr City", nameAr: null },
  distanceMeters: null,
  imageUrl: "https://files.example.test/card.jpg",
  nearestAvailableAt: "2026-10-12T18:00:00Z",
  startingPrice: { amount: "170.50", currency: "EGP" },
  averageRating: 4.6,
  reviewCount: 38,
};
const marker = {
  id: "v-1",
  name: "Game Zone",
  location: { lat: 30.05, lng: 31.33 },
  distanceMeters: 1250,
  imageUrl: "https://files.example.test/map-card.jpg",
  nearestAvailableAt: "2026-10-12T18:00:00Z",
  startingPrice: { amount: "170.50", currency: "EGP" },
  averageRating: 4.6,
  reviewCount: 38,
};
const details = {
  id: "v-1",
  name: "Game Zone",
  area,
  address: "12 Test Street",
  location: marker.location,
  distanceMeters: 1250,
  imageUrls: [],
  activities: ["PLAYSTATION" as const],
  selectedActivity: "PLAYSTATION" as const,
  resources: [],
  prices: [],
  openingHours: [],
  nearestAvailableAt: marker.nearestAvailableAt,
  openNow: true,
  opensAt: null,
  bookingEnabled: true,
  averageRating: 4.6,
  reviewCount: 38,
};

const json = (data: unknown, status = 200) => new Response(
  JSON.stringify(status < 400 ? { data, meta } : data),
  { status, headers: { "Content-Type": "application/json" } },
);

const deniedLocation: LocationAccess = {
  current: jest.fn().mockResolvedValue({ status: "denied" }),
  request: jest.fn().mockResolvedValue({ status: "denied" }),
};

const grantedLocation: LocationAccess = {
  current: jest.fn().mockResolvedValue({ status: "granted", coordinates: { lat: 30.05, lng: 31.33 } }),
  request: jest.fn().mockResolvedValue({ status: "granted", coordinates: { lat: 30.05, lng: 31.33 } }),
};

function renderDiscovery(
  location: LocationAccess,
  respond: (request: Request) => Promise<Response>,
) {
  const requests: Request[] = [];
  const api = createCustomerApiClient(config, async (request: Request) => {
    requests.push(request);
    return respond(request);
  });
  render(<DiscoveryScreen api={api} location={location} />);
  return requests;
}

describe("DiscoveryScreen", () => {
  it("continues with a hard area filter after location permission is denied", async () => {
    const requests = renderDiscovery(deniedLocation, async (request) => {
      if (request.url.endsWith("/customer/areas")) return json([area]);
      if (request.url.includes("/customer/venues?")) return json({ items: [venue], nextCursor: null });
      throw new Error(`Unexpected request: ${request.url}`);
    });

    fireEvent.press(await screen.findByText("Choose area manually"));
    fireEvent.press(await screen.findByText("Nasr City"));

    expect(await screen.findByText("Game Zone")).toBeTruthy();
    const listRequest = requests.find((request) => request.url.includes("/customer/venues?"));
    expect(listRequest?.url).toContain("areaId=a-1");
    expect(listRequest?.url).toContain("activity=PLAYSTATION");
    expect(listRequest?.url).not.toContain("lat=");
    expect(listRequest?.url).not.toContain("lng=");
  });

  it("recovers from an explicit permission denial without crashing", async () => {
    const location: LocationAccess = {
      current: jest.fn().mockResolvedValue({ status: "undetermined" }),
      request: jest.fn().mockResolvedValue({ status: "denied" }),
    };
    renderDiscovery(location, async (request) => request.url.endsWith("/customer/areas")
      ? json([area])
      : json({ items: [venue], nextCursor: null }));

    fireEvent.press(await screen.findByText("Allow location"));
    expect(await screen.findByText("Choose area manually")).toBeTruthy();
    fireEvent.press(screen.getByText("Choose area manually"));
    fireEvent.press(await screen.findByText("Nasr City"));
    expect(await screen.findByText("Game Zone")).toBeTruthy();
  });

  it("sends location to the server and never sorts or computes nearest availability locally", async () => {
    const requests = renderDiscovery(grantedLocation, async (request) => {
      if (request.url.endsWith("/customer/areas")) return json([area]);
      return json({ items: [venue], nextCursor: null });
    });

    expect(await screen.findByText("Game Zone")).toBeTruthy();
    const listRequest = requests.find((request) => request.url.includes("/customer/venues?"));
    expect(listRequest?.url).toContain("lat=30.05");
    expect(listRequest?.url).toContain("lng=31.33");
    expect(screen.getByText("170.50 EGP")).toBeTruthy();
    expect(screen.getByText(/9:00 PM/u)).toBeTruthy();
    expect(screen.getByTestId("venue-card-image").props.source.uri).toBe("https://files.example.test/card.jpg");
  });

  it("uses the server for activity changes, pagination, empty results, and retry", async () => {
    let fail = true;
    const requests = renderDiscovery(grantedLocation, async (request) => {
      if (request.url.endsWith("/customer/areas")) return json([area]);
      if (request.url.includes("activity=BILLIARDS") && fail) {
        fail = false;
        return json({ error: { code: "RATE_LIMITED" }, meta }, 429);
      }
      if (request.url.includes("cursor=next-1")) return json({ items: [venue], nextCursor: null });
      return json({ items: request.url.includes("activity=BILLIARDS") ? [] : [venue], nextCursor: "next-1" });
    });

    expect(await screen.findByText("Game Zone")).toBeTruthy();
    fireEvent.press(screen.getByText("Load more"));
    await waitFor(() => expect(requests.some((request) => request.url.includes("cursor=next-1"))).toBe(true));
    await waitFor(() => expect(screen.queryByText("Load more")).toBeNull());
    expect(screen.getAllByText("Game Zone")).toHaveLength(1);

    fireEvent.press(screen.getByText("Billiards"));
    expect(await screen.findByText("Too many requests. Try again later.")).toBeTruthy();
    fireEvent.press(screen.getByText("Retry"));
    expect(await screen.findByText("No venues found for these filters.")).toBeTruthy();
  });

  it("shows a photo placeholder and never renders a phone field", async () => {
    renderDiscovery(grantedLocation, async (request) => request.url.endsWith("/customer/areas")
      ? json([area])
      : json({ items: [{ ...venue, imageUrl: null }], nextCursor: null }));

    expect(await screen.findByText("Venue photo unavailable")).toBeTruthy();
    expect(screen.queryByText(/phone/iu)).toBeNull();
  });

  it("keeps the same filtered venue on the map and renders every server marker field", async () => {
    let mapFailed = true;
    const requests = renderDiscovery(deniedLocation, async (request) => {
      if (request.url.endsWith("/customer/areas")) return json([area]);
      if (request.url.includes("/customer/venues/map?")) {
        if (mapFailed) {
          mapFailed = false;
          return json({ error: { code: "RATE_LIMITED" }, meta }, 429);
        }
        return json([marker]);
      }
      if (request.url.includes("/customer/venues/v-1?")) return json(details);
      if (request.url.includes("/customer/venues?")) return json({ items: [venue], nextCursor: null });
      throw new Error(`Unexpected request: ${request.url}`);
    });

    fireEvent.press(await screen.findByText("Choose area manually"));
    fireEvent.press(await screen.findByText("Nasr City"));
    expect(await screen.findByText("Game Zone")).toBeTruthy();

    fireEvent.press(screen.getByRole("button", { name: "Map" }));
    expect(await screen.findByText("Too many requests. Try again later.")).toBeTruthy();
    fireEvent.press(screen.getByText("Retry"));

    expect(await screen.findByTestId("map-preview-v-1")).toBeTruthy();
    expect(screen.getByText("1.3 km")).toBeTruthy();
    expect(screen.getByText("170.50 EGP")).toBeTruthy();
    expect(screen.getByText(/Nearest availability: .*9:00 PM/u)).toBeTruthy();
    expect(screen.getByText("4.6 ★ · 38 reviews")).toBeTruthy();
    expect(screen.getByTestId("map-preview-image").props.source.uri).toBe("https://files.example.test/map-card.jpg");

    const mapRequests = requests.filter((request) => request.url.includes("/customer/venues/map?"));
    expect(mapRequests).toHaveLength(2);
    expect(mapRequests[1]?.url).toContain("areaId=a-1");
    expect(mapRequests[1]?.url).toContain("activity=PLAYSTATION");
    expect(mapRequests[1]?.url).toContain("bounds=31.2%2C29.95%2C31.45%2C30.15");
    expect(mapRequests[1]?.url).not.toContain("lat=");
    expect(mapRequests[1]?.url).not.toContain("lng=");

    await act(async () => {
      screen.getByTestId("discovery-map").props.onRegionChangeComplete({
        latitude: 30.1,
        longitude: 31.4,
        latitudeDelta: 0.1,
        longitudeDelta: 0.2,
      });
    });
    await waitFor(() => expect(requests.some((request) => request.url.includes("bounds=31.3%2C30.05%2C31.5%2C30.15"))).toBe(true));

    fireEvent.press(screen.getByRole("button", { name: "View venue" }));
    expect(await screen.findByText("Nasr City · 12 Test Street · 1.3 km")).toBeTruthy();
    expect(requests.some((request) => request.url.includes("/customer/venues/v-1?"))).toBe(true);
    expect(screen.queryByText(/phone/iu)).toBeNull();
  });

  it("shows a map placeholder when the marker photo is absent", async () => {
    renderDiscovery(grantedLocation, async (request) => {
      if (request.url.endsWith("/customer/areas")) return json([area]);
      if (request.url.includes("/customer/venues/map?")) return json([{ ...marker, imageUrl: null }]);
      return json({ items: [venue], nextCursor: null });
    });

    expect(await screen.findByText("Game Zone")).toBeTruthy();
    fireEvent.press(screen.getByRole("button", { name: "Map" }));
    expect(await screen.findByText("Venue photo unavailable")).toBeTruthy();
  });

  it("shows map loading and empty states", async () => {
    let resolveMap!: (response: Response) => void;
    const mapResponse = new Promise<Response>((resolve) => {
      resolveMap = resolve;
    });

    renderDiscovery(grantedLocation, async (request) => {
      if (request.url.endsWith("/customer/areas")) return json([area]);
      if (request.url.includes("/customer/venues/map?")) return mapResponse;
      return json({ items: [venue], nextCursor: null });
    });

    expect(await screen.findByText("Game Zone")).toBeTruthy();
    fireEvent.press(screen.getByRole("button", { name: "Map" }));
    expect(screen.getByText("Loading…")).toBeTruthy();

    await act(async () => {
      resolveMap(json([]));
    });
    expect(await screen.findByText("No venues are visible in this map area.")).toBeTruthy();
  });
});
