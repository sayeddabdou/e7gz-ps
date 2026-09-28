import { fireEvent, render, screen, waitFor } from "@testing-library/react-native";
import { createCustomerApiClient } from "../../api";
import { App } from "../../app/App";
import { readConfig } from "../../config";
import { AreaList } from "./AreaList";

const config = { apiBaseUrl: "http://127.0.0.1:4010" };
const meta = { correlationId: "c-1", serverTime: "2026-10-12T17:04:11Z" };
const json = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

function renderList(fetch: (request: Request) => Promise<Response>) {
  const requests: Request[] = [];
  const api = createCustomerApiClient(config, async (request: Request) => {
    requests.push(request);
    return fetch(request);
  });
  render(<AreaList api={api} />);
  return requests;
}

describe("AreaList", () => {
  it("shows loading, then the public areas, without an Authorization header", async () => {
    let answer: (response: Response) => void = () => {};
    const requests = renderList(() => new Promise((resolve) => (answer = resolve)));

    expect(screen.getByText("Loading…")).toBeTruthy();
    await waitFor(() => expect(requests).toHaveLength(1));
    answer(json(200, { data: [{ id: "a-1", name: "Nasr City", nameAr: null }], meta }));

    expect((await screen.findByTestId("area")).props.children).toBe("Nasr City");
    expect(requests[0]!.url).toBe("http://127.0.0.1:4010/api/v1/customer/areas");
    expect(requests[0]!.headers.has("Authorization")).toBe(false);
  });

  it("shows the error code of an error response, and retries", async () => {
    let calls = 0;
    renderList(async () => (++calls === 1 ? json(422, { error: { code: "AREA_INACTIVE" }, meta }) : json(200, { data: [], meta })));

    expect(await screen.findByText("Could not load the areas.")).toBeTruthy();
    expect(screen.getByText("Error code: AREA_INACTIVE")).toBeTruthy();

    fireEvent.press(screen.getByText("Retry"));
    expect(await screen.findByText("No areas yet.")).toBeTruthy();
  });

  it("shows a network failure as an error, not as an empty list", async () => {
    renderList(async () => {
      throw new TypeError("fetch failed");
    });

    expect(await screen.findByText("The server could not be reached.")).toBeTruthy();
    expect(screen.queryByText("No areas yet.")).toBeNull();
  });
});

describe("config", () => {
  it("requires EXPO_PUBLIC_API_BASE_URL instead of guessing a backend", () => {
    expect(readConfig(undefined)).toEqual({ ok: false, missing: ["EXPO_PUBLIC_API_BASE_URL"] });
    expect(readConfig(" http://10.0.2.2:4010 ")).toEqual({ ok: true, config: { apiBaseUrl: "http://10.0.2.2:4010" } });

    render(<App config={readConfig(undefined)} />);
    expect(screen.getByText("Missing configuration: EXPO_PUBLIC_API_BASE_URL")).toBeTruthy();
  });
});
