/**
 * Boundary tests of later tasks (E7-45): written now from the roadmap's Phase 2 and Phase 3 exit criteria and arch 08
 * section 9, {@code @Disabled} with the task that turns each green. That task moves its class next to the code it
 * tests, replaces each {@code fail(...)} with the steps the test names, using the boundary pack
 * ({@code ps.e7gz.support.boundary}), and removes {@code @Disabled}. E7-61 enabled the last of the Phase 3 timer
 * tests ("boundary pack green"): T5 is {@code ps.e7gz.booking.internal.BookingCompletionBoundaryTest}, beside T2/T3
 * ({@code LateConfirmBoundaryTest}, E7-58) and T4 ({@code BookingStartBoundaryTest}, E7-60).
 */
package ps.e7gz.support.boundary.pending;
