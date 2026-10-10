import { describe, expect, it, vi } from "vitest";
import { RequestAlertQueue, parseSseFrames, readVenueStream } from "./liveStream";

describe("venue live request stream", () => {
  it("parses fragmented change events and ignores heartbeats", () => {
    const first = parseSseFrames("", 'event: change\ndata: {"type":"BOOKING"');
    expect(first.signals).toEqual([]);

    const second = parseSseFrames(first.buffer, ',"id":"b-1","reason":"CREATED"}\n\n: heartbeat\n\n');
    expect(second.buffer).toBe("");
    expect(second.signals).toEqual([{ type: "BOOKING", id: "b-1", reason: "CREATED" }]);
  });

  it("plays once per new booking or tournament request, including queued autoplay-blocked alerts", async () => {
    const play = vi.fn().mockResolvedValue(undefined);
    const alerts = new RequestAlertQueue(play);
    alerts.seed("BOOKING", ["old-booking"]);

    alerts.receive({ type: "BOOKING", id: "old-booking", reason: "CREATED" });
    alerts.receive({ type: "BOOKING", id: "b-1", reason: "CREATED" });
    alerts.receive({ type: "BOOKING", id: "b-1", reason: "CREATED" });
    alerts.receive({ type: "BOOKING", id: "b-1", reason: "CHANGED" });
    expect(play).not.toHaveBeenCalled();

    await alerts.enable();
    expect(play).toHaveBeenCalledTimes(1);

    alerts.receive({ type: "TOURNAMENT", id: "t-1", reason: "REQUESTED" });
    alerts.receive({ type: "TOURNAMENT", id: "t-1", reason: "REQUESTED" });
    await Promise.resolve();
    expect(play).toHaveBeenCalledTimes(2);
  });

  it("reports when audio becomes blocked again", async () => {
    const blocked = vi.fn();
    const alerts = new RequestAlertQueue(vi.fn().mockRejectedValue(new Error("suspended")), blocked);
    alerts.receive({ type: "BOOKING", id: "b-1", reason: "CREATED" });

    await expect(alerts.enable()).rejects.toThrow("suspended");
    expect(blocked).toHaveBeenCalledTimes(1);
  });

  it("ends a stream that misses the heartbeat watchdog", async () => {
    const response = new Response(new ReadableStream({ start: () => undefined }), { status: 200 });
    await expect(readVenueStream(response, vi.fn(), new AbortController().signal, 5))
      .rejects.toThrow("stream-stalled");
  });
});
