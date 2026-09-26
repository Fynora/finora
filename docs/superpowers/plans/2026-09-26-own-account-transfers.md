# Own-Account Transfers (Plan 3) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Recognise money moved between the user's own accounts automatically, so it counts as neither income nor spending.

**Architecture:** Two rules in `ReconciliationService`, persisted on the row like every other transfer. Rule 1 pairs a debit and credit on two of the user's accounts that share a 12-digit UPI/IMPS reference. Rule 2 marks a single row as a transfer (no partner) when the name in the narration's sender or payee slot is the user, read from the holder names on their accounts. A new stateless helper, `OwnAccountEvidence`, extracts references and slot names; `TransactionExplanationService` renders two new explanations.

**Tech Stack:** Java 21, Spring Boot, JUnit 5, AssertJ, Mockito, Maven (`./mvnw`).

**Spec:** `docs/superpowers/specs/2026-09-26-own-account-transfers-design.md`

## Global Constraints

- Work only in `/Users/sid/Downloads/finora-own-transfers` on branch `feature/own-transfers`. Never write in `/Users/sid/Downloads/finora`.
- Commit messages: no `Co-Authored-By` or any AI trailer. Scope from the commitlint enum (`transactions`, `analytics`, `docs`, ...); header ≤100 characters.
- No real narration, name or account number in the repository. Tests use synthetic names (`ASHA VERMA`, `RAVI KUMAR`) and references `111111111111` / `222222222222` (the fixture hook blocks other 12-digit numbers).
- No migration, no new table, no new screen, no web or mobile code.
- Reference = a run of exactly 12 digits, not adjacent to another digit, letters allowed on either side.
- NEFT beneficiary slot is never read.
- Rule 2 never applies to: a CREDIT_CARD account, counterpartyType BUSINESS, a salary-looking row, `transferRejectedAt` set, a duplicate, a row already REFUND/REVERSAL/INVESTMENT_TRANSFER/TRANSFER.
- Titles dropped from names: `mr mrs ms miss dr shri smt`.
- Full backend check is `./mvnw verify` from `backend/` (plain `test` skips integration tests).

## Review Focus

1. Two own transfers of the same amount on the same day with different references must pair by reference, never cross-paired. → Task 3, `twoSameAmountTransfersPairByTheirOwnReference`.
2. A failed UPI debit and its reversal credit on the SAME account share the reference; they must not become a transfer. → Task 3, `aReversalOnTheSameAccountIsNeverATransfer`.
3. A person whose name starts with the owner's name but is longer ("RAVI KUMAR SINGH" vs owner "RAVI KUMAR") must not be read as the owner. → Task 1, `aLongerNameThatStartsWithTheOwnersIsSomeoneElse`.
4. Running reconciliation twice must not change anything the first run decided, and "not a transfer" must stick for a one-sided transfer. → Task 4, `secondRunChangesNothing` and `undoneOneSidedTransferStaysUndone`.
5. A user whose accounts carry no holder name must see no rule-2 decision at all. → Task 4, `noHolderNameMeansNoOneSidedTransfer`.

---

## File Structure

- **Create** `backend/src/main/java/com/finora/util/OwnAccountEvidence.java` — pure static helpers: `references(description)`, `counterpartySlot(description)`, `ownerNames(holderNames)`, `namesOwner(slot, ownerNames)`. No Spring, no repository.
- **Create** `backend/src/test/java/com/finora/util/OwnAccountEvidenceTest.java`.
- **Modify** `backend/src/main/java/com/finora/service/ReconciliationService.java` — rule 1 inside the transfer pass (pass 2); new pass 2a for rule 2; keep one-sided rows as pairing candidates; audit count.
- **Modify** `backend/src/main/java/com/finora/service/ReconciliationExplanation.java` — `transfer(...)` gains a `sharedReference` overload; new `ownAccountByName(...)`.
- **Modify** `backend/src/main/java/com/finora/transactions/TransactionExplanationService.java` — summary and evidence for the two new explanations.
- **Modify** tests: `ReconciliationServiceTest.java`, `TransactionExplanationServiceTest.java`.

---

### Task 1: `OwnAccountEvidence` — references, slots, owner names

**Files:**
- Create: `backend/src/main/java/com/finora/util/OwnAccountEvidence.java`
- Test: `backend/src/test/java/com/finora/util/OwnAccountEvidenceTest.java`

**Interfaces:**
- Produces:
  - `public static Set<String> references(String description)` — every 12-digit run; empty set for null.
  - `public static Optional<String> counterpartySlot(String description)` — the raw text of the sender/payee slot for the shapes in the spec's table, empty when no shape matches.
  - `public static List<List<String>> ownerNames(Collection<String> holderNames)` — each holder name as lower-case letter-only words, titles and single letters dropped; names with fewer than 2 words are skipped; duplicates removed.
  - `public static boolean namesOwner(String slot, List<List<String>> ownerNames)` — the match rule below.

**Match rule (`namesOwner`)**, applied to each owner name; true if any matches:
1. Cut the slot at the first word containing a digit, and at a relation marker (`s o`, `d o`, `w o`, `c o` as two single letters, or `so`/`do`/`wo`/`co` as one word) — banks append "S O SH <father>" and account numbers after the name.
2. Lower-case, split on anything that is not a letter, drop titles and single letters.
3. At least 2 words remain, the owner name has at least 2 words.
4. Slot word 0 equals owner word 0; for i ≥ 1 up to the shorter length, owner word i starts with slot word i (bank truncation).
5. Any slot words beyond the owner name's length must each be a prefix of some owner word (bank repetition, "ASHA VERMA ASHA VER"); otherwise it is a different, longer name.

- [ ] **Step 1: Write the failing tests**

