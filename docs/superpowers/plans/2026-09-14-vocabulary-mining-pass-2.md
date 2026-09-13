# Vocabulary Mining Pass 2 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add real, multi-document-verified vocabulary misses found by mining the *current* residual "Other" bucket (post-#987/#989/#1082, 971 of 1,869 real corpus rows as of the 2026-09-14 re-baseline) — the same lever the last two vocabulary passes used, re-run against a bucket that has already shrunk and changed composition since either of them looked at it.

**Architecture:** No architecture change. Pure appends to the existing `CategoryRules.RULES` static map, picked up automatically by the existing word-boundary `RULE_PATTERNS` compilation. No new category, no Flyway migration.

**Tech Stack:** Java 21, JUnit 5, AssertJ (`backend/src/test/java/com/finora/util/CategoryRulesTest.java`).

**Spec:** `docs/superpowers/specs/2026-09-01-transaction-categorization-design.md` — §8 item #3. This plan's evidence is fresh, not from that spec's original sample: a temporary, uncommitted probe (same discipline as the 2026-09-05 and 2026-09-14 re-baseline probes — real `PdfPreviewGenerator` pipeline over all 29 real corpus PDFs, current V19/`CategoryRules`/`PersonToPersonTransferDetector` waterfall, deleted after use) extracted every row still landing in "Other" today, reduced each to `CategoryRules.extractMerchant()`'s normalized token (already strips numeric reference codes — the same reduction `gokhana`/`tobox` were originally found through), and kept only tokens appearing in **2 or more distinct documents** — this project's own established bar against overfitting to one payer's own frequent vendor (see `gokhana`'s history). Nothing from that probe run was printed or persisted beyond token/count/doc-count aggregates; no raw narration left the process.

## Global Constraints

- **Word-boundary matching only**, via the existing `WORD_BOUNDARY + Pattern.quote(w) + WORD_BOUNDARY` compilation.
- **Append, never interleave.** New keywords go at the end of their category's existing list.
- **Collision-checked before writing.** Every keyword below was checked against the full current ~100-entry table (independently, by running the actual regex logic — not by inspection) before being included in this plan.
- **No new category, no Flyway migration.**
- **No PII in code, comments, or tests.** Every keyword below is a public business/brand name (a restaurant, a shop, a logistics company, a payroll platform) surfaced by `extractMerchant()`'s already-established reduction — not a raw narration, account number, or personal name.
- **A keyword only ships with a category if the category fit is either a direct public fact (the business's actual line of trade) or an explicit, disclosed inference — never a silent guess.** Task 2 below is flagged for exactly this reason; findings this plan declines to act on (§ below) are declined for the same reason.

---

### Task 1: Add five verified, unambiguous vocabulary misses

**Files:**
- Modify: `backend/src/main/java/com/finora/util/CategoryRules.java:59` (Dining), `:74` (Shopping)
- Test: `backend/src/test/java/com/finora/util/CategoryRulesTest.java`

**Interfaces:**
- Consumes/produces nothing new — pure data addition to the existing `CategoryRules.RULES` map.

**Context:** Five real misses from the 2026-09-14 mining pass, each with a category fit that follows directly from the business's own name/public identity:
- `Chinese Factory`, `Cream House`, `Lassi Wassi` — real food-and-drink establishment names (a Chinese restaurant, an ice-cream/dessert parlor, a lassi/beverage shop), 3 distinct documents each. Map to **Dining**, alongside the existing `swiggy`/`zomato`/`cinnabon`/`gokhana`/`tobox` keywords.
- `Global Fashion` — a real clothing/apparel retailer name, 3 distinct documents. Maps to **Shopping**, alongside `nykaa`/`myntra`/`ajio`.
- `Ekart` — Flipkart's own logistics/delivery arm, a real, publicly identifiable business (already flagged as a candidate in the 2026-09-05 plan's Task 1 and explicitly deferred pending its own follow-up — this is that follow-up). 2 distinct documents. Maps to **Shopping**, the same category `flipkart` itself is in.

