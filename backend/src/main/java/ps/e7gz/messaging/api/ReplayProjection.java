package ps.e7gz.messaging.api;

import ps.e7gz.common.command.CommandContext;

/**
 * Re-applies, on a replay, a read rule that depends on time or state to the stored response - for a booking, the
 * counterpart phone retention rule (arch 10 section 5.1, FR-PTS-20, NFR-18): a replay up to 30 days later must not
 * show a phone the caller can no longer see. It runs in the replay's own transaction, under the command's locks, with
 * {@link CommandContext#commandNow()} as "now"; it may read the database through {@link CommandContext#jdbc()}.
 *
 * <p><b>Who is responsible for what.</b>
 * <ul>
 *   <li><b>Platform (this wrapper)</b> - stores and replays only for the authenticated principal that made the request
 *       (the record is keyed by the access token's subject; an unauthenticated or wrong-route-group caller is refused
 *       by the security chain before the controller, and an account no longer active is refused per request); never
 *       runs the business command on a replay; refuses a response type with a phone field that declares no projection;
 *       and refuses a projection that does anything but omit object members - adds a member (even a {@code null} one),
 *       changes a value (to {@code null} too), or changes an array's size, order or elements - a defect, so the replay
 *       fails with a server error rather than showing it.</li>
 *   <li><b>The owning business module</b> (booking: E7-46, E7-47/48, E7-49) - the rule itself: the projection must
 *       call the same contact visibility resolver as its normal read path, against the booking's <i>current</i> state,
 *       and leave out every phone the rule hides now. The Platform cannot see whether the rule is right: a projection
 *       that returns {@code stored} unchanged passes every Platform check. Each owning task therefore adds an
 *       integration test: create the response, move the booking past its retention boundary (e.g.
 *       {@code terminalAt + 2h}, {@code endAt + 2h}, {@code venueResponseExpiredAt + 2h}), replay with the same key and
 *       body, and assert the phone is absent from {@code data} - and present on a replay before the boundary.</li>
 * </ul>
 * A replay does not re-check business authorization (the request already passed it and committed); it is the same
 * principal re-reading its own result, through this projection.
 *
 * <p>It may only <b>remove</b> what the rule hides now (e.g. leave the phone absent, as the contract allows); it never
 * adds data or changes a value. A removed member must be <i>absent</i> from the written JSON, not {@code null}: return
 * the field as {@code null} and give it {@code @JsonInclude(NON_NULL)} in the DTO (the contract already requires a
 * hidden phone to be absent). The stored response itself is not changed.
 */
@FunctionalInterface
public interface ReplayProjection<R> {

    R onReplay(CommandContext context, R stored);
}