```java
package com.finora.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OwnAccountEvidenceTest {

    private static final List<List<String>> ASHA = OwnAccountEvidence.ownerNames(List.of("MRS ASHA VERMA"));

    // ---- references ----

    @Test void referencesAreTwelveDigitRunsEvenWhenGluedToLetters() {
        assertThat(OwnAccountEvidence.references("UPI-ASHA VERMA-asha@okbank-IFSC0000001-111111111111-UPI"))
                .containsExactly("111111111111");
        assertThat(OwnAccountEvidence.references("SentIMPS111111111111ASHA VERMA/IFSC0000001/IMPS"))
                .containsExactly("111111111111");
        assertThat(OwnAccountEvidence.references("UPI/CR/C111111111111/ ASHA VERMA/ ptye/x@ptyes/NA/"))
                .containsExactly("111111111111");
    }

    @Test void phoneCardAndLongAccountNumbersAreNotReferences() {
        assertThat(OwnAccountEvidence.references("UPI-ASHA VERMA-9999999999@ybl")).isEmpty();          // 10 digits
        assertThat(OwnAccountEvidence.references("CARD 4111111111111111 PURCHASE")).isEmpty();          // 16 digits
        assertThat(OwnAccountEvidence.references("NEFT TO 1111111111111 ASHA")).isEmpty();             // 13 digits
        assertThat(OwnAccountEvidence.references(null)).isEmpty();
    }

    // ---- slots ----

    @Test void readsTheSlotOfEveryObservedShape() {
        assertThat(OwnAccountEvidence.counterpartySlot("UPI-ASHA VERMA-asha@okbank-IFSC0000001-111111111111-UPI")).contains("ASHA VERMA");
        assertThat(OwnAccountEvidence.counterpartySlot("BANKCO LIMITED UPI-MR ASHA VERMA-asha@okbank-IFSC0000001-111111111111-UPI")).contains("MR ASHA VERMA");
        assertThat(OwnAccountEvidence.counterpartySlot("UPI/CR/111111111111/ASHA VERMA/BANK/asha@okbank/")).contains("ASHA VERMA");
        assertThat(OwnAccountEvidence.counterpartySlot("UPI/DR/111111111111/ASHA VERMA/BANK/asha@okbank/")).contains("ASHA VERMA");
        assertThat(OwnAccountEvidence.counterpartySlot("UPIAB/111111111111/CR/ASHA VERMA/BANK/asha@okbank")).contains("ASHA VERMA");
        assertThat(OwnAccountEvidence.counterpartySlot("UPIAR/111111111111/DR/ASHA VERMA/BANK/asha@okbank")).contains("ASHA VERMA");
        assertThat(OwnAccountEvidence.counterpartySlot("UPI/RRN 111111111111/UPI_ASHA VERMA ASHA VER")).contains("ASHA VERMA ASHA VER");
        assertThat(OwnAccountEvidence.counterpartySlot("MOB-IMPS-CR/ASHA VER/BANK /111111/IMPS/222/")).contains("ASHA VER");
        assertThat(OwnAccountEvidence.counterpartySlot("SentIMPS111111111111Asha Verma/IFSC0000001/IMPS")).contains("Asha Verma");
        assertThat(OwnAccountEvidence.counterpartySlot("NEFT CR-IFSC0000001-ASHA VERMA S O SH R VERMA-ASHA VERMA-REF1")).contains("ASHA VERMA S O SH R VERMA");
        assertThat(OwnAccountEvidence.counterpartySlot("NEFT*IFSC0000001*REF1 2*ASHA VERMA S 1111111 AT BRANCH")).contains("ASHA VERMA S 1111111 AT BRANCH");
    }

    @Test void neverReadsTheNeftBeneficiarySlot() {
        // Salary: the remitter is the employer, the beneficiary is the account holder.
        assertThat(OwnAccountEvidence.counterpartySlot("NEFT CR-IFSC0000001-EMPLOYERCO PVT LTD-ASHA VERMA-REF1 SALARY FOR JUL"))
                .contains("EMPLOYERCO PVT LTD");
    }

    @Test void freeTextHasNoSlot() {
        assertThat(OwnAccountEvidence.counterpartySlot("IGST DB @ 18.00% TRANSACTIONS FOR ASHA VERMA")).isEmpty();
        assertThat(OwnAccountEvidence.counterpartySlot(null)).isEmpty();
    }

    // ---- owner names ----

    @Test void ownerNamesDropTitlesInitialsAndOneWordNames() {
        assertThat(OwnAccountEvidence.ownerNames(List.of("MRS ASHA K VERMA", "ASHA", " ", "Asha Verma")))
                .containsExactly(List.of("asha", "verma"));
    }

    // ---- matching ----

    @Test void matchesTheOwnerIncludingTruncationTitlesAndRepetition() {
        assertThat(OwnAccountEvidence.namesOwner("ASHA VERMA", ASHA)).isTrue();
        assertThat(OwnAccountEvidence.namesOwner("MR ASHA VERMA", ASHA)).isTrue();
        assertThat(OwnAccountEvidence.namesOwner("ASHA VER", ASHA)).isTrue();
        assertThat(OwnAccountEvidence.namesOwner(" ASHA VERM", ASHA)).isTrue();
        assertThat(OwnAccountEvidence.namesOwner("ASHA VERMA ASHA VER", ASHA)).isTrue();
        assertThat(OwnAccountEvidence.namesOwner("ASHA VERMA S O SH R VERMA", ASHA)).isTrue();
        assertThat(OwnAccountEvidence.namesOwner("ASHA VERMA S 1111111 AT BRANCH", ASHA)).isTrue();
    }

    @Test void anySpellingOfTheOwnerCounts() {
        List<List<String>> owner = OwnAccountEvidence.ownerNames(List.of("ASHA RAMCHANDRA VERMA", "ASHA RAM VARMA"));
        assertThat(OwnAccountEvidence.namesOwner("ASHA RAM VARM", owner)).isTrue();
    }

    @Test void aSingleWordOrADifferentPersonIsNotTheOwner() {
        assertThat(OwnAccountEvidence.namesOwner("Asha", ASHA)).isFalse();
        assertThat(OwnAccountEvidence.namesOwner("ASHA PATEL", ASHA)).isFalse();
        assertThat(OwnAccountEvidence.namesOwner("EMPLOYERCO PVT LTD", ASHA)).isFalse();
        assertThat(OwnAccountEvidence.namesOwner(null, ASHA)).isFalse();
        assertThat(OwnAccountEvidence.namesOwner("ASHA VERMA", List.of())).isFalse();
    }

    @Test void aLongerNameThatStartsWithTheOwnersIsSomeoneElse() {
        List<List<String>> ravi = OwnAccountEvidence.ownerNames(List.of("RAVI KUMAR"));
        assertThat(OwnAccountEvidence.namesOwner("RAVI KUMAR SINGH", ravi)).isFalse();
        assertThat(OwnAccountEvidence.namesOwner("RAVI KUMAR RAVI KUM", ravi)).isTrue();
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd /Users/sid/Downloads/finora-own-transfers/backend && ./mvnw -q test -Dtest=OwnAccountEvidenceTest`
Expected: compilation failure, `cannot find symbol ... OwnAccountEvidence`.

- [ ] **Step 3: Implement**

