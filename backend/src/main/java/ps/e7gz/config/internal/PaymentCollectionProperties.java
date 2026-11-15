package ps.e7gz.config.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Whether a payment provider collects the Booking Fee ({@code e7gz.payments.*}; ADR-018, PCR-010; PO decision
 * 15 Nov 2026). A deployment setting, not a Product rule and not a secret.
 *
 * @param feeCollectionEnabled {@code false} at launch: no payment provider exists, so the part of a fee that Points
 *                             and the Wallet do not pay would stay uncollected - a Customer with Points would spend
 *                             them while a Customer without any pays nothing. Until the payment task turns this on
 *                             (with its adapter), the Platform Admin cannot set a Booking Fee above EGP 0.
 */
@ConfigurationProperties("e7gz.payments")
record PaymentCollectionProperties(@DefaultValue("false") boolean feeCollectionEnabled) {
}
