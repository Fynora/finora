package com.finora.service;

/**
 * How a purchase was paid, as the Reports "How you paid" card groups it.
 *
 * <p>Worked out when the report is built, never stored: {@code transactions.payment_method} exists
 * but nothing in the app has ever written it, so it is empty for every row. A value read off the
 * statement wording at report time is also always consistent with the rows as they are now, and
 * needs no back-fill of the rows already imported.
 *
 * <p>{@link #UNIDENTIFIED} is a real answer, not a failure bucket to be minimised by guessing: a
 * narration that names no rail is reported as unidentified and the card says how much of the
 * spending that is.
 */
public enum PaymentMethod {
    /** UPI: "UPI/...", "TO TRANSFER-UPI/...". */
    UPI,
    /** A card purchase: every spend on a credit-card account, or a debit-card POS / e-commerce row. */
    CARD,
    /** NEFT, IMPS, RTGS or an internal fund transfer. */
    BANK_TRANSFER,
    /** An ATM or cash withdrawal. */
    CASH,
    /** A cheque, or a clearing entry. */
    CHEQUE,
    /** A standing mandate: NACH, ECS, ACH debit, auto-pay. */
    AUTO_DEBIT,
    /** A wallet account's own spending. */
    WALLET,
    /** The statement wording names no payment method. */
    UNIDENTIFIED
}