```java
package com.finora.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Evidence that a transaction moved money between the user's OWN accounts (Plan 3,
 * docs/superpowers/specs/2026-09-26-own-account-transfers-design.md). Stateless; the
 * reconciliation pass decides what to do with it.
 *
 * <p>Two kinds, both measured on the real corpus: the 12-digit UPI/IMPS reference both legs of a
 * real own transfer print, and the user's own name in the fixed slot a bank prints the sender or
 * payee in. The slot matters: a salary NEFT prints the employee's own name as BENEFICIARY, so a
 * "narration contains the owner's name" rule would remove salary from income.
 */
public final class OwnAccountEvidence {

    private OwnAccountEvidence() {}

    private static final Pattern REFERENCE = Pattern.compile("(?<!\\d)\\d{12}(?!\\d)");

    /** Sender/payee slots, one pattern per observed bank shape; group 1 is the slot. Order matters
     *  only where two could match the same text, and none of these overlap. */
    private static final List<Pattern> SLOTS = List.of(
            // UPI dash: "UPI-<name>-<vpa>-..." (optionally after a bank-name prefix)
            Pattern.compile("(?i)(?:^|\\s)UPI-([^-]{2,60})-"),
            // UPI slash: "UPI/CR/<ref>/<name>/<bank>/", "UPIAB/<ref>/CR/<name>/", "UPIAR/<ref>/DR/<name>/"
            Pattern.compile("(?i)\\bUPI(?:AB|AR)?/(?:(?:CR|DR)/)?[^/]*?/(?:(?:CR|DR)/)?([^/]{2,60})/"),
            // UPI tail: ".../UPI_<name>"
            Pattern.compile("(?i)\\bUPI_([A-Za-z][A-Za-z .]{0,60})\\s*$"),
            // IMPS: "IMPS-CR/<name>/"
            Pattern.compile("(?i)\\bIMPS-CR/([^/]{2,60})/"),
            // IMPS glued: "SentIMPS<12 digits><name>/"
            Pattern.compile("(?i)\\bSentIMPS\\d{12}([^/]{2,60})/"),
            // NEFT credit: "NEFT CR-<ifsc>-<remitter>-<beneficiary>-<ref>" -- remitter only
            Pattern.compile("(?i)\\bNEFT CR-[A-Z0-9]{11}-([^-]{2,80})-"),
            // NEFT credit, star: "NEFT*<ifsc>*<ref>*<remitter> ..."
            Pattern.compile("(?i)\\bNEFT\\*[A-Z0-9]{11}\\*[^*]*\\*([^*]{2,80})"));

    private static final Set<String> TITLES = Set.of("mr", "mrs", "ms", "miss", "dr", "shri", "smt");
    private static final Set<String> RELATION_WORDS = Set.of("so", "do", "wo", "co");

    public static Set<String> references(String description) {
        Set<String> out = new LinkedHashSet<>();
        if (description == null) return out;
        Matcher m = REFERENCE.matcher(description);
        while (m.find()) out.add(m.group());
        return out;
    }

    public static Optional<String> counterpartySlot(String description) {
        if (description == null) return Optional.empty();
        for (Pattern p : SLOTS) {
            Matcher m = p.matcher(description);
            if (m.find()) return Optional.of(m.group(1));
        }
        return Optional.empty();
    }

    public static List<List<String>> ownerNames(Collection<String> holderNames) {
        Set<List<String>> out = new LinkedHashSet<>();
        for (String holder : holderNames) {
            List<String> words = nameWords(holder, false);
            if (words.size() >= 2) out.add(words);
        }
        return new ArrayList<>(out);
    }

    public static boolean namesOwner(String slot, List<List<String>> ownerNames) {
        List<String> words = nameWords(slot, true);
        if (words.size() < 2) return false;
        for (List<String> owner : ownerNames) {
            if (matches(words, owner)) return true;
        }
        return false;
    }

    private static boolean matches(List<String> slot, List<String> owner) {
        if (owner.size() < 2 || !slot.get(0).equals(owner.get(0))) return false;
        int shared = Math.min(slot.size(), owner.size());
        for (int i = 1; i < shared; i++) {
            if (!owner.get(i).startsWith(slot.get(i))) return false;
        }
        for (int i = shared; i < slot.size(); i++) {
            String extra = slot.get(i);
            if (owner.stream().noneMatch(w -> w.startsWith(extra))) return false; // a longer, different name
        }
        return true;
    }

    /** Lower-case letter-only words, titles and single letters dropped. For a slot, the text is
     *  first cut at the first word carrying a digit and at a relation marker ("S O", "D/O"). */
    private static List<String> nameWords(String text, boolean cutAtMarkers) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        String[] raw = text.trim().split("\\s+");
        for (int i = 0; i < raw.length; i++) {
            String token = raw[i];
            if (cutAtMarkers && token.chars().anyMatch(Character::isDigit)) break;
            String word = token.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
            if (cutAtMarkers && isRelationMarker(raw, i)) break;
            if (word.length() < 2 || TITLES.contains(word)) continue;
            out.add(word);
        }
        return out;
    }

    private static boolean isRelationMarker(String[] raw, int i) {
        String word = raw[i].toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
        if (RELATION_WORDS.contains(word)) return true;
        if (i + 1 >= raw.length) return false;
        String next = raw[i + 1].toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
        return word.length() == 1 && "sdwc".contains(word) && next.equals("o");
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `cd /Users/sid/Downloads/finora-own-transfers/backend && ./mvnw -q test -Dtest=OwnAccountEvidenceTest`
Expected: no output (pass). If a slot assertion fails, print `counterpartySlot(...)` for that input and fix the one pattern it belongs to; do not loosen `namesOwner`.

- [ ] **Step 5: Commit**

```bash
cd /Users/sid/Downloads/finora-own-transfers
git add backend/src/main/java/com/finora/util/OwnAccountEvidence.java backend/src/test/java/com/finora/util/OwnAccountEvidenceTest.java
git commit -m "feat(transactions): read UPI/IMPS references and the sender or payee slot from a narration"
```

---

### Task 2: Explanations for the two rules

**Files:**
- Modify: `backend/src/main/java/com/finora/service/ReconciliationExplanation.java` (`transfer(...)` at ~line 92, add methods before `envelope`)
- Modify: `backend/src/main/java/com/finora/transactions/TransactionExplanationService.java` (`reconciliationSummary` TRANSFER case ~line 189, `reconciliationEvidence` TRANSFER case ~line 221)
- Test: `backend/src/test/java/com/finora/transactions/TransactionExplanationServiceTest.java`

**Interfaces:**
- Produces:
  - `static Map<String, Object> transfer(Transaction self, Transaction counterpart, long dayWindowApplied, boolean relationshipIdentifierMatched, String sharedReference)` — same as the 4-arg one plus `reason.sharedReference` when non-null. The 4-arg overload delegates with `null`.
  - `static Map<String, Object> ownAccountByName(Transaction self, String nameOnPayment, String holderName)` — `envelope("TRANSFER", null, reason)` with `rule = "OWN_ACCOUNT_NAME"`, `direction = "SENDER"` (credit) or `"PAYEE"` (debit), `nameOnPayment`, `holderName`.
  - `static final String OWN_ACCOUNT_NAME_RULE = "OWN_ACCOUNT_NAME"` on `ReconciliationExplanation`, package-visible.

- [ ] **Step 1: Write the failing tests** (add to `TransactionExplanationServiceTest`, next to `reconciliationExplainsATransfer_withTheTransferPairId`)

```java
    @Test
    void reconciliationExplainsATransferMatchedByASharedReference() {
        UUID pairId = UUID.randomUUID();
        Transaction t = transaction(Transaction.DecisionSource.MANUAL, null, Transaction.Source.CSV_IMPORT);
        t.setReconciliationStatus(Transaction.ReconciliationStatus.TRANSFER);
        t.setTransfer(true);
        t.setTransferPairId(pairId);
        t.setReconciliationExplanation(java.util.Map.of("type", "TRANSFER", "reason", java.util.Map.of(
                "differentAccount", true, "oppositeDirection", true, "amountDifference", "0.00",
                "dateDifferenceDays", 0L, "dayWindowApplied", 4L, "relationshipIdentifierMatched", false,
                "sharedReference", "111111111111")));
        when(transactionRepository.findById(txnId)).thenReturn(Optional.of(t));

        var reconciliation = service.explain(userId, txnId).reconciliation();

        assertThat(reconciliation.summary()).contains("same reference");
        assertThat(reconciliation.evidence()).anyMatch(line -> line.contains("ending 1111"));
    }

    @Test
    void reconciliationExplainsAOneSidedOwnAccountTransfer() {
        Transaction t = transaction(Transaction.DecisionSource.MANUAL, null, Transaction.Source.CSV_IMPORT);
        t.setReconciliationStatus(Transaction.ReconciliationStatus.TRANSFER);
        t.setTransfer(true);
        t.setReconciliationExplanation(java.util.Map.of("type", "TRANSFER", "reason", java.util.Map.of(
                "rule", "OWN_ACCOUNT_NAME", "direction", "SENDER",
                "nameOnPayment", "ASHA VER", "holderName", "ASHA VERMA")));
        when(transactionRepository.findById(txnId)).thenReturn(Optional.of(t));

        var reconciliation = service.explain(userId, txnId).reconciliation();

        assertThat(reconciliation.matchedTransactionId()).isNull();
        assertThat(reconciliation.summary()).isEqualTo(
                "Money moved between your own accounts: the sender on this payment is you.");
        assertThat(reconciliation.evidence()).containsExactly(
                "Name on the payment: ASHA VER", "Your account holder name: ASHA VERMA");
    }
