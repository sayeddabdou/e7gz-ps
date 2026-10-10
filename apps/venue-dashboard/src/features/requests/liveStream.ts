import type { components } from "@e7gz/api-client";

export type VenueStreamSignal = components["schemas"]["VenueStreamSignal"];

function isSignal(value: unknown): value is VenueStreamSignal {
  if (!value || typeof value !== "object") return false;
  const candidate = value as Partial<VenueStreamSignal>;
  return (candidate.type === "BOOKING" || candidate.type === "TOURNAMENT")
    && (candidate.reason === "CREATED" || candidate.reason === "REQUESTED" || candidate.reason === "CHANGED")
    && typeof candidate.id === "string";
}

/** Incremental SSE parser; heartbeat comments are deliberately ignored (arch 08 §5). */
export function parseSseFrames(buffer: string, chunk: string): { buffer: string; signals: VenueStreamSignal[] } {
  const frames = `${buffer}${chunk}`.replace(/\r\n/gu, "\n").split("\n\n");
  const remaining = frames.pop() ?? "";
  const signals: VenueStreamSignal[] = [];
  for (const frame of frames) {
    if (!frame || frame.startsWith(":")) continue;
    const event = frame.split("\n").find((line) => line.startsWith("event:"))?.slice(6).trim();
    const data = frame.split("\n").filter((line) => line.startsWith("data:")).map((line) => line.slice(5).trim()).join("\n");
    if (event !== "change" || !data) continue;
    try {
      const parsed: unknown = JSON.parse(data);
      if (isSignal(parsed)) signals.push(parsed);
    } catch {
      // A malformed signal carries no business data and is safe to ignore; the poll is the correctness backstop.
    }
  }
  return { buffer: remaining, signals };
}

export class RequestAlertQueue {
  private enabled = false;
  private readonly queued: VenueStreamSignal[] = [];
  private readonly seen = new Set<string>();

  constructor(
    private readonly play: () => Promise<void> | void,
    private readonly onBlocked: () => void = () => undefined,
  ) {}

  seed(type: VenueStreamSignal["type"], ids: Iterable<string>): void {
    for (const id of ids) this.seen.add(`${type}:${id}`);
  }

  receive(signal: VenueStreamSignal): void {
    if (signal.reason !== "CREATED" && signal.reason !== "REQUESTED") return;
    const key = `${signal.type}:${signal.id}`;
    if (this.seen.has(key)) return;
    this.seen.add(key);
    if (!this.enabled) {
      this.queued.push(signal);
      return;
    }
    void Promise.resolve(this.play()).catch(() => {
      this.enabled = false;
      this.onBlocked();
    });
  }

  async enable(): Promise<void> {
    this.enabled = true;
    while (this.queued.length > 0) {
      this.queued.shift();
      try {
        await this.play();
      } catch (error) {
        this.enabled = false;
        this.onBlocked();
        throw error;
      }
    }
  }
}

export async function readVenueStream(
  response: Response,
  onSignal: (signal: VenueStreamSignal) => void,
  signal: AbortSignal,
  idleMilliseconds = 45_000,
): Promise<void> {
  if (!response.ok) throw new Error(`stream-${response.status}`);
  if (!response.body) throw new Error("stream-body-missing");
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";
  try {
    while (!signal.aborted) {
      let timeout: number | undefined;
      const stalled = new Promise<never>((_resolve, reject) => {
        timeout = window.setTimeout(() => reject(new Error("stream-stalled")), idleMilliseconds);
      });
      const result = await Promise.race([reader.read(), stalled]).finally(() => window.clearTimeout(timeout));
      if (result.done) break;
      const parsed = parseSseFrames(buffer, decoder.decode(result.value, { stream: true }));
      buffer = parsed.buffer;
      parsed.signals.forEach(onSignal);
    }
  } finally {
    await reader.cancel().catch(() => undefined);
    reader.releaseLock();
  }
}

export function createBrowserAlertTone(): { supported: boolean; unlock: () => Promise<void>; play: () => Promise<void> } {
  const AudioContextConstructor = window.AudioContext
    ?? (window as typeof window & { webkitAudioContext?: typeof AudioContext }).webkitAudioContext;
  if (!AudioContextConstructor) {
    return { supported: false, unlock: async () => undefined, play: async () => undefined };
  }
  let context: AudioContext | undefined;
  const getContext = () => (context ??= new AudioContextConstructor());
  const unlock = async () => {
    const audio = getContext();
    if (audio.state === "suspended") await audio.resume();
  };
  const play = async () => {
    const audio = getContext();
    if (audio.state === "suspended") await audio.resume();
    const oscillator = audio.createOscillator();
    const gain = audio.createGain();
    oscillator.frequency.value = 880;
    gain.gain.setValueAtTime(0.0001, audio.currentTime);
    gain.gain.exponentialRampToValueAtTime(0.18, audio.currentTime + 0.02);
    gain.gain.exponentialRampToValueAtTime(0.0001, audio.currentTime + 0.32);
    oscillator.connect(gain);
    gain.connect(audio.destination);
    oscillator.start();
    oscillator.stop(audio.currentTime + 0.34);
  };
  return { supported: true, unlock, play };
}