All five are safe as word-boundary-bounded phrase/word additions: none is a substring of, or contains as a substring, any of the ~100 keywords already in `CategoryRules.RULES` (checked by running the actual matching regex, not by inspection).

- [ ] **Step 1: Write the failing tests**

Add to `backend/src/test/java/com/finora/util/CategoryRulesTest.java`, before the final closing brace:

```java
    /**
     * Real corpus finding (2026-09-14 mining pass against the current residual "Other" bucket):
     * "Chinese Factory" is a real Chinese-food restaurant name, appearing across 3 distinct
     * corpus documents.
     */
    @Test
    void suggestCategory_matchesChineseFactory_realRestaurant() {
        assertThat(CategoryRules.suggestCategory("UPI-THE CHINESE FACTORY-REF991021")).isEqualTo("Dining");
    }

    /**
     * Real corpus finding: "Cream House" is a real ice-cream/dessert parlor name, across 3
     * distinct documents.
     */
    @Test
    void suggestCategory_matchesCreamHouse_realDessertParlor() {
        assertThat(CategoryRules.suggestCategory("UPI-CREAM HOUSE-REF991022")).isEqualTo("Dining");
    }

    /**
     * Real corpus finding: "Lassi Wassi" is a real lassi/beverage shop name, across 3 distinct
     * documents.
     */
    @Test
    void suggestCategory_matchesLassiWassi_realBeverageShop() {
        assertThat(CategoryRules.suggestCategory("UPI-LASSI WASSI-PAYTM-REF991023")).isEqualTo("Dining");
    }

    /**
     * Real corpus finding: "Global Fashion" is a real clothing/apparel retailer name, across 3
     * distinct documents.
     */
    @Test
    void suggestCategory_matchesGlobalFashion_realApparelRetailer() {
        assertThat(CategoryRules.suggestCategory("UPI-GLOBAL FASHION OFFERS-REF991024")).isEqualTo("Shopping");
    }

    /**
     * Real corpus finding: "Ekart" is Flipkart's own logistics/delivery arm -- flagged as a
     * candidate in the 2026-09-05 categorization-vocabulary-expansion plan's Task 1 and
     * explicitly deferred pending its own follow-up (this task).
     */
    @Test
    void suggestCategory_matchesEkart_flipkartLogisticsArm() {
        assertThat(CategoryRules.suggestCategory("UPI-EKART-EKART@YBL-REF991025")).isEqualTo("Shopping");
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd backend && ./mvnw test -Dtest=CategoryRulesTest`
Expected: all five new tests FAIL, each returning `"Other"`. All pre-existing tests in the file still PASS.

- [ ] **Step 3: Add the five keywords**

In `backend/src/main/java/com/finora/util/CategoryRules.java`, replace:

```java
        RULES.put("Dining", List.of("swiggy", "zomato", "restaurant", "cafe", "starbucks", "dominos", "mcdonald", "kfc", "cinnabon", "gokhana", "tobox"));
```

with:

```java
        // "chinese factory", "cream house", and "lassi wassi" (real restaurant/dessert-parlor/
        // beverage-shop names) added after mining this project's own real bank-statement corpus's
        // current residual "Other" bucket (2026-09-14 pass, docs/superpowers/plans/2026-09-14-
        // vocabulary-mining-pass-2.md) -- each appears across 3 distinct documents, the same
        // multi-payer bar "gokhana" was held to. Safe as bare phrases: none is a substring of, or
        // contains, any other keyword in this table.
        RULES.put("Dining", List.of("swiggy", "zomato", "restaurant", "cafe", "starbucks", "dominos", "mcdonald", "kfc", "cinnabon", "gokhana", "tobox", "chinese factory", "cream house", "lassi wassi"));
```

Replace:

```java
        RULES.put("Shopping", List.of("amazon", "flipkart", "myntra", "ajio", "nykaa", "decathlon", "asspl", "pureplay"));
```

with:

```java
        // "global fashion" (a real clothing/apparel retailer, 3 distinct documents) and "ekart"
        // (Flipkart's own logistics/delivery arm -- flagged as a candidate in the 2026-09-05
        // categorization-vocabulary-expansion plan's Task 1 and deferred pending this follow-up)
        // added after mining this project's own real bank-statement corpus's current residual
        // "Other" bucket (2026-09-14 pass, docs/superpowers/plans/2026-09-14-vocabulary-mining-
        // pass-2.md). Safe as bare words/phrases: neither collides with any other keyword here.
        RULES.put("Shopping", List.of("amazon", "flipkart", "myntra", "ajio", "nykaa", "decathlon", "asspl", "pureplay", "global fashion", "ekart"));
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd backend && ./mvnw test -Dtest=CategoryRulesTest`
Expected: PASS — all five new tests, and every pre-existing test in this file.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/finora/util/CategoryRules.java backend/src/test/java/com/finora/util/CategoryRulesTest.java
git commit -m "feat(transactions): add chinese factory, cream house, lassi wassi, global fashion, ekart keywords"
```

---

### Task 2: Add "kronos" — flagged for category confirmation

**Files:**
- Modify: `backend/src/main/java/com/finora/util/CategoryRules.java:32` (Salary)
- Test: `backend/src/test/java/com/finora/util/CategoryRulesTest.java`

**Interfaces:**
- Consumes/produces nothing new — same as Task 1.

**Context:** The mining pass surfaced `NEFT CR <foreign-bank-IFSC-prefix> KRONOS`-shaped narrations, 6 rows across 2 distinct documents — an NEFT **credit** (money received) referencing "Kronos" and a foreign (non-Indian) bank's IFSC prefix. Kronos (now part of UKG) is a real, well-known workforce-management/payroll platform used by employers to run payroll — the most plausible read of a credit narrating it is a salary deposit from an employer using Kronos for payroll, likely a foreign employer given the IFSC prefix. (The specific real bank prefix is not reproduced here — see this project's own Synthetic Fixture Policy on describing rather than quoting real evidence.)

This is split into its own task, separate from Task 1, because the category fit rests on an inference chain (Kronos-as-payroll-platform → this specific credit is salary) rather than a direct fact about what the business itself sells (a restaurant named "Cream House" is Dining on its face; this is not that direct). Confirm this mapping before running Step 3 — this task is written assuming Salary is confirmed.

- [ ] **Step 1: Write the failing test**

Add to `backend/src/test/java/com/finora/util/CategoryRulesTest.java`, before the final closing brace:

```java
    /**
     * Real corpus finding (2026-09-14 mining pass): "Kronos" (now part of UKG) is a real
     * workforce-management/payroll platform; narrations referencing it appear as NEFT CREDITS
     * (money received) across 2 distinct documents. Mapped to Salary on the inference that a
     * credit naming a payroll platform is a salary deposit; confirm this category before relying
     * on it (see Task 2's Context).
     */
    @Test
    void suggestCategory_matchesKronos_payrollPlatformCredit() {
        assertThat(CategoryRules.suggestCategory("NEFT CR HDFC0XXXXXX KRONOS REF991026")).isEqualTo("Salary");
    }
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=CategoryRulesTest`
Expected: FAILS, returning `"Other"`.

- [ ] **Step 3: Add the keyword**

In `backend/src/main/java/com/finora/util/CategoryRules.java`, replace:

```java
        RULES.put("Salary", List.of("salary", "payroll", "income tax refund", "stipend"));