```

- [ ] **Step 2: Run to verify they fail**

Run: `cd /Users/sid/Downloads/finora-own-transfers/backend && ./mvnw -q test -Dtest=TransactionExplanationServiceTest`
Expected: both new tests FAIL (summary is the existing "Matched as a transfer between your own accounts, N day(s) apart.").

- [ ] **Step 3: Implement**

In `ReconciliationExplanation.java`, replace the existing `transfer(...)` method with:

```java
    static final String OWN_ACCOUNT_NAME_RULE = "OWN_ACCOUNT_NAME";

    static Map<String, Object> transfer(Transaction self, Transaction counterpart,
                                        long dayWindowApplied, boolean relationshipIdentifierMatched) {
        return transfer(self, counterpart, dayWindowApplied, relationshipIdentifierMatched, null);
    }

    /** @param sharedReference the 12-digit UPI/IMPS reference both legs print, when that is what
     *                         paired them (Plan 3, rule 1); null otherwise */
    static Map<String, Object> transfer(Transaction self, Transaction counterpart, long dayWindowApplied,
                                        boolean relationshipIdentifierMatched, String sharedReference) {
        Map<String, Object> reason = new LinkedHashMap<>();
        reason.put("differentAccount", !self.getAccountId().equals(counterpart.getAccountId()));
        reason.put("oppositeDirection", self.getTxnType() != counterpart.getTxnType());
        reason.put("amountDifference",
                self.getAmount().subtract(counterpart.getAmount()).abs().toPlainString());
        reason.put("dateDifferenceDays", daysBetween(self.getTxnDate(), counterpart.getTxnDate()));
        reason.put("dayWindowApplied", dayWindowApplied);
        reason.put("relationshipIdentifierMatched", relationshipIdentifierMatched);
        if (sharedReference != null) reason.put("sharedReference", sharedReference);
        return envelope("TRANSFER", counterpart.getId(), reason);
    }

    /** One leg of a transfer between the user's own accounts, recognised because the sender (on a
     *  credit) or payee (on a debit) slot names the user. No counterpart row -- the other account
     *  may never be imported. Plan 3, rule 2. */
    static Map<String, Object> ownAccountByName(Transaction self, String nameOnPayment, String holderName) {
        Map<String, Object> reason = new LinkedHashMap<>();
        reason.put("rule", OWN_ACCOUNT_NAME_RULE);
        reason.put("direction", self.getTxnType() == Transaction.Type.INCOME ? "SENDER" : "PAYEE");
        reason.put("nameOnPayment", nameOnPayment.trim());
        reason.put("holderName", holderName);
        return envelope("TRANSFER", null, reason);
    }
```

In `TransactionExplanationService.reconciliationSummary`, replace the `case TRANSFER -> { ... }` block with:

```java
            case TRANSFER -> {
                if ("OWN_ACCOUNT_NAME".equals(reason.get("rule"))) {
                    String who = "PAYEE".equals(reason.get("direction")) ? "payee" : "sender";
                    yield "Money moved between your own accounts: the " + who + " on this payment is you.";
                }
                Object days = reason.get("dateDifferenceDays");
                String apart = days != null ? ", " + days + " day(s) apart" : "";
                if (reason.get("sharedReference") != null) {
                    yield "Matched as a transfer between your own accounts: both sides carry the same reference" + apart + ".";
                }
                yield "Matched as a transfer between your own accounts" + apart + ".";
            }
```

In `reconciliationEvidence`, replace the `case TRANSFER -> List.of(...)` with:

```java
            case TRANSFER -> {
                if ("OWN_ACCOUNT_NAME".equals(reason.get("rule"))) {
                    yield List.of("Name on the payment: " + reason.getOrDefault("nameOnPayment", "?"),
                            "Your account holder name: " + reason.getOrDefault("holderName", "?"));
                }
                List<String> lines = new java.util.ArrayList<>(List.of(
                        "Opposite direction: " + reason.getOrDefault("oppositeDirection", "?"),
                        "Amount difference: ₹" + reason.getOrDefault("amountDifference", "0"),
                        "Days apart: " + reason.getOrDefault("dateDifferenceDays", "?")
                                + " (window: " + reason.getOrDefault("dayWindowApplied", "?") + ")"));
                Object ref = reason.get("sharedReference");
                if (ref != null) {
                    String s = ref.toString();
                    lines.add("Same reference on both sides, ending " + s.substring(Math.max(0, s.length() - 4)));
                }
                yield lines;
            }
