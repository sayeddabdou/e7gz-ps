import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { createAdminApiClient } from "../../api";
import { AttendanceCorrectionScreen } from "./AttendanceCorrectionScreen";

afterEach(cleanup);
const meta = { correlationId: "c-1", serverTime: "2026-11-17T10:00:00Z" };
const json = (body: unknown) => new Response(JSON.stringify(body), { status: 200, headers: { "Content-Type": "application/json" } });

describe("AttendanceCorrectionScreen", () => {
  it("has no generic undo and requires an explicit outcome and reason", async () => {
    const requests: Request[] = [];
    const api = createAdminApiClient({ apiBaseUrl: "http://api" }, () => "token", async (request: Request) => { requests.push(request); return json({ data: { bookingId: "11111111-1111-4111-8111-111111111111", status: "COMPLETED", attendanceResult: "ATTENDED", checkedInAt: null, latePresentAt: null, noShowAt: null, terminalReason: null }, meta }); });
    render(<AttendanceCorrectionScreen api={api} />);
    expect(screen.queryByRole("button", { name: /undo/i })).toBeNull();
    expect(screen.queryByRole("option", { name: "Not applicable" })).toBeNull();
    expect(screen.getByRole("button", { name: "Correct attendance" }).hasAttribute("disabled")).toBe(true);
    fireEvent.change(screen.getByLabelText("Booking ID"), { target: { value: "11111111-1111-4111-8111-111111111111" } });
    fireEvent.change(screen.getByLabelText("Corrected outcome"), { target: { value: "ATTENDED" } });
    fireEvent.change(screen.getByLabelText("Reason"), { target: { value: "Venue evidence confirms attendance" } });
    fireEvent.click(screen.getByRole("button", { name: "Correct attendance" }));
    await waitFor(() => expect(requests).toHaveLength(1));
    expect(requests[0]!.headers.get("Idempotency-Key")).toBeTruthy();
    expect(JSON.parse(await requests[0]!.text())).toEqual({ correctedOutcome: "ATTENDED", reason: "Venue evidence confirms attendance" });
  });
});
