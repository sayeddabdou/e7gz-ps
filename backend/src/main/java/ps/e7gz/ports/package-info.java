/**
 * Outbound ports (arch 03 section 18). Only the DEFERRED {@code PaymentProvider} (ADR-018) lives here: the launch
 * adapters' ports sit in the module that uses them (SMS in identity, push and email in notification, object storage in
 * venue).
 * <p>Lane: Platform (Sayed). Filled by: E7-66 (PaymentProvider, PO decision 23 Oct 2026).
 */
package ps.e7gz.ports;