```

If `reconciliationEvidence` is written as a `switch` expression whose other arms are single expressions, the block arms above are valid Java (`yield`). Check the method's return type is `List<String>`.

- [ ] **Step 4: Run to verify they pass**

Run: `cd /Users/sid/Downloads/finora-own-transfers/backend && ./mvnw -q test -Dtest='TransactionExplanationServiceTest,ReconciliationServiceTest'`
Expected: pass (the existing transfer test still sees "2 day(s) apart").

- [ ] **Step 5: Commit**

```bash
cd /Users/sid/Downloads/finora-own-transfers
git add backend/src/main/java/com/finora/service/ReconciliationExplanation.java backend/src/main/java/com/finora/transactions/TransactionExplanationService.java backend/src/test/java/com/finora/transactions/TransactionExplanationServiceTest.java
git commit -m "feat(transactions): explain own-account transfers matched by reference or by the user's name"
```

---

### Task 3: Rule 1 — pair legs that share a reference

**Files:**
- Modify: `backend/src/main/java/com/finora/service/ReconciliationService.java` (transfer pass, ~lines 509-690)
- Test: `backend/src/test/java/com/finora/service/ReconciliationServiceTest.java` (new tests after the card-bill tests, ~line 990)

**Interfaces:**
- Consumes: `OwnAccountEvidence.references(String)`; `ReconciliationExplanation.transfer(self, counterpart, dayWindow, relationshipMatched, sharedReference)`.
- Produces: pairs carry `reason.sharedReference`.

**Change:**
1. In the per-candidate precompute loop (where `looksLikeSalary` and `cardPaymentsReceived` are filled), add `Map<UUID, Set<String>> references` = `OwnAccountEvidence.references(t.getDescription())` per candidate.
2. Gate: `boolean aHasReference = !references.get(a.getId()).isEmpty();` and change the skip to `if (!looksLikeTransfer && !aCardPaymentReceived && !aHasReference) continue;`.
3. Inner loop, after the amount and window checks: compute `String shared = firstShared(references.get(a.getId()), references.get(b.getId()));` (null when none). When the gate was opened ONLY by the reference (`!looksLikeTransfer && !aCardPaymentReceived`), require `shared != null` (`continue` otherwise).
4. Score: `int score = transferCandidateScore(relationshipMatch, daysApart, dayWindow) + (shared != null ? 100 : 0);` and carry `bestMatchSharedReference`.
5. On a match use `ReconciliationExplanation.transfer(a, b, dayWindow, relationshipMatch, bestMatchSharedReference)` (and the mirror for b).
6. Add the helper:

```java
    private static String firstShared(Set<String> a, Set<String> b) {
        for (String r : a) if (b.contains(r)) return r;
        return null;
    }
```

Leave the card-bill guard, salary guard, rejection guard and same-account rule exactly as they are.

- [ ] **Step 1: Write the failing tests**

```java
    // --- Plan 3, rule 1: legs that share a UPI/IMPS reference ---

    @Test
    void reconcileForUser_pairsOwnAccountLegsThatShareAReference_withNoPaymentWord() {
        UUID hdfc = UUID.randomUUID();
        UUID union = UUID.randomUUID();
        Transaction out = txn(UUID.randomUUID(), hdfc, LocalDate.of(2026, 5, 1), new BigDecimal("50000.00"),
                Transaction.Type.EXPENSE, "UPI-ASHA VERMA-asha@okbank-IFSC0000001-111111111111-UPI", Instant.now());
        Transaction in = txn(UUID.randomUUID(), union, LocalDate.of(2026, 5, 1), new BigDecimal("50000.00"),
                Transaction.Type.INCOME, "UPIAB/111111111111/CR/ASHA /BANK/asha@okbank", Instant.now());
        when(transactionRepository.findByUserIdAndAccountIdIn(eq(userId), any())).thenReturn(List.of(out, in));

        reconciliationService.reconcileForUser(userId);

        assertThat(in.getTransferPairId()).isEqualTo(out.getId());
        assertThat(out.getTransferPairId()).isEqualTo(in.getId());
        @SuppressWarnings("unchecked")
        Map<String, Object> reason = (Map<String, Object>) in.getReconciliationExplanation().get("reason");
        assertThat(reason.get("sharedReference")).isEqualTo("111111111111");
    }

    @Test
    void reconcileForUser_pairsAKotakGluedReferenceWithTheCreditOneDayLater() {
        UUID kotak = UUID.randomUUID();
        UUID canara = UUID.randomUUID();
        Transaction out = txn(UUID.randomUUID(), kotak, LocalDate.of(2026, 7, 9), new BigDecimal("25000.00"),
                Transaction.Type.EXPENSE, "SentIMPS111111111111Asha Verma/IFSC0000001/IMPS", Instant.now());
        Transaction in = txn(UUID.randomUUID(), canara, LocalDate.of(2026, 7, 10), new BigDecimal("25000.00"),
                Transaction.Type.INCOME, "999-UPI-111111111111 Value Dt 10/07/2026", Instant.now());
        when(transactionRepository.findByUserIdAndAccountIdIn(eq(userId), any())).thenReturn(List.of(out, in));

        reconciliationService.reconcileForUser(userId);

        assertThat(in.getTransferPairId()).isEqualTo(out.getId());
    }

    @Test
    void reconcileForUser_equalAmountsWithDifferentReferencesAreNotPaired() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        Transaction out = txn(UUID.randomUUID(), a, LocalDate.of(2026, 5, 3), new BigDecimal("200.00"),
                Transaction.Type.EXPENSE, "UPI-RAVI KUMAR-ravi@okbank-IFSC0000001-111111111111-UPI", Instant.now());
        Transaction in = txn(UUID.randomUUID(), b, LocalDate.of(2026, 5, 3), new BigDecimal("200.00"),
                Transaction.Type.INCOME, "UPI-NEHA JAIN-neha@okbank-IFSC0000001-222222222222-UPI", Instant.now());
        when(transactionRepository.findByUserIdAndAccountIdIn(eq(userId), any())).thenReturn(List.of(out, in));

        reconciliationService.reconcileForUser(userId);

        assertThat(out.isTransfer()).isFalse();
        assertThat(in.isTransfer()).isFalse();
    }

    // The debits say "payment", so the existing gate admits BOTH credits as candidates; only the
    // shared-reference bonus picks the right one. (Without "payment", the reference-only gate would
    // exclude the wrong credit on its own and this test could not catch a missing bonus.)
    @Test
    void twoSameAmountTransfersPairByTheirOwnReference() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        LocalDate d = LocalDate.of(2026, 6, 28);
        Transaction out1 = txn(UUID.randomUUID(), a, d, new BigDecimal("500.00"), Transaction.Type.EXPENSE,
                "UPI-ASHA VERMA-asha@okbank-IFSC0000001-111111111111-PAYMENT FROM PHONE", Instant.now());
        Transaction out2 = txn(UUID.randomUUID(), a, d, new BigDecimal("500.00"), Transaction.Type.EXPENSE,
                "UPI-ASHA VERMA-asha@okbank-IFSC0000001-222222222222-PAYMENT FROM PHONE", Instant.now());
        Transaction in2 = txn(UUID.randomUUID(), b, d, new BigDecimal("500.00"), Transaction.Type.INCOME,
                "UPIAB/222222222222/CR/ASHA /BANK/asha@okbank", Instant.now());
        Transaction in1 = txn(UUID.randomUUID(), b, d, new BigDecimal("500.00"), Transaction.Type.INCOME,
                "UPIAB/111111111111/CR/ASHA /BANK/asha@okbank", Instant.now());
        when(transactionRepository.findByUserIdAndAccountIdIn(eq(userId), any())).thenReturn(List.of(out1, out2, in2, in1));

        reconciliationService.reconcileForUser(userId);

        assertThat(in1.getTransferPairId()).isEqualTo(out1.getId());
        assertThat(in2.getTransferPairId()).isEqualTo(out2.getId());
    }

    @Test
    void aReversalOnTheSameAccountIsNeverATransfer() {
        UUID a = UUID.randomUUID();
        Transaction failed = txn(UUID.randomUUID(), a, LocalDate.of(2026, 6, 1), new BigDecimal("300.00"),
                Transaction.Type.EXPENSE, "UPI/DR/111111111111/SHOPCO/BANK/shop@okbank/", Instant.now());
        Transaction reversal = txn(UUID.randomUUID(), a, LocalDate.of(2026, 6, 1), new BigDecimal("300.00"),
                Transaction.Type.INCOME, "UPI/REV/111111111111/SHOPCO/BANK/", Instant.now());
        when(transactionRepository.findByUserIdAndAccountIdIn(eq(userId), any())).thenReturn(List.of(failed, reversal));

        reconciliationService.reconcileForUser(userId);

        assertThat(failed.isTransfer()).isFalse();
        assertThat(reversal.isTransfer()).isFalse();
    }

    @Test
    void reconcileForUser_aSharedReferenceNeverPairsACardBillWithAnotherCard() {
        UUID card = UUID.randomUUID();
        UUID otherCard = UUID.randomUUID();
        Transaction received = txn(UUID.randomUUID(), card, LocalDate.of(2026, 6, 30), new BigDecimal("1582.00"),
                Transaction.Type.INCOME, "BBPS PMT 111111111111", Instant.now());
        Transaction purchase = txn(UUID.randomUUID(), otherCard, LocalDate.of(2026, 6, 30), new BigDecimal("1582.00"),
                Transaction.Type.EXPENSE, "UPI MERCHANTCO 111111111111", Instant.now());
        typed(card, Account.Type.CREDIT_CARD);
        typed(otherCard, Account.Type.CREDIT_CARD);
        when(transactionRepository.findByUserIdAndAccountIdIn(eq(userId), any())).thenReturn(List.of(received, purchase));

        reconciliationService.reconcileForUser(userId);

        assertThat(received.isTransfer()).isFalse();
    }
