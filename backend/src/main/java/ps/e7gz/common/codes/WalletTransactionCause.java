package ps.e7gz.common.codes;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Cause of a wallet transaction - {@code ck_wallet_tx_source} (cause; literal branches). The wallet is credited only
 * by booking-fee refunds (FR-PTS-10) and spent only on a later booking's booking fee (PCR-009): never a top-up, a
 * withdrawal or the venue gaming charge.
 */
@Schema(enumAsRef = true)
public enum WalletTransactionCause {
    BOOKING_FEE_REFUND,
    BOOKING_FEE_PAYMENT
}
