import { createCustomerApiClient, type RefreshAccessToken, type TokenStore } from "./index";

const meta = { correlationId: "c-1", serverTime: "2026-10-12T17:04:11Z" };
const response = (status: number) =>
  new Response(JSON.stringify(status === 200 ? { data: [], meta } : { error: { code: "TOKEN_EXPIRED" }, meta }), {
    status,
    headers: { "Content-Type": "application/json" },
  });

function memoryStore(): TokenStore & { tokens: { accessToken: string; refreshToken: string } | null } {
  return {
    tokens: { accessToken: "old-access", refreshToken: "refresh-token" },
    async read() {
      return this.tokens;
    },
    async write(tokens) {
      this.tokens = tokens;
    },
    async clear() {
      this.tokens = null;
    },
  };
}

describe("authenticated customer API client", () => {
  it("refreshes after a 401 and retries the request exactly once", async () => {
    const requests: Request[] = [];
    const fetch = jest.fn(async (request: Request) => {
      requests.push(request);
      return response(requests.length === 1 ? 401 : 200);
    });
    const store = memoryStore();
    const refresh: RefreshAccessToken = jest.fn(async () => ({
      accessToken: "new-access",
      refreshToken: "new-refresh",
    }));
    const api = createCustomerApiClient({ apiBaseUrl: "http://127.0.0.1:4010" }, { fetch, tokenStore: store, refresh });

    const result = await api.GET("/api/v1/customer/areas");

    expect(result.response.status).toBe(200);
    expect(fetch).toHaveBeenCalledTimes(2);
    expect(requests[0]!.headers.get("Authorization")).toBe("Bearer old-access");
    expect(requests[1]!.headers.get("Authorization")).toBe("Bearer new-access");
    expect(refresh).toHaveBeenCalledWith("refresh-token");
    expect(store.tokens).toEqual({ accessToken: "new-access", refreshToken: "new-refresh" });
  });

  it("never loops when the retried request is also unauthorized", async () => {
    const fetch = jest.fn(async () => response(401));
    const store = memoryStore();
    const refresh: RefreshAccessToken = jest.fn(async () => ({
      accessToken: "new-access",
      refreshToken: "new-refresh",
    }));
    const api = createCustomerApiClient({ apiBaseUrl: "http://127.0.0.1:4010" }, { fetch, tokenStore: store, refresh });

    const result = await api.GET("/api/v1/customer/areas");

    expect(result.response.status).toBe(401);
    expect(fetch).toHaveBeenCalledTimes(2);
    expect(refresh).toHaveBeenCalledTimes(1);
    expect(store.tokens).toBeNull();
  });

  it("does not retry when refresh fails", async () => {
    const fetch = jest.fn(async () => response(401));
    const store = memoryStore();
    const refresh: RefreshAccessToken = jest.fn(async () => null);
    const api = createCustomerApiClient({ apiBaseUrl: "http://127.0.0.1:4010" }, { fetch, tokenStore: store, refresh });

    const result = await api.GET("/api/v1/customer/areas");

    expect(result.response.status).toBe(401);
    expect(fetch).toHaveBeenCalledTimes(1);
    expect(store.tokens).toBeNull();
  });
});