```

- [ ] **Step 2: Run to verify the new tests fail**

Run: `cd /Users/sid/Downloads/finora-own-transfers/backend && ./mvnw -q test -Dtest='ReconciliationServiceTest'`
Expected: `reconcileForUser_pairsOwnAccountLegsThatShareAReference_withNoPaymentWord`, `reconcileForUser_pairsAKotakGluedReferenceWithTheCreditOneDayLater` and `twoSameAmountTransfersPairByTheirOwnReference` FAIL (not paired). The three negative tests already pass; they guard the change.

- [ ] **Step 3: Implement** the six changes listed under **Change** above.

- [ ] **Step 4: Run to verify**

Run: `cd /Users/sid/Downloads/finora-own-transfers/backend && ./mvnw -q test -Dtest='ReconciliationServiceTest,OwnAccountEvidenceTest'`
Expected: pass. Then mutation-check: set the `+ 100` bonus to `+ 0`, rerun — `twoSameAmountTransfersPairByTheirOwnReference` must fail; restore.

- [ ] **Step 5: Commit**

```bash
cd /Users/sid/Downloads/finora-own-transfers
git add backend/src/main/java/com/finora/service/ReconciliationService.java backend/src/test/java/com/finora/service/ReconciliationServiceTest.java
git commit -m "feat(transactions): pair own-account legs that print the same UPI or IMPS reference"
```

---

### Task 4: Rule 2 — the user is the sender or payee

**Files:**
- Modify: `backend/src/main/java/com/finora/service/ReconciliationService.java`
- Test: `backend/src/test/java/com/finora/service/ReconciliationServiceTest.java`

**Interfaces:**
- Consumes: `OwnAccountEvidence.counterpartySlot`, `ownerNames`, `namesOwner`; `ReconciliationExplanation.ownAccountByName`, `OWN_ACCOUNT_NAME_RULE`.
- Produces: one-sided transfers (`isTransfer=true`, `transferPairId=null`, status TRANSFER, explanation rule `OWN_ACCOUNT_NAME`); audit detail `ownAccountTransfersFound`.

**Change:**
1. **Holder names.** In the loop that builds `accountTypes`, also collect `account.getAccountHolderName()` (non-blank) into a list; after it, `List<List<String>> ownerNames = OwnAccountEvidence.ownerNames(holderNames);` and keep a `Map<List<String>, String> ownerDisplay` (normalised words → the holder name as stored) for the explanation.
2. **One-sided rows stay pairing candidates.** Add

```java
    /** A one-sided own-account transfer from rule 2: still looking for its other leg. */
    private static boolean isOneSidedOwnAccountTransfer(Transaction t) {
        if (!t.isTransfer() || t.getTransferPairId() != null) return false;
        Map<String, Object> e = t.getReconciliationExplanation();
        Object reason = e == null ? null : e.get("reason");
        return reason instanceof Map<?, ?> m && ReconciliationExplanation.OWN_ACCOUNT_NAME_RULE.equals(m.get("rule"));
    }
```

   Change the `candidates` filter to `t.getIsDuplicateOf() == null && (!t.isTransfer() || isOneSidedOwnAccountTransfer(t))`, the outer-loop skip to `if (a.isTransfer() && !isOneSidedOwnAccountTransfer(a)) continue;`, and the inner skip to `... || (b.isTransfer() && !isOneSidedOwnAccountTransfer(b))) continue;`. A one-sided row that gets paired has its explanation overwritten by the pair's, which is correct.
   The investment pass (2b) and refund pass (3) already skip `t.isTransfer()` rows; confirm by reading them, change nothing there.
3. **Pass 2a**, placed right after the transfer pass loop and before `// 2b)`:

```java
        // 2a) One-sided own-account transfers (Plan 3, rule 2): the sender (credit) or payee
        // (debit) slot names the user, read from the holder names on their accounts. The other
        // account may never be imported, so there is no partner row. See OwnAccountEvidence for
        // why only the slot is read -- a salary NEFT names the employee as beneficiary.
        int newOwnAccountTransfers = 0;
        if (!ownerNames.isEmpty()) {
            for (Transaction t : candidates) {
                if (t.isTransfer() || t.getReconciliationStatus() != Transaction.ReconciliationStatus.OK) continue;
                if (t.getTransferRejectedAt() != null) continue;
                if (isCard(t, accountTypes)) continue;
                if (t.getCounterpartyType() == com.finora.util.CounterpartyType.BUSINESS) continue;
                if (looksLikeSalary.getOrDefault(t.getId(), false)) continue;
                Optional<String> slot = OwnAccountEvidence.counterpartySlot(t.getDescription());
                if (slot.isEmpty()) continue;
                List<String> matched = null;
                for (List<String> owner : ownerNames) {
                    if (OwnAccountEvidence.namesOwner(slot.get(), List.of(owner))) { matched = owner; break; }
                }
                if (matched == null) continue;
                t.setTransfer(true);
                t.setTransferPairId(null);
                t.setReconciliationStatus(Transaction.ReconciliationStatus.TRANSFER);
                t.setReconciliationExplanation(ReconciliationExplanation.ownAccountByName(t, slot.get(), ownerDisplay.get(matched)));
                dirty.add(t);
                newOwnAccountTransfers++;
            }
        }
```

   Declare `newOwnAccountTransfers` with the other counters at the top of the method instead if the audit block is outside this scope (it is: add `newOwnAccountTransfers = 0` to the `int newDuplicates = 0, ...` declaration and drop the local `int`).
