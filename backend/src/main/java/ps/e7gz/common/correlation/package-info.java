/**
 * The correlation ID of the current unit of work (NFR-32, arch 09 §1.2, arch 11 §5.1). Kept outside {@code common.web}
 * so that audit, outbox and workers — which must not depend on the web layer — can read and restore it.
 * <p>Filled by: E7-12.
 */
package ps.e7gz.common.correlation;
