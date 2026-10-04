import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { createAdminApiClient } from "../../api";
import { AdminPhotoManager } from "./AdminPhotoManager";

afterEach(() => { cleanup(); vi.unstubAllGlobals(); });
const meta = { correlationId: "c-1", serverTime: "2026-10-03T10:00:00Z" };
const response = (status: number, body?: unknown) => new Response(body === undefined ? undefined : JSON.stringify(body), { status, headers: body === undefined ? undefined : { "Content-Type": "application/json" } });

describe("AdminPhotoManager", () => {
  it("shows upload failure and retries from a fresh signed upload before confirming", async () => {
    let starts = 0; let confirms = 0;
    const api = createAdminApiClient({ apiBaseUrl: "http://api" }, () => "token", async (request) => {
      if (request.method === "GET") return response(200, { data: [], meta });
      if (request.url.endsWith("/images")) { starts += 1; return response(201, { data: { image: { id: `i-${starts}`, contentType: "image/jpeg", mediumUrl: null, smallUrl: null, urlsExpireAt: null }, uploadUrl: `https://upload/${starts}`, uploadExpiresAt: meta.serverTime }, meta }); }
      confirms += 1; return response(200, { data: { id: "i-2", contentType: "image/jpeg", mediumUrl: "https://signed/photo.jpg", smallUrl: "https://signed/small.jpg", urlsExpireAt: meta.serverTime }, meta });
    });
    const upload = vi.fn().mockResolvedValueOnce(new Response(undefined, { status: 500 })).mockResolvedValueOnce(new Response(undefined, { status: 200 }));
    vi.stubGlobal("fetch", upload);
    render(<AdminPhotoManager api={api} venueId="v-1" />);
    await screen.findByText("No photos yet.");
    fireEvent.change(screen.getByLabelText("Add photo"), { target: { files: [new File(["bytes"], "venue.jpg", { type: "image/jpeg" })] } });
    expect((await screen.findByRole("alert")).textContent).toContain("Something went wrong");
    fireEvent.click(screen.getByRole("button", { name: "Retry upload" }));
    await waitFor(() => expect(confirms).toBe(1));
    expect(starts).toBe(2);
    expect(upload).toHaveBeenCalledTimes(2);
  });

  it("removes a photo without an Owner PIN body and treats 204 as success", async () => {
    const requests: Request[] = [];
    const image = { id: "i-1", contentType: "image/jpeg", mediumUrl: "https://signed/photo.jpg", smallUrl: "https://signed/small.jpg", urlsExpireAt: meta.serverTime };
    const api = createAdminApiClient({ apiBaseUrl: "http://api" }, () => "token", async (request) => { requests.push(request); return request.method === "GET" ? response(200, { data: [image], meta }) : response(204); });
    render(<AdminPhotoManager api={api} venueId="v-1" />);
    expect(await screen.findByText("Venue-card photo")).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Hide photo" }));
    await screen.findByText("No photos yet.");
    expect(requests[1]!.url).toBe("http://api/api/v1/admin/venues/v-1/images/i-1/remove");
    expect(await requests[1]!.text()).toBe("");
    expect(screen.queryByText(/Owner PIN/i)).toBeNull();
  });

  it("saves the complete reordered photo list", async () => {
    const requests: Request[] = [];
    const images = ["i-1", "i-2"].map((id) => ({ id, contentType: "image/jpeg", mediumUrl: `https://signed/${id}.jpg`, smallUrl: `https://signed/${id}-small.jpg`, urlsExpireAt: meta.serverTime }));
    const api = createAdminApiClient({ apiBaseUrl: "http://api" }, () => "token", async (request) => { requests.push(request); return request.method === "GET" ? response(200, { data: images, meta }) : response(200, { data: [...images].reverse(), meta }); });
    render(<AdminPhotoManager api={api} venueId="v-1" />);
    const moveDown = await screen.findAllByRole("button", { name: "Move down" });
    fireEvent.click(moveDown[0]!);
    await waitFor(() => expect(requests).toHaveLength(2));
    expect(JSON.parse(await requests[1]!.text())).toEqual({ imageIds: ["i-2", "i-1"] });
  });

  it("shows upload progress while the signed byte upload is in flight", async () => {
    let finishUpload: (response: Response) => void = () => undefined;
    const api = createAdminApiClient({ apiBaseUrl: "http://api" }, () => "token", async (request) => {
      if (request.method === "GET") return response(200, { data: [], meta });
      if (request.url.endsWith("/images")) return response(201, { data: { image: { id: "i-1", contentType: "image/jpeg", mediumUrl: null, smallUrl: null, urlsExpireAt: null }, uploadUrl: "https://upload/1", uploadExpiresAt: meta.serverTime }, meta });
      return response(200, { data: { id: "i-1", contentType: "image/jpeg", mediumUrl: "https://signed/photo.jpg", smallUrl: "https://signed/small.jpg", urlsExpireAt: meta.serverTime }, meta });
    });
    vi.stubGlobal("fetch", () => new Promise<Response>((resolve) => { finishUpload = resolve; }));
    render(<AdminPhotoManager api={api} venueId="v-1" />);
    await screen.findByText("No photos yet.");
    fireEvent.change(screen.getByLabelText("Add photo"), { target: { files: [new File(["bytes"], "venue.jpg", { type: "image/jpeg" })] } });
    expect(await screen.findByText("Uploading…")).toBeTruthy();
    finishUpload(new Response(undefined, { status: 200 }));
    await waitFor(() => expect(screen.queryByText("Uploading…")).toBeNull());
  });

  it("rolls back an optimistic reorder when the API refuses it", async () => {
    const images = ["i-1", "i-2"].map((id) => ({ id, contentType: "image/jpeg", mediumUrl: `https://signed/${id}.jpg`, smallUrl: `https://signed/${id}-small.jpg`, urlsExpireAt: meta.serverTime }));
    const api = createAdminApiClient({ apiBaseUrl: "http://api" }, () => "token", async (request) => request.method === "GET"
      ? response(200, { data: images, meta })
      : response(422, { error: { code: "VENUE_IMAGE_NOT_UPLOADED" }, meta }));
    render(<AdminPhotoManager api={api} venueId="v-1" />);
    const moveDown = await screen.findAllByRole("button", { name: "Move down" });
    fireEvent.click(moveDown[0]!);
    expect((await screen.findByRole("alert")).textContent).toBe("The image upload was not completed.");
    const photos = screen.getAllByRole("img");
    expect(photos[0]!.getAttribute("src")).toContain("i-1.jpg");
  });
});