4. **Audit:** after `details.put("transfersMatched", newTransfers);` add `details.put("ownAccountTransfersFound", newOwnAccountTransfers);`.
5. Imports: `com.finora.util.OwnAccountEvidence`, `java.util.Optional` if not present.

**Test helper** (add next to `typed(...)`):

```java
    private void holder(UUID accountId, String holderName) {
        liveAccounts.stream().filter(a -> a.getId().equals(accountId)).forEach(a -> a.setAccountHolderName(holderName));
    }
```

- [ ] **Step 1: Write the failing tests**

```java
    // --- Plan 3, rule 2: the user is the sender or payee ---

    private Transaction ownRow(UUID account, String amount, Transaction.Type type, String description) {
        return txn(UUID.randomUUID(), account, LocalDate.of(2026, 7, 1), new BigDecimal(amount), type, description, Instant.now());
    }

    @Test
    void reconcileForUser_aCreditSentByTheUserIsAOneSidedTransfer() {
        UUID savings = UUID.randomUUID();
        Transaction in = ownRow(savings, "10000.00", Transaction.Type.INCOME,
                "UPI/CR/C111111111111/ ASHA VER/ ptye/x@ptyes/NA/");
        holder(savings, "MRS ASHA VERMA");
        when(transactionRepository.findByUserIdAndAccountIdIn(eq(userId), any())).thenReturn(List.of(in));

        reconciliationService.reconcileForUser(userId);

        assertThat(in.isTransfer()).isTrue();
        assertThat(in.getTransferPairId()).isNull();
        assertThat(in.getReconciliationStatus()).isEqualTo(Transaction.ReconciliationStatus.TRANSFER);
        @SuppressWarnings("unchecked")
        Map<String, Object> reason = (Map<String, Object>) in.getReconciliationExplanation().get("reason");
        assertThat(reason).containsEntry("rule", "OWN_ACCOUNT_NAME").containsEntry("direction", "SENDER")
                .containsEntry("holderName", "MRS ASHA VERMA");
    }

    @Test
    void reconcileForUser_aDebitPaidToTheUserIsAOneSidedTransfer() {
        UUID savings = UUID.randomUUID();
        Transaction out = ownRow(savings, "8000.00", Transaction.Type.EXPENSE,
                "UPI-ASHA VERMA-asha@oksbi-IFSC0000001-111111111111-PAYMENT FROM PHONE");
        holder(savings, "ASHA VERMA");
        when(transactionRepository.findByUserIdAndAccountIdIn(eq(userId), any())).thenReturn(List.of(out));

        reconciliationService.reconcileForUser(userId);

        assertThat(out.isTransfer()).isTrue();
        @SuppressWarnings("unchecked")
        Map<String, Object> reason = (Map<String, Object>) out.getReconciliationExplanation().get("reason");
        assertThat(reason).containsEntry("direction", "PAYEE");
    }

    @Test
    void reconcileForUser_salaryNamingTheUserAsBeneficiaryStaysIncome() {
        UUID savings = UUID.randomUUID();
        Transaction salary = ownRow(savings, "96000.00", Transaction.Type.INCOME,
                "NEFT CR-IFSC0000001-EMPLOYERCO PVT LTD-ASHA VERMA-REF1 SALARY FOR JUN 2026");
        holder(savings, "ASHA VERMA");
        when(transactionRepository.findByUserIdAndAccountIdIn(eq(userId), any())).thenReturn(List.of(salary));

        reconciliationService.reconcileForUser(userId);

        assertThat(salary.isTransfer()).isFalse();
    }

    @Test
    void reconcileForUser_theOwnerInTheNeftRemitterSlotIsAOneSidedTransfer() {
        UUID savings = UUID.randomUUID();
        Transaction in = ownRow(savings, "40000.00", Transaction.Type.INCOME,
                "NEFT CR-IFSC0000001-ASHA VERMA S O SH R VERMA-ASHA VERMA-REF1");
        holder(savings, "ASHA VERMA");
        when(transactionRepository.findByUserIdAndAccountIdIn(eq(userId), any())).thenReturn(List.of(in));

        reconciliationService.reconcileForUser(userId);

        assertThat(in.isTransfer()).isTrue();
    }

    @Test
    void reconcileForUser_rule2SkipsCardsBusinessesOtherPeopleAndRejectedRows() {
        UUID savings = UUID.randomUUID();
        UUID card = UUID.randomUUID();
        Transaction onCard = ownRow(card, "500.00", Transaction.Type.INCOME, "UPI-ASHA VERMA-asha@okbank-IFSC0000001-111111111111-UPI");
        Transaction business = ownRow(savings, "700.00", Transaction.Type.INCOME, "UPI-ASHA VERMA-asha@okbank-IFSC0000001-222222222222-UPI");
        business.setCounterpartyType(com.finora.util.CounterpartyType.BUSINESS);
        Transaction otherAsha = ownRow(savings, "764.00", Transaction.Type.INCOME, "UPI-ASHA PATEL-ashap@okbank-IFSC0000001-111111111111-UPI");
        Transaction rejected = ownRow(savings, "900.00", Transaction.Type.EXPENSE, "UPI-ASHA VERMA-asha@okbank-IFSC0000001-222222222222-UPI");
        rejected.setTransferRejectedAt(Instant.now());
        Transaction freeText = ownRow(savings, "49.40", Transaction.Type.EXPENSE, "IGST DB @ 18.00% TRANSACTIONS FOR ASHA VERMA");
        holder(savings, "ASHA VERMA");
        holder(card, "ASHA VERMA");
        typed(card, Account.Type.CREDIT_CARD);
        when(transactionRepository.findByUserIdAndAccountIdIn(eq(userId), any()))
                .thenReturn(List.of(onCard, business, otherAsha, rejected, freeText));

        reconciliationService.reconcileForUser(userId);

        assertThat(List.of(onCard, business, otherAsha, rejected, freeText)).noneMatch(Transaction::isTransfer);
    }

    @Test
    void noHolderNameMeansNoOneSidedTransfer() {
        UUID savings = UUID.randomUUID();
        Transaction in = ownRow(savings, "10000.00", Transaction.Type.INCOME, "UPI/CR/C111111111111/ ASHA VER/ ptye/x@ptyes/NA/");
        when(transactionRepository.findByUserIdAndAccountIdIn(eq(userId), any())).thenReturn(List.of(in));

        reconciliationService.reconcileForUser(userId);

        assertThat(in.isTransfer()).isFalse();
    }

    @Test
    void reconcileForUser_aOneSidedTransferPairsWhenItsOtherLegArrives() {
        UUID paytm = UUID.randomUUID();
        UUID pnb = UUID.randomUUID();
        Transaction in = ownRow(paytm, "10000.00", Transaction.Type.INCOME, "UPI/CR/C111111111111/ ASHA VER/ ptye/x@ptyes/NA/");
        holder(paytm, "ASHA VERMA");
        when(transactionRepository.findByUserIdAndAccountIdIn(eq(userId), any())).thenReturn(List.of(in));
        reconciliationService.reconcileForUser(userId);
        assertThat(in.isTransfer()).isTrue();

        // The other statement is imported: its leg names only a truncated first name.
        Transaction out = ownRow(pnb, "10000.00", Transaction.Type.EXPENSE, "UPI/DR/111111111111/Ashaverm/BANK/x/");
        when(transactionRepository.findByUserIdAndAccountIdIn(eq(userId), any())).thenReturn(List.of(in, out));
        reconciliationService.reconcileForUser(userId);

        assertThat(in.getTransferPairId()).isEqualTo(out.getId());
        assertThat(out.getTransferPairId()).isEqualTo(in.getId());
    }

    @Test
    void secondRunChangesNothing() {
        UUID savings = UUID.randomUUID();
        Transaction in = ownRow(savings, "10000.00", Transaction.Type.INCOME, "UPI/CR/C111111111111/ ASHA VER/ ptye/x@ptyes/NA/");
        holder(savings, "ASHA VERMA");
        when(transactionRepository.findByUserIdAndAccountIdIn(eq(userId), any())).thenReturn(List.of(in));
        reconciliationService.reconcileForUser(userId);
        Map<String, Object> first = in.getReconciliationExplanation();

        reconciliationService.reconcileForUser(userId);

        assertThat(in.isTransfer()).isTrue();
        assertThat(in.getTransferPairId()).isNull();
        assertThat(in.getReconciliationExplanation()).isEqualTo(first);
    }

    @Test
    void undoneOneSidedTransferStaysUndone() {
        UUID savings = UUID.randomUUID();
        Transaction in = ownRow(savings, "10000.00", Transaction.Type.INCOME, "UPI/CR/C111111111111/ ASHA VER/ ptye/x@ptyes/NA/");
        holder(savings, "ASHA VERMA");
        // What TransactionService.unmarkTransfer leaves behind.
        in.setTransferRejectedAt(Instant.now());
        when(transactionRepository.findByUserIdAndAccountIdIn(eq(userId), any())).thenReturn(List.of(in));

        reconciliationService.reconcileForUser(userId);

        assertThat(in.isTransfer()).isFalse();
    }
```