```

with:

```java
        // "kronos" (a real workforce-management/payroll platform, now part of UKG) added after
        // mining this project's own real bank-statement corpus's current residual "Other" bucket
        // (2026-09-14 pass, docs/superpowers/plans/2026-09-14-vocabulary-mining-pass-2.md) --
        // narrations referencing it appear as NEFT credits across 2 distinct documents. Mapped to
        // Salary on the assumption a credit naming a payroll platform is a salary deposit (see
        // CategoryRulesTest's Kronos test comment for the reasoning and its caveat). Safe as a
        // bare keyword: a distinctive proper noun, not a substring of any other keyword here.
        RULES.put("Salary", List.of("salary", "payroll", "income tax refund", "stipend", "kronos"));
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd backend && ./mvnw test -Dtest=CategoryRulesTest`
Expected: PASS — the new test, and every pre-existing test in this file.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/finora/util/CategoryRules.java backend/src/test/java/com/finora/util/CategoryRulesTest.java
git commit -m "feat(transactions): add kronos keyword, a payroll-platform credit signal"
```

---

## Findings not acted on in this plan

The same mining pass surfaced several other real, multi-document patterns. None of these is a simple keyword addition — each needs either a product decision or a different (non-keyword-table) fix, so none is guessed at here:

- **Generic business names with no identifiable trade** — `Nathani Enterprises` (24 rows across 3 documents, the single highest-frequency finding this pass), `Shree Balaji Traders`, `Roshni Services`. All are real, recurring businesses, but "Enterprises"/"Traders"/"Services" name patterns give no signal about what they actually sell — a category guess here would be exactly the "confident wrong answer" the design spec's own §14 rates as worse than "Other."
- **`City Realty`** — same ambiguity class as `housingcom` from the last plan: a real estate business, but which of its products (brokerage fee? rent facilitation? purchase installment?) this narration represents isn't established by the narration alone.
- **`Jasmines Beauty Care`** — a real beauty/personal-care business; Health vs. Shopping is a genuine judgment call this plan declines to make without a steer.
- **An apparent income-taxonomy gap, not a vocabulary gap:** `interest paid till <date>`-shaped rows (5 rows across 4 documents) look like recurring savings-account interest *credited* to the user, but the only interest-related category today (`Fees/Interest`) is scoped to interest/fees *charged*, the opposite direction. Reusing it would misrepresent income as an expense category; this needs a product decision (a new category, or an explicit "interest earned" mapping), not a keyword.
- **Payment-gateway artifacts, not merchants:** `PhonePe`/`Razorpay`-prefixed narrations (`upi phonepe phonepemerchant yesbank`, `upi razorpay pg razorpay`) name the payment rail, not the actual merchant behind it — the same structural gap `CategoryRules.java`'s own comments already describe for gateway-fused tokens. A keyword can't fix this; it needs merchant resolution behind the gateway, which is a different (and larger) mechanism.
- **A possible `PersonToPersonTransferDetector` gap, worth its own investigation:** several tokens this pass found are plainly full person names (`Madhura Shrihari`, `Karim Chand Badsha`, `Sachin Rajendra Kamb...`, and others), appearing across multiple documents, still landing in "Other" rather than "Personal Transfer." That's the detector's job, not this table's — flagging it here because it surfaced from the same mining pass, but it belongs in a P2P-detector investigation, not a `CategoryRules` plan.

## Self-Review

**1. Spec coverage.** This plan is itself the "next mining pass" the 2026-09-05 and 2026-09-14 (re-baseline) work both pointed at. It covers the 6 concrete, category-clear findings from that pass and explicitly documents (without guessing at) the 6 findings that need a decision or a different mechanism first.

**2. Placeholder scan.** No "TBD"/deferred content in either task. Both have complete code for every step.

**3. Type consistency.** Both tasks touch only `CategoryRules.RULES` and `CategoryRulesTest` — no new types or signatures.

**Assumption flagged for the executor:** Task 2's Salary mapping for `kronos` is a disclosed inference (payroll-platform credit → salary), not a confirmed fact — call it out explicitly in review, the same way Task 3's `housingcom` mapping was flagged in the prior plan.

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-09-14-vocabulary-mining-pass-2.md`. Two execution options:

**1. Subagent-Driven (recommended)** - I dispatch a fresh subagent per task, review between tasks, fast iteration

**2. Inline Execution** - Execute tasks in this session using executing-plans, batch execution with checkpoints

Which approach?
