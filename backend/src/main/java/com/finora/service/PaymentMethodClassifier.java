package com.finora.service;

import com.finora.entity.Account;
import com.finora.entity.Transaction;

import java.util.Locale;
import java.util.Map;

/**
 * Names how a transaction was paid, from the account it sits on and, for a bank account, the words
 * at the front of its statement narration.
 *
 * <h2>What it relies on</h2>
 * Indian bank narrations lead with the rail: {@code UPI/DR/<ref>/<payee>}, {@code NEFT-<ref>-<payee>},
 * {@code POS <card> <merchant>}, {@code ATM WDL <place>}. {@code TO TRANSFER-UPI/...} (SBI) puts a
 * generic word or two first. So the rail is the first recognised token among the first
 * {@value #WINDOW} tokens, and the earliest one wins. Nothing later in the text is read: a payee
 * called "Atm Cafe" paid by UPI is UPI, and a merchant named after a rail word further along cannot
 * change the answer.
 *
 * <h2>What it does not do</h2>
 * It does not guess. A narration with no recognised rail word is {@link PaymentMethod#UNIDENTIFIED},
 * which the report shows as such with its share of the spending. Wrongly naming a method would put
 * money in the wrong row of a card the user trusts; leaving it unnamed costs only coverage. For the
 * same reason the word list is short and every entry is a rail or instrument name, never a word
 * that could equally start a payee ("card" is left out because "CREDIT CARD PAYMENT" is a bill
 * payment, not a card purchase).
 *
 * <h2>Account type decides first</h2>
 * Everything spent on a credit-card account is a card payment, whatever the statement says, and a
 * wallet account's spending is wallet spending. Only a bank account's rows are read for a rail, and
 * only those that came from a bank: statement imports and Account Aggregator syncs. A row typed in
 * by hand or built from an e-mail has free-text wording, not a narration.
 */
public final class PaymentMethodClassifier {

    private PaymentMethodClassifier() {}

    /** How many leading tokens are searched for a rail word. */
    static final int WINDOW = 4;

    private static final Map<String, PaymentMethod> RAIL_WORDS = Map.ofEntries(
            Map.entry("upi", PaymentMethod.UPI),

            Map.entry("pos", PaymentMethod.CARD),
            Map.entry("ecom", PaymentMethod.CARD),
            Map.entry("pcd", PaymentMethod.CARD),

            Map.entry("atm", PaymentMethod.CASH),
            Map.entry("atw", PaymentMethod.CASH),
            Map.entry("nwd", PaymentMethod.CASH),
            Map.entry("cwdr", PaymentMethod.CASH),
            Map.entry("cash", PaymentMethod.CASH),

            Map.entry("neft", PaymentMethod.BANK_TRANSFER),
            Map.entry("imps", PaymentMethod.BANK_TRANSFER),
            Map.entry("rtgs", PaymentMethod.BANK_TRANSFER),
            Map.entry("ift", PaymentMethod.BANK_TRANSFER),
            Map.entry("mmt", PaymentMethod.BANK_TRANSFER),
            Map.entry("tpt", PaymentMethod.BANK_TRANSFER),
            Map.entry("inb", PaymentMethod.BANK_TRANSFER),

            Map.entry("chq", PaymentMethod.CHEQUE),
            Map.entry("cheque", PaymentMethod.CHEQUE),
            Map.entry("clg", PaymentMethod.CHEQUE),
            Map.entry("byclg", PaymentMethod.CHEQUE),

            Map.entry("nach", PaymentMethod.AUTO_DEBIT),
            Map.entry("ach", PaymentMethod.AUTO_DEBIT),
            Map.entry("ecs", PaymentMethod.AUTO_DEBIT),
            Map.entry("emandate", PaymentMethod.AUTO_DEBIT),
            Map.entry("mandate", PaymentMethod.AUTO_DEBIT),
            Map.entry("autopay", PaymentMethod.AUTO_DEBIT)
    );

    /**
     * @param accountType the type of the account the transaction sits on; null is treated as a bank account
     */
    public static PaymentMethod classify(Transaction t, Account.Type accountType) {
        if (accountType == Account.Type.CREDIT_CARD) return PaymentMethod.CARD;
        if (accountType == Account.Type.WALLET) return PaymentMethod.WALLET;
        // Only a bank's own narration carries a rail. A row the user typed in, or one built from an
        // e-mail, has a description that is free text ("Lunch at ATM cafe"), where a rail word near
        // the front is a coincidence, not a statement.
        if (t.getSource() != Transaction.Source.CSV_IMPORT && t.getSource() != Transaction.Source.ACCOUNT_AGGREGATOR) {
            return PaymentMethod.UNIDENTIFIED;
        }
        return fromNarration(t.getDescription());
    }

    /** The rail named at the front of {@code narration}, or {@link PaymentMethod#UNIDENTIFIED}. */
    static PaymentMethod fromNarration(String narration) {
        if (narration == null || narration.isBlank()) return PaymentMethod.UNIDENTIFIED;
        String[] tokens = narration.toLowerCase(Locale.ROOT).split("[^a-z0-9]+");
        int seen = 0;
        for (String token : tokens) {
            if (token.isEmpty()) continue;
            PaymentMethod method = RAIL_WORDS.get(token);
            if (method != null) return method;
            if (++seen >= WINDOW) break;
        }
        return PaymentMethod.UNIDENTIFIED;
    }
}