- [ ] **Step 2: Run to verify they fail**

Run: `cd /Users/sid/Downloads/finora-own-transfers/backend && ./mvnw -q test -Dtest='ReconciliationServiceTest'`
Expected: the positive tests (`aCreditSentByTheUser…`, `aDebitPaidToTheUser…`, `theOwnerInTheNeftRemitterSlot…`, `aOneSidedTransferPairsWhenItsOtherLegArrives`, `secondRunChangesNothing`) FAIL; the negative ones pass.

- [ ] **Step 3: Implement** the five changes listed under **Change**.

- [ ] **Step 4: Run to verify**

Run: `cd /Users/sid/Downloads/finora-own-transfers/backend && ./mvnw -q test -Dtest='ReconciliationServiceTest,OwnAccountEvidenceTest,TransactionExplanationServiceTest'`
Expected: pass. Mutation-checks, one at a time, each restored after:
- remove the `isCard` skip → `rule2SkipsCards…` fails;
- remove `isOneSidedOwnAccountTransfer` from the candidates filter → `aOneSidedTransferPairsWhenItsOtherLegArrives` fails.

- [ ] **Step 5: Commit**

```bash
cd /Users/sid/Downloads/finora-own-transfers
git add backend/src/main/java/com/finora/service/ReconciliationService.java backend/src/test/java/com/finora/service/ReconciliationServiceTest.java
git commit -m "feat(transactions): a payment the user sent to or received from themselves is a transfer"
```

---

### Task 5: Measure on the real corpus, per owner

**Files:**
- Uncommitted probe: `backend/src/test/java/com/finora/imports/analysis/TmpOwnTransferProbeTest.java` (already exists in the worktree from the design phase; never `git add` it). Delete it at the end of this task.

The probe must, for each owner (statements grouped by normalised holder name, as the design-phase probe did):
1. Build one `Account` per statement section with `accountType` and `accountHolderName` set, and one `Transaction` per staged row (source `CSV_IMPORT`, counterparty from `CounterpartyClassifier.classify`).
2. Run the real `ReconciliationService.reconcileForUser` (mocked repositories, as `ReconciliationServiceTest` does) once on the code from `origin/main` behaviour (run the probe on a checkout of `origin/main` first, saving output to the scratchpad) and once on this branch.
3. Print every row whose `isTransfer`/`transferPairId`/status differs, with owner, file, date, amount, direction, rule and narration; then per owner the income, spending and unresolved deltas using `FlowTotals.countsAsIncome`, `RefundNetting` spend rules and `FlowTotals.isUnresolvedInflow`.

- [ ] **Step 1:** Run the probe on `origin/main` (temporary `git stash`-free approach: `git worktree add ../finora-p3-base origin/main`, copy the probe in, run, remove that worktree). Save to `$SCRATCH/p3-before.txt`.
- [ ] **Step 2:** Run it on this branch. Save to `$SCRATCH/p3-after.txt`.
- [ ] **Step 3:** Read every changed row. For each, state in the scratchpad notes whether it is a genuine own transfer. Any row that is not blocks the PR: find the mechanism (print the slot and the match), fix the rule with a test first, and rerun from Step 2.
- [ ] **Step 4:** Confirm: no salary row changed; income fell only by genuine own transfers; spending fell by own transfers out; unresolved fell by own transfers in. Record the per-owner numbers for the PR body.
- [ ] **Step 5:** Delete the probe and the base worktree:

```bash
rm /Users/sid/Downloads/finora-own-transfers/backend/src/test/java/com/finora/imports/analysis/TmpOwnTransferProbeTest.java
cd /Users/sid/Downloads/finora && git worktree remove ../finora-p3-base
cd /Users/sid/Downloads/finora-own-transfers && git status --short   # expect clean
```

---

### Task 6: Full verification and PR

- [ ] **Step 1:** `cd /Users/sid/Downloads/finora-own-transfers/backend && ./mvnw verify` — read the `Tests run:` lines and `BUILD SUCCESS` from the log, not the exit code of a pipe.
- [ ] **Step 2:** Self-review the full diff (`git diff origin/main...HEAD`) for: unused imports, the NEFT beneficiary never read, rule 2 exclusions all present, comments that quote no real narration or value.
- [ ] **Step 3:** Merge-state check and push:

```bash
cd /Users/sid/Downloads/finora-own-transfers && git fetch origin && git log --oneline HEAD..origin/main
git push -u origin feature/own-transfers
```

If `origin/main` moved, merge it, rerun Step 1, then push.
- [ ] **Step 4:** `gh pr create` with a body covering: the two rules, the evidence (spec link), the per-owner corpus numbers from Task 5, every mutation check run, what is out of scope (family tagging → Plan 2). No real names or narrations in the body.
