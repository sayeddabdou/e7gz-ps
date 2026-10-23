package ps.e7gz.common.codes;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Cause of a wallet transaction - {@code ck_wallet_tx_launch_source} (cause; literal branch). At launch the wallet is
 * credited only by booking-fee refunds (FR-PTS-10, PCR-004).
 */
@Schema(enumAsRef = true)
public enum WalletTransactionCause {
    BOOKING_FEE_REFUND
}
