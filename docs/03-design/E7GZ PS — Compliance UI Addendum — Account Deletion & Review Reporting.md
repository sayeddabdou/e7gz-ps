# E7GZ PS — Compliance UI Addendum
# Account Deletion & Review Reporting

**Authority:** Product Spec v5.0 with PCR-002/PCR-003. This addendum supplements the existing prototypes only for these two newly approved flows.

## Customer App — Delete Account

- Entry point: Profile / Account settings → **Delete Account**.
- Confirmation explains that the request starts deletion and that retained Booking/financial/audit history is preserved as required.
- Confirm action calls `POST /customer/account/deletion-request`.
- Success state shows **Deletion request received**. No destructive client-side deletion is implied.
- Repeating the action while a request is unresolved shows the existing request state instead of creating a second operation.

## Customer App — Report Review

- Every **publicly visible ACTIVE Review** exposes **Report**.
- Report sheet/form: mandatory Reason, optional Details, Submit/Cancel.
- Success confirms the report was submitted. The Review stays visible unless a separate Admin moderation action changes it.
- If the Customer already has an unresolved report on the same Review, the API returns `REVIEW_REPORT_ALREADY_OPEN`; show that the report is already under review instead of a second form. Reviews of deleted accounts are no longer public and expose no Report action.

## Platform Admin — Review Reports

- Reviews moderation gains a **Reports** queue/list.
- Admin sees Review, reporter reference, reason/details, submitted time and resolution state.
- Resolve requires a resolution note. Existing Hide/Remove/Restore/Approve controls remain unchanged; a report may link to the resulting moderation event.

## Platform Admin — Account Deletion Requests

- Users area surfaces unresolved deletion requests.
- Completing a request uses the existing anonymisation action/O19; it is not a separate destructive-delete workflow. Completion blanks personal data, signs the Customer out everywhere, removes their Reviews from public display and closes the request in one step (`POST /admin/users/{id}/anonymise`).
