import { describe, expect, it, vi } from "vitest";
import { createAdminSessionApi } from "./session";

const tokenResponse = (accessToken: string) => new Response(
  JSON.stringify({ data: { accessToken }, meta: { correlationId: "c-1", serverTime: "2026-10-02T10:00:00Z" } }),
  { status: 200, headers: { "Content-Type": "application/json" } },
);

describe("admin session retry", () => {
  it("shares one refresh across concurrent 401 responses and retries each request once", async () => {
    let refreshCalls = 0;
    const retriedAuthorizations: Array<string | null> = [];
    const request = vi.fn(async (input: RequestInfo | URL, _init?: RequestInit) => {
      void _init;
      const url = input instanceof Request ? input.url : String(input);
      if (url.endsWith("/api/v1/admin/auth/refresh")) {
        refreshCalls += 1;
        await Promise.resolve();
        return tokenResponse("fresh-access");
      }
      const authorization = input instanceof Request ? input.headers.get("Authorization") : null;
      if (authorization === "Bearer fresh-access") {
        retriedAuthorizations.push(authorization);
        return new Response(undefined, { status: 200 });
      }
      return new Response(undefined, { status: 401 });
    });
    const session = createAdminSessionApi("http://api.test", request as typeof fetch);
    const onAccessToken = vi.fn();
    const onUnauthenticated = vi.fn();

    const responses = await Promise.all([
      session.withRefresh(new Request("http://api.test/api/v1/admin/areas", { headers: { Authorization: "Bearer expired" } }), onAccessToken, onUnauthenticated),
      session.withRefresh(new Request("http://api.test/api/v1/admin/venues", { headers: { Authorization: "Bearer expired" } }), onAccessToken, onUnauthenticated),
    ]);

    expect(responses.map(({ status }) => status)).toEqual([200, 200]);
    expect(refreshCalls).toBe(1);
    expect(retriedAuthorizations).toEqual(["Bearer fresh-access", "Bearer fresh-access"]);
    expect(onAccessToken).toHaveBeenCalledWith("fresh-access");
    expect(onUnauthenticated).not.toHaveBeenCalled();
    const refreshCall = request.mock.calls.find(([input]) => (input instanceof Request ? input.url : String(input)).endsWith("/api/v1/admin/auth/refresh"));
    expect(refreshCall?.[1]).toEqual(expect.objectContaining({ credentials: "include", headers: { "X-Requested-With": "e7gz" }, method: "POST" }));
  });

  it("returns to login without retrying when refresh is refused", async () => {
    const request = vi.fn(async (input: RequestInfo | URL, _init?: RequestInit) => {
      void _init;
      const url = input instanceof Request ? input.url : String(input);
      return new Response(undefined, { status: url.endsWith("/auth/refresh") ? 403 : 401 });
    });
    const session = createAdminSessionApi("http://api.test", request as typeof fetch);
    const onUnauthenticated = vi.fn();

    const response = await session.withRefresh(
      new Request("http://api.test/api/v1/admin/areas", { headers: { Authorization: "Bearer expired" } }),
      vi.fn(),
      onUnauthenticated,
    );

    expect(response.status).toBe(401);
    expect(request).toHaveBeenCalledTimes(2);
    expect(onUnauthenticated).toHaveBeenCalledOnce();
  });
});
