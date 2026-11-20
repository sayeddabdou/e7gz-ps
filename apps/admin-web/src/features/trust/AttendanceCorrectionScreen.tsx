import { useRef, useState, type FormEvent } from "react";
import type { components } from "@e7gz/api-client";
import { createIdempotencyKey, errorCodeOf, errorFieldOf, idempotencyHeaders, type ApiClient } from "../../api";
import { messages as t } from "../../i18n/messages";
import { adminErrorMessage } from "../adminErrors";

type Outcome = components["schemas"]["AttendanceResult"];
type Operation = "correct" | "reverse-check-in" | "reverse-late-present" | "reverse-no-show";

export function AttendanceCorrectionScreen({ api }: { api: ApiClient }) {
  const [bookingId, setBookingId] = useState("");
  const [operation, setOperation] = useState<Operation>("correct");
  const [outcome, setOutcome] = useState<Outcome | "">("");
  const [reason, setReason] = useState("");
  const [working, setWorking] = useState(false);
  const [message, setMessage] = useState<{ kind: "error" | "success"; text: string }>();
  const inFlight = useRef(false);
  const idempotencyKey = useRef(createIdempotencyKey());
  const ready = Boolean(bookingId.trim() && reason.trim() && (operation !== "correct" || outcome));

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    if (!ready || inFlight.current) return;
    inFlight.current = true;
    setWorking(true); setMessage(undefined);
    const params = { path: { bookingId: bookingId.trim() }, header: idempotencyHeaders(idempotencyKey.current) };
    try {
      const result = operation === "correct"
        ? await api.POST("/api/v1/admin/bookings/{bookingId}/attendance/correct", { params, body: { correctedOutcome: outcome as Outcome, reason: reason.trim() } })
        : operation === "reverse-check-in"
          ? await api.POST("/api/v1/admin/bookings/{bookingId}/attendance/reverse-check-in", { params, body: { reason: reason.trim() } })
          : operation === "reverse-late-present"
            ? await api.POST("/api/v1/admin/bookings/{bookingId}/attendance/reverse-late-present", { params, body: { reason: reason.trim() } })
            : await api.POST("/api/v1/admin/bookings/{bookingId}/attendance/reverse-no-show", { params, body: { reason: reason.trim() } });
      if (result.error) setMessage({ kind: "error", text: adminErrorMessage(errorCodeOf(result.error), errorFieldOf(result.error)) });
      else { setMessage({ kind: "success", text: `Attendance updated to ${String(result.data.data.attendanceResult ?? "no recorded outcome").replaceAll("_", " ")}.` }); idempotencyKey.current = createIdempotencyKey(); }
    } catch { setMessage({ kind: "error", text: t.networkError }); }
    finally { inFlight.current = false; setWorking(false); }
  };

  return <section className="admin-page" aria-labelledby="attendance-title">
    <header className="page-heading"><span className="page-eyebrow">Trust remediation</span><h2 id="attendance-title">Attendance Correction</h2></header>
    <p className="notice notice--info">{t.e75.attendanceCorrectionEffect}</p>
    <form className="form-card form-grid" onSubmit={(event) => void submit(event)}>
      <label>Booking ID<input pattern="[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89aAbB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}" required title="Enter a valid booking UUID." value={bookingId} onChange={(event) => { setBookingId(event.target.value); idempotencyKey.current = createIdempotencyKey(); }} /></label>
      <label>Action<select value={operation} onChange={(event) => { setOperation(event.target.value as Operation); idempotencyKey.current = createIdempotencyKey(); }}><option value="correct">Correct attendance outcome</option><option value="reverse-check-in">Reverse check-in</option><option value="reverse-late-present">Reverse late present</option><option value="reverse-no-show">Reverse no-show</option></select></label>
      {operation === "correct" && <label>Corrected outcome<select value={outcome} onChange={(event) => { setOutcome(event.target.value as Outcome | ""); idempotencyKey.current = createIdempotencyKey(); }}><option value="">Choose an outcome</option><option value="ATTENDED">Attended</option><option value="ATTENDANCE_UNCONFIRMED">Attendance unconfirmed</option><option value="ATTENDED_INTERRUPTED">Attended — interrupted</option><option value="ABSENT_NO_SHOW">Absent — no-show</option></select></label>}
      <label className="form-span">Reason<textarea maxLength={500} rows={3} value={reason} onChange={(event) => { setReason(event.target.value); idempotencyKey.current = createIdempotencyKey(); }} /></label>
      {message && <p className={`notice notice--${message.kind} form-span`} role={message.kind === "error" ? "alert" : "status"}>{message.text}</p>}
      <div className="form-actions form-span"><button className="button button--primary" disabled={working || !ready} type="submit">{operation === "correct" ? "Correct attendance" : "Apply reversal"}</button></div>
    </form>
  </section>;
}
