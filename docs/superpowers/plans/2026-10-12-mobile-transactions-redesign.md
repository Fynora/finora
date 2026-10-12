# Mobile Transactions Screen Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task, inline, in one session. The owner's standing rule for this repository is no sub-agents for implementation or verification, so superpowers:subagent-driven-development is not used and the final review is a self-review. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rebuild the look of the mobile Transactions tab (title area, month card, search, filters, day cards) to match the redesigned dashboard, without changing what the screen fetches or does.

**Architecture:** Pure row logic moves into `mobile/src/lib/ledgerRows.ts` (title, tags, spoken name, day pieces, page merge). Presentation moves into five small components under `mobile/src/components/ledger/`. `LedgerScreen.tsx` keeps its state, queries and handlers and renders the new components. The list item becomes a piece of a day (at most 25 rows) instead of a single row.

**Tech Stack:** Expo SDK 57, React Native 0.86.3, TypeScript 6, TanStack Query 5, Reanimated 4.7, expo-glass-effect 57, Jest 30 with React Native Testing Library 13.

**Spec:** `docs/superpowers/specs/2026-10-11-mobile-transactions-redesign-design.md` (approved 2026-10-12, softened capitals).

## Global Constraints

- Work only in the worktree `/Users/sid/Downloads/finora/.claude/worktrees/mobile-dashboard-cards`, branch `feature/mobile-transactions-redesign`, with absolute paths. The primary checkout is never written to.
- Commit messages carry no `Co-Authored-By` line and no AI attribution of any kind.
- No sub-agents. Test first: every new function and component gets a failing test before its code.
- Checks run under Node 22 through `scratchpad/m22.sh` (`jest`, `typecheck`, `lint`, `export`) and `scratchpad/guards.sh`. Local Node is 26 and is not what CI uses.
- The simulator app talks to production (`https://api.fynora.net`) signed in as the owner: read only. No delete is confirmed, no category is changed, no transaction is added or edited.
- Temporary edits (dark theme, forced piece size, forced Reduce Transparency) are reverted and never committed. `git status --short` is clean of them before every commit.
- No real name, amount or narration from the owner's account or statements goes into code, tests, comments or this document. Test data is invented.
- Nothing is fetched differently: no change to `transactionsApi`, the query key `['transactions', filters]`, `DEFAULT_LEDGER_FILTERS`, `LEDGER_PAGE_SIZE`, `getLedgerNextPageParam`, or the `useInfiniteQuery` call. Pull request #2125 (the paging fix) changes exactly those lines of `LedgerScreen.tsx`, adds `LedgerPageParam` and `ledgerPageRequest`, and corrects the comment on `groupTransactionsByDay`. The redesign is built on a base that has #2125: before Task 0, `git fetch origin` and confirm `git grep -n ledgerPageRequest origin/main -- mobile/src/screens/LedgerScreen.tsx` finds it, then bring the branch up to date with `origin/main`. If #2125 is not merged yet, stop and ask the owner which goes first; do not build on a base without it and resolve the same lines twice. Task 1 moves `groupTransactionsByDay` with the corrected comment #2125 gives it.
- Exports that other files import from `LedgerScreen.tsx` keep working: `LedgerScreen`, `LEDGER_PAGE_SIZE`, `DEFAULT_LEDGER_FILTERS`, `getLedgerNextPageParam`, `statusBadges`, `groupTransactionsByDay`.
- Tokens only: colours from `useTheme()`, type from `typography`, `radius.xxl` 20, `radius.hero` 28, `spacing.ms` 12, `spacing.ml` 20, `shadows.card`. Values come from the spec's "Design reference" table.
- Expenses stay red (`dangerInk`), income green (`successInk`).
- A glass view is never faded with `opacity` on itself or a parent: Expo documents that the effect then does not draw. The pinned bar uses `glassEffectStyle={{ style, animate, animationDuration }}` instead.
- This ships in the next store build. Never over the air to the 1.0.0 runtime.

## Review Focus

Inputs the spec implies but that are easy to leave untested, most likely first. Each has a test in the task named.

1. A transaction returned on two pages, the second time under a different date (it was edited between the two requests): it is listed once, at its first position, and counted once. Task 1, `mergePagesById`.
2. A merchant made only of punctuation, or with characters that mean something in a pattern ("A&B (P) Ltd."): the title falls back cleanly and no pattern error is thrown. Task 1, `rowTitle`.
3. A description that is empty while a merchant exists, and a row with neither: titles are the merchant and "Transaction". Task 1, `rowTitle`.
4. A day with more rows than one piece holds, ending exactly on a piece boundary (25, 50): no empty piece, heading only on the first, rounded bottom and shadow only on the last. Task 1, `buildDayPieces`; Task 4 for the styles.
5. Pull to refresh (a negative scroll offset) and a scroll back to the top: the pinned bar returns to clear and its buttons stop being reachable. Task 7 and Task 8.

## File Structure

| File | Responsibility |
|---|---|
| Create `mobile/src/lib/ledgerRows.ts` | `softenCapitals`, `rowTitle`, `rowTags`, `rowSpokenLabel`, `dayHeading`, `mergePagesById`, `buildDayPieces`; `groupTransactionsByDay` moves here unchanged |
| Create `mobile/src/lib/ledgerRows.test.ts` | Tests for the above |
| Create `mobile/src/theme/ledgerPairs.test.ts` | Contrast of the text and fill pairs this screen introduces, both themes |
| Create `mobile/src/components/ledger/LedgerMonthCard.tsx` and `.test.tsx` | The month card |
| Create `mobile/src/components/ledger/LedgerDayCard.tsx` and `.test.tsx` | A day piece: heading, card, memoised rows |
| Create `mobile/src/components/ledger/LedgerControls.tsx` and `.test.tsx` | Search, type switch, Filters button, active filter chips |
| Create `mobile/src/components/ledger/LedgerFiltersSheet.tsx` and `.test.tsx` | The filters sheet; owns `STATUS_FILTERS`, `StatusFilter`, `statusFilterLabel` |
| Create `mobile/src/components/ledger/LedgerTitleBar.tsx` and `.test.tsx` | `LedgerTitleBlock` (scrolls) and `LedgerPinnedBar` (overlay) |
| Modify `mobile/src/screens/LedgerScreen.tsx` | Uses the above; inline `LedgerMonthSummary`, the chip row, the date row and the row renderer are removed |
| Modify `mobile/src/screens/LedgerScreen.test.tsx` | Fixture, moved controls, new behaviour |
| Modify `scripts/check-reporting-period-labels.py` | `LedgerMonthCard.tsx` becomes a companion of `LedgerScreen.tsx` |

Commands used throughout (S is `/private/tmp/claude-501/-Users-sid-Downloads-finora/8ef85f19-c519-4b5f-82f7-497ef63610e4/scratchpad`):

- One test file: `zsh S/m22.sh jest src/lib/ledgerRows.test.ts`
- Whole suite: `zsh S/m22.sh jest`
- Types: `zsh S/m22.sh typecheck`. Lint: `zsh S/m22.sh lint`. Android bundle: `zsh S/m22.sh export`. Guards: `zsh S/guards.sh`.

---

### Task 0: Baseline on the unchanged screen

Measured before any code changes so the "after" numbers in Task 10 have something to stand against. No files change.

- [ ] **Step 1: Start the simulator and Metro** as in the memory note `mobile-sim-and-rntl-gotchas` (iPhone 17 Pro, `m22.sh metro` on port 8083, open the dev client URL). Confirm the Transactions tab shows the owner's list.
- [ ] **Step 2: First draw.** Reload the bundle, start a 12 second Time Profiler recording attached to the app process (the direct `xctrace` recipe in the same note, section 9), tap the Transactions tab two seconds in. Read the JavaScript-thread and main-thread sample counts with `scratchpad/prof_by_thread.py`. Three runs.
- [ ] **Step 3: Scroll.** The twelve-swipe run from `scratchpad/txn_scroll_steps.py`, 22 seconds, three runs, same reading.
- [ ] **Step 4: Record** the six first-draw numbers and six scroll numbers in the SDD ledger under "Task 0". Expected: numbers exist for both threads in every run; a run whose recording failed is repeated, not dropped silently.

---

### Task 1: Row logic (`ledgerRows.ts`)

**Files:**
- Create: `mobile/src/lib/ledgerRows.ts`
- Create: `mobile/src/lib/ledgerRows.test.ts`
- Modify: `mobile/src/screens/LedgerScreen.tsx` (remove `groupTransactionsByDay`, `GroupedRow`, `SUBTOTAL_EXCLUDED_STATUSES`; re-export)

**Interfaces:**
- Produces:
  - `softenCapitals(text: string): string`
  - `rowTitle(t: Pick<Transaction, 'description' | 'merchant'>): string`
  - `type RowTagTone = 'warning' | 'danger' | 'success' | 'neutral'`; `interface RowTag { key: 'match' | 'bank-correction' | 'needs-review' | 'recurring' | 'international'; label: string; tone: RowTagTone }`
  - `rowTags(t: Transaction): RowTag[]`
  - `rowSpokenLabel(t: Transaction): string`
  - `dayHeading(date: string, today: Date): string`
  - `mergePagesById(pages: readonly { content: Transaction[] }[]): Transaction[]`
  - `DAY_PIECE_SIZE = 25`; `interface DayPiece { key: string; date: string; heading: string | null; subtotal: number; rows: Transaction[]; first: boolean; last: boolean }`
  - `buildDayPieces(txns: Transaction[], today?: Date, pieceSize?: number): DayPiece[]`
  - `groupTransactionsByDay(txns: Transaction[], today?: Date): GroupedRow[]` and `type GroupedRow` (moved, unchanged)

- [ ] **Step 1: Write the failing tests**

`mobile/src/lib/ledgerRows.test.ts`:

```ts
import {
  DAY_PIECE_SIZE, buildDayPieces, dayHeading, mergePagesById, rowSpokenLabel, rowTags, rowTitle, softenCapitals,
} from './ledgerRows';
import type { Transaction } from '../types';

function txn(over: Partial<Transaction> = {}): Transaction {
  return {
    id: 't-1', accountId: 'a-1', categoryId: 'c-1', categoryName: 'Food', date: '2026-07-14',
    description: 'Grocery run', merchant: 'grocery run', paymentMethod: 'UPI', amount: -1250, type: 'EXPENSE',
    tags: [], notes: null, reconciliationStatus: 'OK', recurring: false, needsCategoryReview: false,
    pendingBankCorrection: false, categoryManuallySet: false, counterpartyType: 'UNKNOWN',
    international: false, foreignCurrency: null, foreignAmount: null,
    ...over,
  };
}

describe('softenCapitals', () => {
  it.each([
    ['RAHUL SH', 'Rahul Sh'],
    ['IRCTC', 'Irctc'],
    ['CITY POWER DISTRIBUTION L', 'City Power Distribution L'],
    ["DOMINO'S PIZZA", "Domino's Pizza"],
    ['NORTHWIND.CLEARING-CORP', 'Northwind.Clearing-Corp'],
    ['UPI123 PAY', 'Upi123 Pay'],
  ])('rewrites all-capital words: %s', (input, expected) => {
    expect(softenCapitals(input)).toBe(expected);
  });

  it.each(['PhonePe', 'McDonald', 'Dinner with college friends', 'a', 'A', '12345', ''])(
    'leaves %j as printed', (input) => {
      expect(softenCapitals(input)).toBe(input);
    });

  it('leaves letters outside A to Z untouched', () => {
    expect(softenCapitals('किराना STORE')).toBe('किराना Store');
  });
});

describe('rowTitle', () => {
  it('uses the description when there is no merchant', () => {
    expect(rowTitle(txn({ description: 'NEFT INWARD 0042', merchant: '' }))).toBe('Neft Inward 0042');
  });

  it('says "Transaction" when there is neither', () => {
    expect(rowTitle(txn({ description: '', merchant: '' }))).toBe('Transaction');
  });

  it('uses a plain description in full when it begins with the merchant', () => {
    expect(rowTitle(txn({ description: 'Dinner with college friends', merchant: 'dinner with college friends' })))
      .toBe('Dinner with college friends');
    // The server keeps four words at most; the title is still the whole description.
    expect(rowTitle(txn({ description: 'Dinner with college friends at home', merchant: 'dinner with college friends' })))
      .toBe('Dinner with college friends at home');
  });

  it('does not treat a longer first word as a match for the merchant', () => {
    // "pay" is not the first word of "Payment to landlord"; the name is not in the description as
    // a word of its own, so the stored merchant is shown.
    expect(rowTitle(txn({ description: 'Payment to landlord', merchant: 'pay' }))).toBe('Pay');
  });

  it('takes the payee from a structured narration exactly as printed, then softens it', () => {
    expect(rowTitle(txn({ description: 'UPI/DR/600000000001/RAHUL SH/YESB/rahulsh@ybl/Payment', merchant: 'rahul sh' })))
      .toBe('Rahul Sh');
    expect(rowTitle(txn({ description: 'UPI/DR/600000000002/StreamBo/HDFC/streambox@hdfc', merchant: 'streambo' })))
      .toBe('StreamBo');
  });

  it('finds a name whose words are separated by punctuation in the narration', () => {
    expect(rowTitle(txn({ description: 'POS 4000XXXX1234 NORTHWIND.CLEARING CORP MUMBAI', merchant: 'northwind clearing corp' })))
      .toBe('Northwind.Clearing Corp');
  });

  it('does not match the merchant inside a longer word', () => {
    expect(rowTitle(txn({ description: 'IMPS/P2A/600000000003/INTERESTING TRADERS', merchant: 'interest' })))
      .toBe('Interest');
  });

  it('shows the stored merchant, capitalised, when it is not in the description', () => {
    expect(rowTitle(txn({ description: 'CREDIT INT PD 01-07-26 TO 30-09-26', merchant: 'interest' }))).toBe('Interest');
    expect(rowTitle(txn({ description: 'UPI/CR/600000000004', merchant: 'meera nair' }))).toBe('Meera Nair');
  });

  it('keeps the capitals of a merchant the person typed, apart from all-capital words', () => {
    expect(rowTitle(txn({ description: 'UPI/CR/600000000005', merchant: 'PhonePe Wallet' }))).toBe('PhonePe Wallet');
  });

  it('uses the merchant when the description is empty', () => {
    expect(rowTitle(txn({ description: '', merchant: 'green basket' }))).toBe('Green Basket');
  });

  it('survives a merchant with pattern characters or only punctuation', () => {
    expect(rowTitle(txn({ description: 'POS A&B (P) LTD. PUNE', merchant: 'A&B (P) Ltd.' }))).toBe('A&B (P) Ltd');
    expect(rowTitle(txn({ description: 'Cash deposit', merchant: '***' }))).toBe('Cash deposit');
    expect(rowTitle(txn({ description: '', merchant: '***' }))).toBe('Transaction');
  });
});

describe('rowTags', () => {
  it('gives no tag to an ordinary row, reviewed or not', () => {
    expect(rowTags(txn())).toEqual([]);
    expect(rowTags(txn({ categoryManuallySet: true }))).toEqual([]);
  });

  it('orders match, bank correction, needs review, recurring, international', () => {
    const tags = rowTags(txn({
      reconciliationStatus: 'TRANSFER', pendingBankCorrection: true, needsCategoryReview: true,
      recurring: true, international: true,
    }));
    expect(tags.map((t) => t.label)).toEqual(['Transfer', 'Bank Correction', 'Needs Review', 'Recurring', 'International']);
    expect(tags.map((t) => t.key)).toEqual(['match', 'bank-correction', 'needs-review', 'recurring', 'international']);
  });

  it.each([
    ['DUPLICATE', 'Duplicate', 'danger'],
    ['TRANSFER', 'Transfer', 'neutral'],
    ['REFUND', 'Refund', 'success'],
    ['REVERSAL', 'Reversed', 'warning'],
    ['INVESTMENT_TRANSFER', 'Investment', 'neutral'],
    ['SUPERSEDED', 'Superseded', 'neutral'],
  ] as const)('colours the %s match tag', (status, label, tone) => {
    expect(rowTags(txn({ reconciliationStatus: status }))).toEqual([{ key: 'match', label, tone }]);
  });

  it('colours the review tags', () => {
    expect(rowTags(txn({ pendingBankCorrection: true }))[0].tone).toBe('danger');
    expect(rowTags(txn({ needsCategoryReview: true }))[0].tone).toBe('warning');
    expect(rowTags(txn({ recurring: true }))[0].tone).toBe('neutral');
    expect(rowTags(txn({ international: true }))[0].tone).toBe('neutral');
  });
});

describe('rowSpokenLabel', () => {
  it('starts with the title a sighted person sees and keeps the date', () => {
    const label = rowSpokenLabel(txn({ description: 'UPI/DR/600000000001/RAHUL SH/YESB', merchant: 'rahul sh', counterpartyType: 'PERSON' }));
    expect(label).toBe('Rahul Sh, expense ₹1,250, Food, 2026-07-14, Sent to a person');
  });

  it('adds the match explanation and the tags shown, with international last', () => {
    const label = rowSpokenLabel(txn({ reconciliationStatus: 'DUPLICATE', recurring: true, international: true }));
    expect(label).toBe('Grocery run, expense ₹1,250, Food, 2026-07-14, Matched as a repeat of another transaction, Recurring, international');
  });

  it('does not say Reviewed or Categorized', () => {
    expect(rowSpokenLabel(txn({ categoryManuallySet: true }))).not.toMatch(/Reviewed|Categorized/);
    expect(rowSpokenLabel(txn())).not.toMatch(/Reviewed|Categorized/);
  });

  it('says income for money in', () => {
    expect(rowSpokenLabel(txn({ type: 'INCOME', amount: 500 }))).toContain('income ₹500');
  });
});

describe('dayHeading', () => {
  const today = new Date(2026, 9, 12); // 12 Oct 2026, local

  it('says Today and Yesterday', () => {
    expect(dayHeading('2026-10-12', today)).toBe('Today');
    expect(dayHeading('2026-10-11', today)).toBe('Yesterday');
  });

  it('writes a short day and month for the current year', () => {
    expect(dayHeading('2026-09-30', today)).toBe('Wed, 30 Sep');
    expect(dayHeading('2026-01-01', today)).toBe('Thu, 1 Jan');
  });

  it('adds the year when it is not the current year', () => {
    expect(dayHeading('2025-12-31', today)).toBe('Wed, 31 Dec 2025');
  });
});

describe('mergePagesById', () => {
  it('keeps the first copy of a transaction that comes back on two pages', () => {
    const a = txn({ id: 'a' }); const b = txn({ id: 'b' }); const c = txn({ id: 'c' });
    expect(mergePagesById([{ content: [a, b] }, { content: [b, c] }]).map((t) => t.id)).toEqual(['a', 'b', 'c']);
  });

  it('keeps the first position even when the second copy has a different date', () => {
    const first = txn({ id: 'x', date: '2026-07-14' });
    const again = txn({ id: 'x', date: '2026-07-13' });
    const merged = mergePagesById([{ content: [first] }, { content: [again] }]);
    expect(merged).toEqual([first]);
  });

  it('returns an empty list for no pages', () => {
    expect(mergePagesById([])).toEqual([]);
  });
});

describe('buildDayPieces', () => {
  const today = new Date(2026, 9, 12);
  const many = (n: number, date: string) => Array.from({ length: n }, (_, i) => txn({ id: `${date}-${i}`, date, amount: -10 }));

  it('makes one piece per ordinary day, with its heading and whole-day total', () => {
    const pieces = buildDayPieces([
      txn({ id: 'a', date: '2026-10-12', amount: -100 }),
      txn({ id: 'b', date: '2026-10-12', type: 'INCOME', amount: 40 }),
      txn({ id: 'c', date: '2026-10-10', amount: -5 }),
    ], today);
    expect(pieces).toHaveLength(2);
    expect(pieces[0]).toMatchObject({ key: '2026-10-12#0', heading: 'Today', subtotal: -60, first: true, last: true });
    expect(pieces[0].rows.map((t) => t.id)).toEqual(['a', 'b']);
    expect(pieces[1]).toMatchObject({ key: '2026-10-10#0', heading: 'Sat, 10 Oct', subtotal: -5 });
  });

  it('cuts a long day into pieces of 25, heading on the first, total on all', () => {
    const pieces = buildDayPieces(many(60, '2026-09-01'), today);
    expect(pieces.map((p) => p.rows.length)).toEqual([25, 25, 10]);
    expect(pieces.map((p) => p.heading)).toEqual(['Tue, 1 Sep', null, null]);
    expect(pieces.map((p) => [p.first, p.last])).toEqual([[true, false], [false, false], [false, true]]);
    expect(pieces.every((p) => p.subtotal === -600)).toBe(true);
    expect(new Set(pieces.map((p) => p.key)).size).toBe(3);
  });

  it.each([25, 50])('makes no empty piece for a day of exactly %i rows', (n) => {
    const pieces = buildDayPieces(many(n, '2026-09-01'), today);
    expect(pieces).toHaveLength(n / DAY_PIECE_SIZE);
    expect(pieces.at(-1)).toMatchObject({ last: true });
    expect(pieces.every((p) => p.rows.length === DAY_PIECE_SIZE)).toBe(true);
  });

  it('leaves duplicate and superseded rows out of the total, as the old grouping did', () => {
    const pieces = buildDayPieces([
      txn({ id: 'a', date: '2026-09-01', amount: -100 }),
      txn({ id: 'b', date: '2026-09-01', amount: -100, reconciliationStatus: 'DUPLICATE' }),
    ], today);
    expect(pieces[0].subtotal).toBe(-100);
    expect(pieces[0].rows).toHaveLength(2);
  });

  it('returns no pieces for no transactions', () => {
    expect(buildDayPieces([], today)).toEqual([]);
  });
});
```

- [ ] **Step 2: Run the tests and see them fail**

Run: `zsh S/m22.sh jest src/lib/ledgerRows.test.ts`
Expected: FAIL, "Cannot find module './ledgerRows'".

- [ ] **Step 3: Write `mobile/src/lib/ledgerRows.ts`**

Move `GroupedRow`, `SUBTOTAL_EXCLUDED_STATUSES` and `groupTransactionsByDay` out of `LedgerScreen.tsx` into this file with their comments, unchanged except that `GroupedRow` gains `export`. Then add:

```ts
import { counterpartyLabel } from './counterpartyLabel';
import { fmtCurrency, fromLocalDateString, toLocalDateString } from './format';
import { reconciliationBadge } from './reconciliationBadge';
import type { ReconciliationStatus, Transaction } from '../types';

// A word is a run of the letters A to Z; an apostrophe between letters stays inside it, so
// "DOMINO'S" is one word. Letters outside A to Z are left alone: statements print these names in
// plain capitals, and a pattern that names other alphabets is one more thing to get wrong.
const WORD = /[A-Za-z]+(?:'[A-Za-z]+)*/g;

/**
 * The owner's choice for row titles (design, 2026-10-12): a word printed entirely in capitals is
 * shown with only its first letter capital. A word that already has a small letter, a single
 * letter, digits and punctuation are left exactly as printed. An abbreviation therefore reads as a
 * word ("IRCTC" as "Irctc"); that cost was accepted with the choice.
 */
export function softenCapitals(text: string): string {
  return text.replace(WORD, (word) => {
    const letters = word.replace(/'/g, '');
    if (letters.length < 2 || word !== word.toUpperCase()) return word;
    return word[0] + word.slice(1).toLowerCase();
  });
}

/** Lower case, with every run of anything that is not a letter or digit as one space: the same
 *  reduction the server's merchant label has already been through. */
function plain(text: string): string {
  return text.toLowerCase().replace(/[^a-z0-9]+/g, ' ').trim();
}

function capitaliseIfLower(text: string): string {
  if (text !== text.toLowerCase()) return text;
  return text.replace(/(^|\s)([a-z])/g, (_, gap: string, letter: string) => gap + letter.toUpperCase());
}

function printedTitle(description: string, merchant: string): string {
  const words = plain(merchant).split(' ').filter(Boolean);
  if (words.length === 0) return description || 'Transaction';
  const key = words.join(' ');

  // A description without a slash that begins with the merchant is plain words already (a
  // hand-entered transaction, an unstructured bank line): it is the title, in full. The slash
  // test keeps out the layouts that print the payee ahead of a structured narration.
  if (description && !description.includes('/')) {
    const reduced = plain(description);
    if (reduced === key || reduced.startsWith(`${key} `)) return description;
  }

  // Otherwise the merchant's words are looked for inside the description, whatever punctuation
  // sits between them, as whole words. `words` holds only a-z and 0-9, so nothing in it can act
  // as a pattern character.
  const found = new RegExp(`(?:^|[^A-Za-z0-9])(${words.join('[^A-Za-z0-9]+')})(?![A-Za-z0-9])`, 'i').exec(description);
  if (found) return found[1];

  return capitaliseIfLower(merchant);
}

/**
 * Who a transaction was with, as the row shows it. The server's `merchant` cannot be shown as it
 * is: it is lower case, stripped of punctuation and cut to four words, for hand-entered
 * transactions too. See the design document for the order of the rules and what was measured.
 */
export function rowTitle(t: Pick<Transaction, 'description' | 'merchant'>): string {
  const description = (t.description ?? '').trim();
  const merchant = (t.merchant ?? '').trim();
  return softenCapitals(merchant ? printedTitle(description, merchant) : description || 'Transaction');
}

export type RowTagTone = 'warning' | 'danger' | 'success' | 'neutral';
export interface RowTag {
  key: 'match' | 'bank-correction' | 'needs-review' | 'recurring' | 'international';
  label: string;
  tone: RowTagTone;
}

const MATCH_TONE: Record<Exclude<ReconciliationStatus, 'OK'>, RowTagTone> = {
  DUPLICATE: 'danger',
  REVERSAL: 'warning',
  REFUND: 'success',
  TRANSFER: 'neutral',
  INVESTMENT_TRANSFER: 'neutral',
  SUPERSEDED: 'neutral',
};

/**
 * The tags a row shows, in order. "Reviewed" and "Categorized" are the normal state of a row and
 * are not tags; they stay in the details sheet (statusBadges in LedgerScreen.tsx still makes them
 * for that sheet).
 */
export function rowTags(t: Transaction): RowTag[] {
  const tags: RowTag[] = [];
  const match = reconciliationBadge(t.reconciliationStatus);
  if (match && t.reconciliationStatus !== 'OK') {
    tags.push({ key: 'match', label: match.label, tone: MATCH_TONE[t.reconciliationStatus] });
  }
  if (t.pendingBankCorrection) tags.push({ key: 'bank-correction', label: 'Bank Correction', tone: 'danger' });
  if (t.needsCategoryReview) tags.push({ key: 'needs-review', label: 'Needs Review', tone: 'warning' });
  if (t.recurring) tags.push({ key: 'recurring', label: 'Recurring', tone: 'neutral' });
  if (t.international) tags.push({ key: 'international', label: 'International', tone: 'neutral' });
  return tags;
}

/**
 * What a screen reader says for a row: the title as shown, direction and amount, category, the
 * date (the day heading is a separate element), who was on the other side in full, what a match
 * means, then the tags on the row.
 */
export function rowSpokenLabel(t: Transaction): string {
  const cp = counterpartyLabel(t.counterpartyType, t.type);
  const match = reconciliationBadge(t.reconciliationStatus);
  const parts = [
    rowTitle(t),
    `${t.type === 'INCOME' ? 'income' : 'expense'} ${fmtCurrency(Math.abs(t.amount))}`,
    t.categoryName,
    t.date,
    cp?.full,
    match?.hint,
    ...rowTags(t).filter((tag) => tag.key !== 'match').map((tag) => (tag.key === 'international' ? 'international' : tag.label)),
  ];
  return parts.filter(Boolean).join(', ');
}

// Written out, not asked of the device: the short month for September differs between the
// engines this runs on ("Sep" and "Sept"), and a heading must read the same in a test as on a phone.
const WEEKDAYS = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];
const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

/** "Today", "Yesterday", or "Wed, 30 Sep"; the year is added when it is not `today`'s year. */
export function dayHeading(date: string, today: Date): string {
  const yesterday = new Date(today);
  yesterday.setDate(yesterday.getDate() - 1);
  if (date === toLocalDateString(today)) return 'Today';
  if (date === toLocalDateString(yesterday)) return 'Yesterday';
  const d = fromLocalDateString(date);
  const short = `${WEEKDAYS[d.getDay()]}, ${d.getDate()} ${MONTHS[d.getMonth()]}`;
  return d.getFullYear() === today.getFullYear() ? short : `${short} ${d.getFullYear()}`;
}

/**
 * Every fetched page as one list with each transaction once. The server's search has returned the
 * same row on two pages (its sort had no tie-break); the first copy keeps its place and later
 * copies are dropped, so a row is neither listed nor counted twice.
 */
export function mergePagesById(pages: readonly { content: Transaction[] }[]): Transaction[] {
  const seen = new Set<string>();
  const merged: Transaction[] = [];
  for (const page of pages) {
    for (const t of page.content) {
      if (seen.has(t.id)) continue;
      seen.add(t.id);
      merged.push(t);
    }
  }
  return merged;
}

/** Drawing one very long card in a single pass is what measured as expensive, so a day is cut. */
export const DAY_PIECE_SIZE = 25;

export interface DayPiece {
  key: string;
  date: string;
  /** The day's heading, on its first piece only. */
  heading: string | null;
  /** The whole day's total, on every piece. */
  subtotal: number;
  rows: Transaction[];
  first: boolean;
  last: boolean;
}

/** The list's items: each day as one card, cut into pieces of at most `pieceSize` rows. */
export function buildDayPieces(txns: Transaction[], today: Date = new Date(), pieceSize: number = DAY_PIECE_SIZE): DayPiece[] {
  const pieces: DayPiece[] = [];
  let day: { date: string; subtotal: number; rows: Transaction[] } | null = null;

  function close() {
    if (!day) return;
    const count = Math.ceil(day.rows.length / pieceSize);
    for (let i = 0; i < count; i++) {
      pieces.push({
        key: `${day.date}#${i}`,
        date: day.date,
        heading: i === 0 ? dayHeading(day.date, today) : null,
        subtotal: day.subtotal,
        rows: day.rows.slice(i * pieceSize, (i + 1) * pieceSize),
        first: i === 0,
        last: i === count - 1,
      });
    }
  }

  for (const item of groupTransactionsByDay(txns, today)) {
    if (item.kind === 'header') {
      close();
      day = { date: item.date, subtotal: item.subtotal, rows: [] };
    } else {
      day!.rows.push(item.transaction);
    }
  }
  close();
  return pieces;
}
```

In `LedgerScreen.tsx`, where the moved code was, leave:

```ts
// Moved to lib/ledgerRows.ts with the rest of the row logic; re-exported so existing importers
// and this screen's own tests keep their import.
export { groupTransactionsByDay } from '../lib/ledgerRows';
```

and delete the now unused imports (`fromLocalDateString`, `toLocalDateString`) only if nothing else in the file uses them. The screen still calls `groupTransactionsByDay` until Task 8, so import it too: `import { groupTransactionsByDay } from '../lib/ledgerRows';`.

- [ ] **Step 4: Run the new tests and the screen's tests**

Run: `zsh S/m22.sh jest src/lib/ledgerRows.test.ts src/screens/LedgerScreen.test.tsx`
Expected: both PASS (the screen file is unchanged in behaviour).

- [ ] **Step 5: Types and lint, then commit**

Run: `zsh S/m22.sh typecheck` then `zsh S/m22.sh lint`. Expected: no errors.

```bash
git -C /Users/sid/Downloads/finora/.claude/worktrees/mobile-dashboard-cards add mobile/src/lib/ledgerRows.ts mobile/src/lib/ledgerRows.test.ts mobile/src/screens/LedgerScreen.tsx
git -C /Users/sid/Downloads/finora/.claude/worktrees/mobile-dashboard-cards commit -m "feat(mobile): row title, tags, day pieces and page merge for the Transactions list"
```

---

### Task 2: Contrast of the new pairs

**Files:**
- Create: `mobile/src/theme/ledgerPairs.test.ts`

Tags, the selected segment, the count badge, the sheet's selected chip and button, and the active filter chip are text on a solid fill, not on glass, so they are checked as plain pairs. Text that sits on glass (unselected segment, day heading, row text) uses tokens `glassContrast.test.ts` already measures.

- [ ] **Step 1: Write the test**

```ts
import { dark, light, type Palette } from './palette';

const channel = (v: number) => { const c = v / 255; return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4; };
const rgb = (hex: string) => { const n = parseInt(hex.slice(1), 16); return [(n >> 16) & 255, (n >> 8) & 255, n & 255]; };
const luminance = (hex: string) => { const [r, g, b] = rgb(hex); return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b); };
const ratio = (a: string, b: string) => { const [x, y] = [luminance(a), luminance(b)].sort((p, q) => q - p); return (x + 0.05) / (y + 0.05); };

// [text, fill]: every solid pair the Transactions screen draws text on.
const PAIRS: [keyof Palette, keyof Palette, string][] = [
  ['warningInk', 'warningBg', 'Needs Review and Reversed tags'],
  ['dangerInk', 'dangerBg', 'Bank Correction and Duplicate tags'],
  ['successInk', 'successBg', 'Refund tag'],
  ['mutedInk', 'border', 'neutral tags'],
  ['onPrimary', 'primary', 'selected segment, count badge, selected status, sheet button'],
  ['primary', 'primaryLight', 'active filter chip'],
];

describe.each([['light', light], ['dark', dark]] as const)('%s theme', (_, p) => {
  it.each(PAIRS)('%s on %s clears 4.5:1 (%s)', (text, fill) => {
    expect(ratio(p[text] as string, p[fill] as string)).toBeGreaterThanOrEqual(4.5);
  });
});
```

- [ ] **Step 2: Run it**

Run: `zsh S/m22.sh jest src/theme/ledgerPairs.test.ts`
Expected: PASS, 12 tests. This test guards existing tokens, so it passes on first run; that is the point of it. If any pair fails, stop: the design reference names a pair the palette cannot carry, and the pair is changed in the spec and here before any component uses it.

- [ ] **Step 3: Commit**

```bash
git -C /Users/sid/Downloads/finora/.claude/worktrees/mobile-dashboard-cards add mobile/src/theme/ledgerPairs.test.ts
git -C /Users/sid/Downloads/finora/.claude/worktrees/mobile-dashboard-cards commit -m "test(mobile): contrast of the Transactions screen's text and fill pairs"
```

---

### Task 3: Month card

**Files:**
- Create: `mobile/src/components/ledger/LedgerMonthCard.tsx`
- Create: `mobile/src/components/ledger/LedgerMonthCard.test.tsx`
- Modify: `scripts/check-reporting-period-labels.py` (the `COMPANION_FILES` entry for `LedgerScreen.tsx`)

**Interfaces:**
- Consumes: `KpiItem` from `components/dashboard/LedgerSnapshotCard`; `DashboardCard`; `DeltaChip`; `AnimatedNumber`.
- Produces: `LedgerMonthCard({ kpis, title, deltaLabel, deltaSpokenLabel, stacked }: { kpis: KpiItem[]; title: string; deltaLabel: string; deltaSpokenLabel: string; stacked: boolean })`. Test ids `ledger-month-card`, `kpi-Income`, `kpi-Expenses`, `ledger-month-columns`.

- [ ] **Step 1: Write the failing test**

```tsx
import { render, screen } from '@testing-library/react-native';
import { LedgerMonthCard } from './LedgerMonthCard';
import { ThemeProvider } from '../../theme';
import type { KpiItem } from '../dashboard/LedgerSnapshotCard';

const kpi = (label: string, value: number, delta: number | null, invert: boolean): KpiItem =>
  ({ label, value, delta, invert, caption: null, isPercent: false });
const KPIS = [kpi('Income', 145000, 12, false), kpi('Expenses', 18672, 8, true), kpi('Net Savings', 126328, 15, false)];

function show(over: Partial<Parameters<typeof LedgerMonthCard>[0]> = {}) {
  return render(
    <ThemeProvider>
      <LedgerMonthCard kpis={KPIS} title="Aug 26" deltaLabel="vs the month before Aug 26"
        deltaSpokenLabel="versus the month before Aug 26" stacked={false} {...over} />
    </ThemeProvider>
  );
}

describe('LedgerMonthCard', () => {
  it('shows the period it is given, the comparison, and the two figures', () => {
    show();
    expect(screen.getByText('Aug 26')).toBeTruthy();
    expect(screen.getByText('vs the month before Aug 26')).toBeTruthy();
    expect(screen.getByTestId('kpi-Income')).toBeTruthy();
    expect(screen.getByTestId('kpi-Expenses')).toBeTruthy();
    expect(screen.queryByText('Net Savings')).toBeNull();
  });

  it('speaks a change with the comparison in words, and a rise in expenses as a rise', () => {
    show();
    expect(screen.getByLabelText('Income up 12.0 percent versus the month before Aug 26')).toBeTruthy();
    expect(screen.getByLabelText('Expenses up 8.0 percent versus the month before Aug 26')).toBeTruthy();
  });

  it('shows no chip and no comparison when there is no change figure', () => {
    show({ kpis: [kpi('Income', 1, null, false), kpi('Expenses', 2, null, true)] });
    expect(screen.queryByText(/%/)).toBeNull();
    expect(screen.queryByText('vs the month before Aug 26')).toBeNull();
  });

  it('renders nothing without both figures', () => {
    show({ kpis: [kpi('Income', 1, null, false)] });
    expect(screen.queryByTestId('ledger-month-card')).toBeNull();
  });

  it('stacks the two columns at large text', () => {
    show({ stacked: true });
    expect(screen.getByTestId('ledger-month-columns')).toHaveStyle({ flexDirection: 'column' });
  });

  it('puts them side by side otherwise', () => {
    show();
    expect(screen.getByTestId('ledger-month-columns')).toHaveStyle({ flexDirection: 'row' });
  });
});
```

- [ ] **Step 2: Run it and see it fail**

Run: `zsh S/m22.sh jest src/components/ledger/LedgerMonthCard.test.tsx`
Expected: FAIL, "Cannot find module './LedgerMonthCard'".

- [ ] **Step 3: Write the component**

```tsx
import { StyleSheet, Text, View } from 'react-native';
import { AnimatedNumber } from '../AnimatedNumber';
import { DashboardCard } from '../dashboard/DashboardCard';
import { DeltaChip } from '../dashboard/DeltaChip';
import type { KpiItem } from '../dashboard/LedgerSnapshotCard';
import { spacing, typography, useTheme } from '../../theme';

/**
 * The Transactions tab's month card: Income and Expenses from the dashboard's own figures. The
 * period heading and the comparison wording are props from useDashboardKpis and are never written
 * here as text; scripts/check-reporting-period-labels.py scans this file to keep it so.
 */
export function LedgerMonthCard({
  kpis, title, deltaLabel, deltaSpokenLabel, stacked,
}: { kpis: KpiItem[]; title: string; deltaLabel: string; deltaSpokenLabel: string; stacked: boolean }) {
  const c = useTheme();
  const income = kpis.find((k) => k.label === 'Income');
  const expenses = kpis.find((k) => k.label === 'Expenses');
  if (!income || !expenses) return null;
  const hasDelta = (k: KpiItem) => k.delta !== null && k.delta !== undefined;

  function column(k: KpiItem) {
    const delta = k.delta ?? 0;
    return (
      <View style={styles.column}>
        <View style={styles.labelRow}>
          <Text style={[typography.labelS, { color: c.mutedInk }]}>{k.label}</Text>
          {hasDelta(k) ? (
            <View
              accessible
              accessibilityLabel={`${k.label} ${delta >= 0 ? 'up' : 'down'} ${Math.abs(delta).toFixed(1)} percent ${deltaSpokenLabel}`}
            >
              <DeltaChip delta={delta} good={k.invert ? delta < 0 : delta >= 0} />
            </View>
          ) : null}
        </View>
        {/* Income and Expenses are always numbers; only Savings Rate can be null. */}
        <AnimatedNumber testID={`kpi-${k.label}`} value={k.value ?? 0} style={[typography.numberM, { color: c.ink }]} />
      </View>
    );
  }

  return (
    <DashboardCard testID="ledger-month-card" style={styles.card}>
      <View style={styles.head}>
        <Text style={[typography.eyebrow, { color: c.mutedInk }]}>{title}</Text>
        {hasDelta(income) || hasDelta(expenses) ? (
          <Text style={[typography.caption, styles.comparison, { color: c.mutedInk }]}>{deltaLabel}</Text>
        ) : null}
      </View>
      <View testID="ledger-month-columns" style={stacked ? styles.stacked : styles.sideBySide}>
        {column(income)}
        {column(expenses)}
      </View>
    </DashboardCard>
  );
}

const styles = StyleSheet.create({
  card: { paddingVertical: 14, paddingHorizontal: spacing.md },
  head: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'baseline', gap: spacing.sm, marginBottom: spacing.sm },
  comparison: { flexShrink: 1, textAlign: 'right' },
  sideBySide: { flexDirection: 'row', gap: spacing.md },
  stacked: { flexDirection: 'column', gap: spacing.ms },
  column: { flex: 1, gap: spacing.xs },
  labelRow: { flexDirection: 'row', alignItems: 'center', flexWrap: 'wrap', gap: spacing.sm },
});
```

- [ ] **Step 4: Register the file with the period-label guard**

In `scripts/check-reporting-period-labels.py`, change the `LedgerScreen.tsx` entry of `COMPANION_FILES` to:

```python
    "mobile/src/screens/LedgerScreen.tsx": [
        "mobile/src/lib/useDashboardKpis.ts",
        "mobile/src/components/ledger/LedgerMonthCard.tsx",
    ],
```

- [ ] **Step 5: Run the test and the guards**

Run: `zsh S/m22.sh jest src/components/ledger/LedgerMonthCard.test.tsx` then `zsh S/guards.sh`.
Expected: PASS, 6 tests; each guard prints `exit=0`.

Then prove the guard really reads the new file: add the line `const x = 'vs last month';` to `LedgerMonthCard.tsx`, run `zsh S/guards.sh`, and expect `check-reporting-period-labels exit=1` naming that file. Remove the line and run again: `exit=0`.

- [ ] **Step 6: Commit**

```bash
git -C /Users/sid/Downloads/finora/.claude/worktrees/mobile-dashboard-cards add mobile/src/components/ledger/LedgerMonthCard.tsx mobile/src/components/ledger/LedgerMonthCard.test.tsx scripts/check-reporting-period-labels.py
git -C /Users/sid/Downloads/finora/.claude/worktrees/mobile-dashboard-cards commit -m "feat(mobile): month card for the Transactions tab"
```

---

### Task 4: Day card and rows

**Files:**
- Create: `mobile/src/components/ledger/LedgerDayCard.tsx`
- Create: `mobile/src/components/ledger/LedgerDayCard.test.tsx`

**Interfaces:**
- Consumes: `DayPiece`, `rowTitle`, `rowTags`, `rowSpokenLabel`, `RowTagTone` from `lib/ledgerRows`.
- Produces: `LedgerDayCard({ piece, deletingId, largeText, onOpen, onDelete }: { piece: DayPiece; deletingId: string | null; largeText: boolean; onOpen: (t: Transaction) => void; onDelete: (t: Transaction) => void })`, memoised. Test ids: `day-card-<piece.key>`, `reconciliation-badge-<id>` (match tag), `international-badge-<id>`, `row-tag-<key>-<id>` (the other tags), `row-deleting-<id>`.

- [ ] **Step 1: Write the failing test**

```tsx
import { Platform } from 'react-native';
import { fireEvent, render, screen } from '@testing-library/react-native';
import { LedgerDayCard } from './LedgerDayCard';
import { ThemeProvider } from '../../theme';
import { light } from '../../theme/palette';
import type { DayPiece } from '../../lib/ledgerRows';
import type { Transaction } from '../../types';

function txn(over: Partial<Transaction> = {}): Transaction {
  return {
    id: 't-1', accountId: 'a-1', categoryId: 'c-1', categoryName: 'Food', date: '2026-07-14',
    description: 'UPI/DR/600000000001/RAHUL SH/YESB', merchant: 'rahul sh', paymentMethod: 'UPI', amount: -1250,
    type: 'EXPENSE', tags: [], notes: null, reconciliationStatus: 'OK', recurring: false,
    needsCategoryReview: false, pendingBankCorrection: false, categoryManuallySet: false,
    counterpartyType: 'PERSON', international: false, foreignCurrency: null, foreignAmount: null,
    ...over,
  };
}
function piece(over: Partial<DayPiece> = {}): DayPiece {
  // The total differs from the one row's amount so each can be found on its own.
  return { key: '2026-07-14#0', date: '2026-07-14', heading: 'Tue, 14 Jul', subtotal: -2000, rows: [txn()], first: true, last: true, ...over };
}
const onOpen = jest.fn();
const onDelete = jest.fn();
function show(p: DayPiece, over: { deletingId?: string | null; largeText?: boolean } = {}) {
  return render(
    <ThemeProvider>
      <LedgerDayCard piece={p} deletingId={over.deletingId ?? null} largeText={over.largeText ?? false} onOpen={onOpen} onDelete={onDelete} />
    </ThemeProvider>
  );
}
beforeEach(() => jest.clearAllMocks());

describe('LedgerDayCard', () => {
  it('shows the day heading as a heading, and the day total signed', () => {
    show(piece());
    expect(screen.getByRole('header', { name: 'Tue, 14 Jul' })).toBeTruthy();
    expect(screen.getByText('-₹2,000')).toBeTruthy();
    expect(screen.getByText('-₹2,000')).toHaveStyle({ color: light.dangerInk });
  });

  it('shows no heading on a later piece of the same day', () => {
    show(piece({ key: '2026-07-14#1', heading: null, first: false }));
    expect(screen.queryByRole('header')).toBeNull();
  });

  it('titles a row by the softened payee name, with category and counterparty under it, and no date', () => {
    show(piece());
    expect(screen.getByText('Rahul Sh')).toBeTruthy();
    expect(screen.getByText('Food · Person')).toBeTruthy();
    expect(screen.queryByText(/2026-07-14/)).toBeNull();
    expect(screen.queryByText(/UPI\/DR/)).toBeNull();
  });

  it('shows only the category when the counterparty is unknown', () => {
    show(piece({ rows: [txn({ counterpartyType: 'UNKNOWN' })] }));
    expect(screen.getByText('Food')).toBeTruthy();
  });

  it('shows no tag line for an ordinary row', () => {
    show(piece());
    expect(screen.queryByText('Categorized')).toBeNull();
    expect(screen.queryByText('Reviewed')).toBeNull();
    expect(screen.queryByTestId('reconciliation-badge-t-1')).toBeNull();
  });

  it('shows the tags a row has, under their test ids', () => {
    show(piece({ rows: [txn({ reconciliationStatus: 'TRANSFER', needsCategoryReview: true, international: true })] }));
    expect(screen.getByTestId('reconciliation-badge-t-1')).toHaveTextContent('Transfer');
    expect(screen.getByTestId('row-tag-needs-review-t-1')).toHaveTextContent('Needs Review');
    expect(screen.getByTestId('international-badge-t-1')).toHaveTextContent('International');
  });

  it('is one button per row that opens details on press and asks to delete on long press', () => {
    const t = txn();
    show(piece({ rows: [t] }));
    const row = screen.getByRole('button', { name: /^Rahul Sh, expense ₹1,250, Food, 2026-07-14, Sent to a person$/ });
    fireEvent.press(row);
    expect(onOpen).toHaveBeenCalledWith(t);
    fireEvent(row, 'longPress');
    expect(onDelete).toHaveBeenCalledWith(t);
  });

  it('replaces the amount with a spinner while that row is being deleted', () => {
    show(piece(), { deletingId: 't-1' });
    expect(screen.getByTestId('row-deleting-t-1')).toBeTruthy();
    expect(screen.queryByText('-₹1,250')).toBeNull();
    expect(screen.getByText('-₹2,000')).toBeTruthy(); // the day total stays
  });

  it('colours income green and expense red', () => {
    show(piece({ rows: [txn({ id: 'in', type: 'INCOME', amount: 500 }), txn({ id: 'out', amount: -70 })], subtotal: 430 }));
    expect(screen.getByText('+₹500')).toHaveStyle({ color: light.successInk });
    expect(screen.getByText('-₹70')).toHaveStyle({ color: light.dangerInk });
    expect(screen.getByText('+₹430')).toHaveStyle({ color: light.successInk });
  });

  it('at large text hides the avatar, lets the title wrap and moves the amount under the text', () => {
    show(piece(), { largeText: true });
    expect(screen.queryByLabelText('rahul sh')).toBeNull();
    expect(screen.getByText('Rahul Sh').props.numberOfLines).toBeUndefined();
    expect(screen.getByTestId('row-amount-below-t-1')).toBeTruthy();
  });

  it('keeps the title on one line at ordinary text sizes', () => {
    show(piece());
    expect(screen.getByText('Rahul Sh').props.numberOfLines).toBe(1);
    expect(screen.queryByTestId('row-amount-below-t-1')).toBeNull();
  });

  it('rounds and shadows a whole card, and only the ends of a cut day', () => {
    show(piece());
    expect(screen.getByTestId('day-card-2026-07-14#0')).toHaveStyle({ borderTopLeftRadius: 20, borderBottomLeftRadius: 20 });

    show(piece({ key: 'mid', heading: null, first: false, last: false }));
    const mid = screen.getByTestId('day-card-mid');
    expect(mid).not.toHaveStyle({ borderTopLeftRadius: 20 });
    expect(mid).not.toHaveStyle({ borderBottomLeftRadius: 20 });
    expect(mid).toHaveStyle({ borderTopWidth: 0, borderBottomWidth: 0 });
    expect(mid.props.style).not.toEqual(expect.arrayContaining([expect.objectContaining({ boxShadow: expect.anything() })]));
  });

  it('uses elevation instead of a box shadow on Android', () => {
    const original = Platform.OS;
    (Platform as { OS: string }).OS = 'android';
    try {
      show(piece());
      expect(screen.getByTestId('day-card-2026-07-14#0')).toHaveStyle({ elevation: 2 });
    } finally {
      (Platform as { OS: string }).OS = original;
    }
  });
});
```

- [ ] **Step 2: Run it and see it fail**

Run: `zsh S/m22.sh jest src/components/ledger/LedgerDayCard.test.tsx`
Expected: FAIL, "Cannot find module './LedgerDayCard'".

- [ ] **Step 3: Write the component**

```tsx
import { memo } from 'react';
import { ActivityIndicator, Platform, Pressable, StyleSheet, Text, View } from 'react-native';
import { useGlassSurfaceStyle } from '../GlassSurface';
import { MerchantLogo } from '../MerchantLogo';
import { counterpartyLabel } from '../../lib/counterpartyLabel';
import { fmtCurrency } from '../../lib/format';
import { rowSpokenLabel, rowTags, rowTitle, type DayPiece, type RowTag } from '../../lib/ledgerRows';
import { radius, shadows, spacing, typography, useTheme } from '../../theme';
import type { Transaction } from '../../types';

interface RowProps {
  t: Transaction;
  deleting: boolean;
  divider: boolean;
  largeText: boolean;
  onOpen: (t: Transaction) => void;
  onDelete: (t: Transaction) => void;
}

function tagTestId(tag: RowTag, id: string): string {
  if (tag.key === 'match') return `reconciliation-badge-${id}`;
  if (tag.key === 'international') return `international-badge-${id}`;
  return `row-tag-${tag.key}-${id}`;
}

// Memoised so a new page arriving, or another row's delete, does not redraw rows already drawn.
const LedgerRow = memo(function LedgerRow({ t, deleting, divider, largeText, onOpen, onDelete }: RowProps) {
  const c = useTheme();
  const cp = counterpartyLabel(t.counterpartyType, t.type);
  const tags = rowTags(t);
  const tone = {
    warning: { bg: c.warningBg, fg: c.warningInk },
    danger: { bg: c.dangerBg, fg: c.dangerInk },
    success: { bg: c.successBg, fg: c.successInk },
    neutral: { bg: c.border, fg: c.mutedInk },
  } as const;
  const amount = deleting ? (
    <ActivityIndicator testID={`row-deleting-${t.id}`} size="small" color={c.muted} />
  ) : (
    <Text style={[typography.numberS, { color: t.type === 'INCOME' ? c.successInk : c.dangerInk }]}>
      {t.type === 'INCOME' ? '+' : '-'}
      {fmtCurrency(Math.abs(t.amount))}
    </Text>
  );

  return (
    <Pressable
      onPress={() => onOpen(t)}
      onLongPress={() => onDelete(t)}
      android_ripple={{ color: c.border }}
      // One button for the whole row. Long press is the only gesture that deletes, so the same
      // delete is also a row of its own in the details sheet this opens, where a screen reader
      // can reach it.
      accessibilityRole="button"
      accessibilityLabel={rowSpokenLabel(t)}
      accessibilityHint="Opens transaction details"
      style={[styles.row, divider && { borderTopWidth: StyleSheet.hairlineWidth, borderTopColor: c.border }]}
    >
      {largeText ? null : (
        <MerchantLogo merchant={t.merchant || t.description || '?'} size={40} person={t.counterpartyType === 'PERSON'} />
      )}
      <View style={styles.rowMain}>
        <Text style={[typography.labelM, { color: c.ink }]} numberOfLines={largeText ? undefined : 1}>
          {rowTitle(t)}
        </Text>
        <Text style={[typography.bodyS, { color: c.mutedInk }]} numberOfLines={largeText ? undefined : 1}>
          {cp ? `${t.categoryName} · ${cp.short}` : t.categoryName}
        </Text>
        {tags.length > 0 ? (
          <View style={styles.tags}>
            {tags.map((tag) => (
              <Text
                key={tag.key}
                testID={tagTestId(tag, t.id)}
                style={[typography.caption, styles.tag, { backgroundColor: tone[tag.tone].bg, color: tone[tag.tone].fg }]}
              >
                {tag.label}
              </Text>
            ))}
          </View>
        ) : null}
        {largeText ? <View testID={`row-amount-below-${t.id}`} style={styles.amountBelow}>{amount}</View> : null}
      </View>
      {largeText ? null : amount}
    </Pressable>
  );
});

/**
 * One piece of a day: the heading (on the day's first piece) and the card holding its rows. A day
 * of up to 25 rows is one piece and so one whole card. A longer day is several pieces that draw as
 * one card: only the first has the rounded top, only the last the rounded bottom and the shadow,
 * and the edges between them are dropped.
 *
 * The card is the tinted list-row surface, not a native glass view: a native effect view per card
 * in a long list is the costly kind (see GlassSurface).
 */
export const LedgerDayCard = memo(function LedgerDayCard({
  piece, deletingId, largeText, onOpen, onDelete,
}: {
  piece: DayPiece;
  deletingId: string | null;
  largeText: boolean;
  onOpen: (t: Transaction) => void;
  onDelete: (t: Transaction) => void;
}) {
  const c = useTheme();
  const glass = useGlassSurfaceStyle();
  return (
    <View>
      {piece.heading !== null ? (
        <View style={styles.heading}>
          <Text accessibilityRole="header" style={[typography.eyebrow, styles.headingLabel, { color: c.mutedInk }]} numberOfLines={1}>
            {piece.heading}
          </Text>
          <Text style={[typography.labelS, styles.headingTotal, { color: piece.subtotal >= 0 ? c.successInk : c.dangerInk }]}>
            {piece.subtotal >= 0 ? '+' : '-'}
            {fmtCurrency(Math.abs(piece.subtotal))}
          </Text>
        </View>
      ) : null}
      <View
        testID={`day-card-${piece.key}`}
        style={[
          styles.card,
          glass,
          piece.first ? styles.top : styles.joinAbove,
          piece.last ? styles.bottom : styles.joinBelow,
          // Read at render, like GlassSurface's own platform check, so a test can drive both.
          piece.last && (Platform.OS === 'ios' ? styles.shadowIos : styles.shadowAndroid),
          Platform.OS === 'android' && styles.clipRipple,
        ]}
      >
        {piece.rows.map((t, i) => (
          <LedgerRow
            key={t.id}
            t={t}
            deleting={deletingId === t.id}
            divider={i > 0 || !piece.first}
            largeText={largeText}
            onOpen={onOpen}
            onDelete={onDelete}
          />
        ))}
      </View>
    </View>
  );
});

const styles = StyleSheet.create({
  heading: {
    flexDirection: 'row', justifyContent: 'space-between', alignItems: 'baseline', gap: spacing.sm,
    paddingTop: spacing.md, paddingBottom: spacing.sm, paddingHorizontal: spacing.xs,
  },
  // The total is the figure that must never be clipped, so the date gives way first.
  headingLabel: { flexShrink: 1 },
  headingTotal: { flexShrink: 0 },
  card: { borderWidth: StyleSheet.hairlineWidth, paddingHorizontal: spacing.md, paddingVertical: 2 },
  top: { borderTopLeftRadius: radius.xxl, borderTopRightRadius: radius.xxl },
  bottom: { borderBottomLeftRadius: radius.xxl, borderBottomRightRadius: radius.xxl },
  joinAbove: { borderTopWidth: 0, paddingTop: 0 },
  joinBelow: { borderBottomWidth: 0, paddingBottom: 0 },
  shadowIos: { boxShadow: shadows.card },
  shadowAndroid: { elevation: 2 },
  // Keeps a row's ripple inside the card's corners. Not on iOS, where clipping a view is what
  // removed the dashboard cards' shadows.
  clipRipple: { overflow: 'hidden' },
  row: { flexDirection: 'row', alignItems: 'center', gap: spacing.ms, paddingVertical: spacing.ms },
  rowMain: { flex: 1 },
  tags: { flexDirection: 'row', flexWrap: 'wrap', gap: spacing.xs, marginTop: 5 },
  tag: { borderRadius: 999, paddingHorizontal: spacing.sm, paddingVertical: 2, overflow: 'hidden' },
  amountBelow: { marginTop: spacing.xs, alignItems: 'flex-start' },
});
```

- [ ] **Step 4: Run the test**

Run: `zsh S/m22.sh jest src/components/ledger/LedgerDayCard.test.tsx`
Expected: PASS, 13 tests. If "rounds and shadows" fails on the shadow assertion because RNTL flattens the style, assert on `StyleSheet.flatten(mid.props.style).boxShadow` being undefined instead; the behaviour under test does not change.

- [ ] **Step 5: Types, lint, commit**

Run: `zsh S/m22.sh typecheck`, `zsh S/m22.sh lint`. Expected: clean.

```bash
git -C /Users/sid/Downloads/finora/.claude/worktrees/mobile-dashboard-cards add mobile/src/components/ledger/LedgerDayCard.tsx mobile/src/components/ledger/LedgerDayCard.test.tsx
git -C /Users/sid/Downloads/finora/.claude/worktrees/mobile-dashboard-cards commit -m "feat(mobile): day card and row for the Transactions list"
```

---

### Task 5: Search, type switch, Filters button, active chips

**Files:**
- Create: `mobile/src/components/ledger/LedgerControls.tsx`
- Create: `mobile/src/components/ledger/LedgerControls.test.tsx`

**Interfaces:**
- Produces:
  - `type TypeFilter = 'ALL' | 'INCOME' | 'EXPENSE'`
  - `interface ActiveFilterChip { key: string; label: string; onClear: () => void }`
  - `LedgerControls({ keyword, onKeywordChange, searchRef, typeFilter, onTypeFilter, filtersOn, onOpenFilters, chips, stacked })` with `keyword: string`, `onKeywordChange: (text: string) => void`, `searchRef: RefObject<TextInput | null>`, `typeFilter: TypeFilter`, `onTypeFilter: (t: TypeFilter) => void`, `filtersOn: number`, `onOpenFilters: () => void`, `chips: ActiveFilterChip[]`, `stacked: boolean`.
  - Spoken names: search field "Search transactions"; segments "Filter: all", "Filter: income", "Filter: expense"; button "Filters" or "Filters, N on"; chip clear "Clear filter: <label>". Test ids: `ledger-controls-row`, `filters-count`.

- [ ] **Step 1: Write the failing test**

```tsx
import { createRef } from 'react';
import type { TextInput } from 'react-native';
import { fireEvent, render, screen } from '@testing-library/react-native';
import { LedgerControls, type ActiveFilterChip } from './LedgerControls';
import { ThemeProvider } from '../../theme';

const handlers = { onKeywordChange: jest.fn(), onTypeFilter: jest.fn(), onOpenFilters: jest.fn() };
function show(over: Partial<Parameters<typeof LedgerControls>[0]> = {}) {
  return render(
    <ThemeProvider>
      <LedgerControls keyword="" searchRef={createRef<TextInput>()} typeFilter="ALL" filtersOn={0} chips={[]} stacked={false}
        {...handlers} {...over} />
    </ThemeProvider>
  );
}
beforeEach(() => jest.clearAllMocks());

describe('LedgerControls', () => {
  it('keeps the search field, its placeholder and its spoken name', () => {
    show({ keyword: 'rent' });
    const field = screen.getByLabelText('Search transactions');
    expect(field.props.value).toBe('rent');
    expect(field.props.placeholder).toBe('Search description, merchant, bank…');
    fireEvent.changeText(field, 'rent 2');
    expect(handlers.onKeywordChange).toHaveBeenCalledWith('rent 2');
  });

  it('offers All, Income and Expense as one switch, saying which is selected', () => {
    show({ typeFilter: 'INCOME' });
    expect(screen.getByLabelText('Filter: income')).toBeSelected();
    expect(screen.getByLabelText('Filter: all')).not.toBeSelected();
    fireEvent.press(screen.getByLabelText('Filter: expense'));
    expect(handlers.onTypeFilter).toHaveBeenCalledWith('EXPENSE');
  });

  it('opens the filters from one button', () => {
    show();
    fireEvent.press(screen.getByLabelText('Filters'));
    expect(handlers.onOpenFilters).toHaveBeenCalledTimes(1);
    expect(screen.queryByTestId('filters-count')).toBeNull();
  });

  it('shows and says how many filters are on', () => {
    show({ filtersOn: 2 });
    expect(screen.getByLabelText('Filters, 2 on')).toBeTruthy();
    expect(screen.getByTestId('filters-count')).toHaveTextContent('2');
  });

  it('shows one removable chip per active filter, and no row when there is none', () => {
    const chips: ActiveFilterChip[] = [
      { key: 'status', label: 'Transfer', onClear: jest.fn() },
      { key: 'international', label: 'International', onClear: jest.fn() },
    ];
    show({ chips });
    expect(screen.getByText('Transfer')).toBeTruthy();
    fireEvent.press(screen.getByLabelText('Clear filter: International'));
    expect(chips[1].onClear).toHaveBeenCalledTimes(1);
    expect(chips[0].onClear).not.toHaveBeenCalled();
  });

  it('renders no chip row without chips', () => {
    show();
    expect(screen.queryByTestId('ledger-active-filters')).toBeNull();
  });

  it('stacks the switch and the button at large text', () => {
    show({ stacked: true });
    expect(screen.getByTestId('ledger-controls-row')).toHaveStyle({ flexDirection: 'column' });
  });
});
```

- [ ] **Step 2: Run it and see it fail**

Run: `zsh S/m22.sh jest src/components/ledger/LedgerControls.test.tsx`
Expected: FAIL, "Cannot find module './LedgerControls'".

- [ ] **Step 3: Write the component**

```tsx
import type { RefObject } from 'react';
import { Pressable, StyleSheet, Text, TextInput, View } from 'react-native';
import Ionicons from '@expo/vector-icons/Ionicons';
import { GlassSurface, useGlassSurfaceStyle } from '../GlassSurface';
import { spacing, typography, useTheme } from '../../theme';

export type TypeFilter = 'ALL' | 'INCOME' | 'EXPENSE';
export interface ActiveFilterChip { key: string; label: string; onClear: () => void }

const TYPES: { value: TypeFilter; label: string; spoken: string }[] = [
  { value: 'ALL', label: 'All', spoken: 'all' },
  { value: 'INCOME', label: 'Income', spoken: 'income' },
  { value: 'EXPENSE', label: 'Expense', spoken: 'expense' },
];

/**
 * The Transactions tab's controls: search, the All / Income / Expense switch, the one Filters
 * button that opens the rarely used filters, and a chip for every filter that is on.
 */
export function LedgerControls({
  keyword, onKeywordChange, searchRef, typeFilter, onTypeFilter, filtersOn, onOpenFilters, chips, stacked,
}: {
  keyword: string;
  onKeywordChange: (text: string) => void;
  searchRef: RefObject<TextInput | null>;
  typeFilter: TypeFilter;
  onTypeFilter: (t: TypeFilter) => void;
  filtersOn: number;
  onOpenFilters: () => void;
  chips: ActiveFilterChip[];
  stacked: boolean;
}) {
  const c = useTheme();
  // The Filters button is a Pressable, so it takes the glass fill and edge as a style.
  const glass = useGlassSurfaceStyle();

  return (
    <View style={styles.wrap}>
      <GlassSurface variant="row" style={styles.search}>
        <Ionicons name="search" size={18} color={c.muted} />
        <TextInput
          ref={searchRef}
          value={keyword}
          onChangeText={onKeywordChange}
          placeholder="Search description, merchant, bank…"
          placeholderTextColor={c.muted}
          autoCapitalize="none"
          autoCorrect={false}
          accessibilityLabel="Search transactions"
          style={[typography.bodyM, styles.searchInput, { color: c.ink }]}
        />
      </GlassSurface>

      <View testID="ledger-controls-row" style={stacked ? styles.controlsStacked : styles.controls}>
        <GlassSurface variant="row" style={[styles.track, !stacked && styles.grow]}>
          {TYPES.map((t) => {
            const selected = typeFilter === t.value;
            return (
              <Pressable
                key={t.value}
                onPress={() => onTypeFilter(t.value)}
                // The track is 44 high with 4 of padding, so a segment is 36; this makes it 44.
                hitSlop={{ top: 4, bottom: 4 }}
                accessibilityRole="button"
                accessibilityState={{ selected }}
                accessibilityLabel={`Filter: ${t.spoken}`}
                style={[styles.segment, selected && { backgroundColor: c.primary }]}
              >
                <Text style={[typography.labelS, { color: selected ? c.onPrimary : c.mutedInk }]}>{t.label}</Text>
              </Pressable>
            );
          })}
        </GlassSurface>

        <Pressable
          onPress={onOpenFilters}
          accessibilityRole="button"
          accessibilityLabel={filtersOn > 0 ? `Filters, ${filtersOn} on` : 'Filters'}
          style={[styles.filtersButton, glass]}
        >
          <Ionicons name="options-outline" size={16} color={c.ink} />
          <Text style={[typography.labelS, { color: c.ink }]}>Filters</Text>
          {filtersOn > 0 ? (
            <View style={[styles.count, { backgroundColor: c.primary }]}>
              <Text testID="filters-count" style={[typography.caption, { color: c.onPrimary }]}>{filtersOn}</Text>
            </View>
          ) : null}
        </Pressable>
      </View>

      {chips.length > 0 ? (
        <View testID="ledger-active-filters" style={styles.chips}>
          {chips.map((chip) => (
            <View key={chip.key} style={[styles.chip, { borderColor: c.primary, backgroundColor: c.primaryLight }]}>
              <Text style={[typography.labelS, styles.chipLabel, { color: c.primary }]} numberOfLines={1}>{chip.label}</Text>
              <Pressable
                onPress={chip.onClear}
                // A 28 by 28 box plus 8 on every side is a 44 by 44 target.
                hitSlop={8}
                accessibilityRole="button"
                accessibilityLabel={`Clear filter: ${chip.label}`}
                style={styles.chipClear}
              >
                <Ionicons name="close" size={14} color={c.primary} />
              </Pressable>
            </View>
          ))}
        </View>
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  wrap: { gap: spacing.ms },
  search: {
    flexDirection: 'row', alignItems: 'center', gap: spacing.sm,
    minHeight: 48, borderRadius: 999, paddingHorizontal: spacing.md,
  },
  searchInput: { flex: 1, paddingVertical: 0 },
  controls: { flexDirection: 'row', alignItems: 'center', gap: spacing.ms },
  controlsStacked: { flexDirection: 'column', alignItems: 'stretch', gap: spacing.ms },
  grow: { flex: 1 },
  track: { flexDirection: 'row', minHeight: 44, borderRadius: 999, padding: 4 },
  segment: { flex: 1, alignItems: 'center', justifyContent: 'center', borderRadius: 999, paddingHorizontal: spacing.sm },
  filtersButton: {
    flexDirection: 'row', alignItems: 'center', justifyContent: 'center', gap: 6,
    minHeight: 44, borderRadius: 999, borderWidth: StyleSheet.hairlineWidth, paddingHorizontal: spacing.md,
  },
  count: { minWidth: 18, height: 18, borderRadius: 9, alignItems: 'center', justifyContent: 'center', paddingHorizontal: 5 },
  chips: { flexDirection: 'row', flexWrap: 'wrap', gap: spacing.sm },
  chip: {
    flexDirection: 'row', alignItems: 'center', gap: spacing.xs, maxWidth: '100%',
    minHeight: 32, borderRadius: 999, borderWidth: 1, paddingLeft: spacing.ms, paddingRight: spacing.xs,
  },
  chipLabel: { flexShrink: 1 },
  chipClear: { width: 28, height: 28, alignItems: 'center', justifyContent: 'center' },
});
```

- [ ] **Step 4: Run the test**

Run: `zsh S/m22.sh jest src/components/ledger/LedgerControls.test.tsx`
Expected: PASS, 7 tests.

- [ ] **Step 5: Types, lint, commit**

Run: `zsh S/m22.sh typecheck`, `zsh S/m22.sh lint`. Expected: clean.

```bash
git -C /Users/sid/Downloads/finora/.claude/worktrees/mobile-dashboard-cards add mobile/src/components/ledger/LedgerControls.tsx mobile/src/components/ledger/LedgerControls.test.tsx
git -C /Users/sid/Downloads/finora/.claude/worktrees/mobile-dashboard-cards commit -m "feat(mobile): search, type switch, Filters button and active chips for Transactions"
```

---

### Task 6: Filters sheet

**Files:**
- Create: `mobile/src/components/ledger/LedgerFiltersSheet.tsx`
- Create: `mobile/src/components/ledger/LedgerFiltersSheet.test.tsx`

**Interfaces:**
- Produces:
  - `STATUS_FILTERS: ReconciliationStatus[]` (moved from `LedgerScreen.tsx`, same six in the same order)
  - `type StatusFilter = 'ALL' | ReconciliationStatus`
  - `statusFilterLabel(s: StatusFilter): string` ("Any status", "OK", then the match labels)
  - `LedgerFiltersSheet({ status, onStatus, dateFrom, dateTo, onDateFrom, onDateTo, internationalOnly, onInternational, onClearAll, resultCount, onClose })` with `status: StatusFilter`, `onStatus: (s: StatusFilter) => void`, `dateFrom: string | null`, `dateTo: string | null`, `onDateFrom`/`onDateTo: (v: string | null) => void`, `internationalOnly: boolean`, `onInternational: (on: boolean) => void`, `onClearAll: () => void`, `resultCount: number | null`, `onClose: () => void`. The caller mounts it only while it is open.
  - Spoken names: "Filter by status: <label>"; the two `DateField`s' own; "Show only international transactions" (role switch); "Clear all filters"; "Close filters". Test id `filters-done`.

- [ ] **Step 1: Write the failing test**

```tsx
import { fireEvent, render, screen } from '@testing-library/react-native';
import { LedgerFiltersSheet, STATUS_FILTERS, statusFilterLabel } from './LedgerFiltersSheet';
import { ThemeProvider } from '../../theme';

const handlers = {
  onStatus: jest.fn(), onDateFrom: jest.fn(), onDateTo: jest.fn(), onInternational: jest.fn(),
  onClearAll: jest.fn(), onClose: jest.fn(),
};
function show(over: Partial<Parameters<typeof LedgerFiltersSheet>[0]> = {}) {
  return render(
    <ThemeProvider>
      <LedgerFiltersSheet status="ALL" dateFrom={null} dateTo={null} internationalOnly={false} resultCount={21}
        {...handlers} {...over} />
    </ThemeProvider>
  );
}
beforeEach(() => jest.clearAllMocks());

describe('statusFilterLabel', () => {
  it('names the eight choices as the chips did', () => {
    expect((['ALL', 'OK', ...STATUS_FILTERS] as const).map(statusFilterLabel)).toEqual([
      'Any status', 'OK', 'Duplicate', 'Transfer', 'Refund', 'Reversed', 'Investment', 'Superseded',
    ]);
  });
});

describe('LedgerFiltersSheet', () => {
  it('offers the eight statuses, one selected, with their old spoken names', () => {
    show({ status: 'TRANSFER' });
    expect(screen.getByLabelText('Filter by status: Transfer')).toBeSelected();
    expect(screen.getByLabelText('Filter by status: Any status')).not.toBeSelected();
    fireEvent.press(screen.getByLabelText('Filter by status: OK'));
    expect(handlers.onStatus).toHaveBeenCalledWith('OK');
    expect(screen.getAllByLabelText(/^Filter by status: /)).toHaveLength(8);
  });

  it('holds the From and To date fields', () => {
    show({ dateFrom: '2026-09-01' });
    expect(screen.getByLabelText(/^From: .*\. Change$/)).toBeTruthy();
    expect(screen.getByLabelText('To: not set. Choose a date')).toBeTruthy();
    fireEvent.press(screen.getByLabelText('Clear From'));
    expect(handlers.onDateFrom).toHaveBeenCalledWith(null);
  });

  it('turns International on and off from one switch row', () => {
    show();
    const row = screen.getByLabelText('Show only international transactions');
    expect(row).toHaveAccessibilityState({ checked: false });
    fireEvent.press(row);
    expect(handlers.onInternational).toHaveBeenCalledWith(true);
  });

  it('says the switch is on when it is, and turns it off', () => {
    show({ internationalOnly: true });
    const row = screen.getByLabelText('Show only international transactions');
    expect(row).toHaveAccessibilityState({ checked: true });
    fireEvent.press(row);
    expect(handlers.onInternational).toHaveBeenCalledWith(false);
  });

  it('clears everything from one control', () => {
    show({ status: 'REFUND' });
    fireEvent.press(screen.getByLabelText('Clear all filters'));
    expect(handlers.onClearAll).toHaveBeenCalledTimes(1);
  });

  it.each([[21, 'Show 21 transactions'], [1, 'Show 1 transaction'], [0, 'Show 0 transactions'], [1234567, 'Show 12,34,567 transactions']])(
    'closes from a button that says how many match: %i', (count, text) => {
      show({ resultCount: count });
      expect(screen.getByTestId('filters-done')).toHaveTextContent(text);
      fireEvent.press(screen.getByTestId('filters-done'));
      expect(handlers.onClose).toHaveBeenCalledTimes(1);
    });

  it('says Done while the count is not known', () => {
    show({ resultCount: null });
    expect(screen.getByTestId('filters-done')).toHaveTextContent('Done');
  });

  it('closes from the area outside the sheet', () => {
    show();
    fireEvent.press(screen.getByLabelText('Close filters'));
    expect(handlers.onClose).toHaveBeenCalledTimes(1);
  });
});
```

- [ ] **Step 2: Run it and see it fail**

Run: `zsh S/m22.sh jest src/components/ledger/LedgerFiltersSheet.test.tsx`
Expected: FAIL, "Cannot find module './LedgerFiltersSheet'".

- [ ] **Step 3: Write the component**

```tsx
import { Pressable, ScrollView, StyleSheet, Switch, Text, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { AppModal } from '../AppModal';
import { DateField } from '../DateField';
import { GlassSurface } from '../GlassSurface';
import { reconciliationBadge } from '../../lib/reconciliationBadge';
import { radius, spacing, typography, useTheme } from '../../theme';
import type { ReconciliationStatus } from '../../types';

export type StatusFilter = 'ALL' | ReconciliationStatus;

// One entry per match status, in the order they are offered. 'OK' is offered too, worded on its
// own: reconciliationBadge returns nothing for it, but "only the ordinary rows" is a real filter
// for someone working through flagged ones.
export const STATUS_FILTERS: ReconciliationStatus[] = [
  'DUPLICATE', 'TRANSFER', 'REFUND', 'REVERSAL', 'INVESTMENT_TRANSFER', 'SUPERSEDED',
];

export function statusFilterLabel(s: StatusFilter): string {
  return s === 'ALL' ? 'Any status' : (reconciliationBadge(s)?.label ?? 'OK');
}

/**
 * The rarely used filters: status, date range, international. Each applies as it is changed, as
 * the chips it replaces did; the button at the bottom only closes the sheet.
 */
export function LedgerFiltersSheet({
  status, onStatus, dateFrom, dateTo, onDateFrom, onDateTo, internationalOnly, onInternational, onClearAll,
  resultCount, onClose,
}: {
  status: StatusFilter;
  onStatus: (s: StatusFilter) => void;
  dateFrom: string | null;
  dateTo: string | null;
  onDateFrom: (v: string | null) => void;
  onDateTo: (v: string | null) => void;
  internationalOnly: boolean;
  onInternational: (on: boolean) => void;
  onClearAll: () => void;
  /** How many transactions match now; null while that is loading or the request failed. */
  resultCount: number | null;
  onClose: () => void;
}) {
  const c = useTheme();
  const insets = useSafeAreaInsets();
  const done = resultCount === null
    ? 'Done'
    : `Show ${resultCount.toLocaleString('en-IN')} ${resultCount === 1 ? 'transaction' : 'transactions'}`;

  return (
    <AppModal visible transparent animationType="slide" onRequestClose={onClose}>
      <View style={styles.flex}>
        <Pressable style={styles.backdrop} onPress={onClose} accessibilityLabel="Close filters" />
        <GlassSurface style={[styles.sheet, { paddingBottom: insets.bottom + spacing.ml }]}>
          <View style={styles.head}>
            <Text accessibilityRole="header" style={[typography.cardTitle, { color: c.ink }]}>Filters</Text>
            <Pressable onPress={onClearAll} accessibilityRole="button" accessibilityLabel="Clear all filters" style={styles.clearAll}>
              <Text style={[typography.labelM, { color: c.primary }]}>Clear all</Text>
            </Pressable>
          </View>

          {/* Scrolls: the date picker opens in place on iOS and is taller than what is left. */}
          <ScrollView style={styles.scroll} contentContainerStyle={styles.body} keyboardShouldPersistTaps="handled">
            <Text style={[typography.eyebrow, { color: c.mutedInk }]}>Status</Text>
            <View style={styles.statuses}>
              {(['ALL', 'OK', ...STATUS_FILTERS] as StatusFilter[]).map((s) => {
                const selected = status === s;
                const label = statusFilterLabel(s);
                return (
                  <Pressable
                    key={s}
                    onPress={() => onStatus(s)}
                    // The chip is 36 high; this makes the target 44.
                    hitSlop={{ top: 4, bottom: 4 }}
                    accessibilityRole="button"
                    accessibilityState={{ selected }}
                    accessibilityLabel={`Filter by status: ${label}`}
                    style={[styles.status, { borderColor: selected ? c.primary : c.border }, selected && { backgroundColor: c.primary }]}
                  >
                    <Text style={[typography.labelS, { color: selected ? c.onPrimary : c.mutedInk }]}>{label}</Text>
                  </Pressable>
                );
              })}
            </View>

            <Text style={[typography.eyebrow, styles.section, { color: c.mutedInk }]}>Date range</Text>
            {/* One under the other, not side by side: the iOS calendar opens inside its field. */}
            <DateField label="From" value={dateFrom} onChange={onDateFrom} />
            <DateField label="To" value={dateTo} onChange={onDateTo} />

            <Pressable
              onPress={() => onInternational(!internationalOnly)}
              accessibilityRole="switch"
              accessibilityState={{ checked: internationalOnly }}
              accessibilityLabel="Show only international transactions"
              style={styles.switchRow}
            >
              <Text style={[typography.labelM, styles.switchLabel, { color: c.ink }]}>International only</Text>
              {/* The row is the control; the switch is its picture. */}
              <View pointerEvents="none" accessibilityElementsHidden importantForAccessibility="no-hide-descendants">
                <Switch value={internationalOnly} trackColor={{ true: c.primary, false: c.border }} />
              </View>
            </Pressable>
          </ScrollView>

          <Pressable testID="filters-done" onPress={onClose} accessibilityRole="button" style={[styles.done, { backgroundColor: c.primary }]}>
            <Text style={[typography.labelM, { color: c.onPrimary }]}>{done}</Text>
          </Pressable>
        </GlassSurface>
      </View>
    </AppModal>
  );
}

const styles = StyleSheet.create({
  flex: { flex: 1 },
  backdrop: { flex: 1, backgroundColor: 'rgba(0,0,0,0.35)' },
  sheet: {
    maxHeight: '90%', borderTopLeftRadius: radius.hero, borderTopRightRadius: radius.hero,
    paddingHorizontal: spacing.ml, paddingTop: spacing.ml,
  },
  head: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', marginBottom: spacing.ms },
  clearAll: { minHeight: 44, justifyContent: 'center' },
  scroll: { flexGrow: 0 },
  body: { paddingBottom: spacing.md },
  statuses: { flexDirection: 'row', flexWrap: 'wrap', gap: spacing.sm, marginTop: spacing.sm },
  status: { minHeight: 36, justifyContent: 'center', borderRadius: 999, borderWidth: 1, paddingHorizontal: 14 },
  section: { marginTop: spacing.ml, marginBottom: spacing.sm },
  switchRow: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: spacing.ms, minHeight: 48, marginTop: spacing.sm },
  switchLabel: { flexShrink: 1 },
  done: { minHeight: 50, borderRadius: 999, alignItems: 'center', justifyContent: 'center', marginTop: spacing.sm },
});
```

- [ ] **Step 4: Run the test**

Run: `zsh S/m22.sh jest src/components/ledger/LedgerFiltersSheet.test.tsx`
Expected: PASS, 12 tests. The scrim alpha 0.35 is already one of the values `glassContrast.test.ts` measures.

- [ ] **Step 5: Types, lint, commit**

Run: `zsh S/m22.sh typecheck`, `zsh S/m22.sh lint`. Expected: clean.

```bash
git -C /Users/sid/Downloads/finora/.claude/worktrees/mobile-dashboard-cards add mobile/src/components/ledger/LedgerFiltersSheet.tsx mobile/src/components/ledger/LedgerFiltersSheet.test.tsx
git -C /Users/sid/Downloads/finora/.claude/worktrees/mobile-dashboard-cards commit -m "feat(mobile): filters sheet for the Transactions tab"
```

---

### Task 7: Title block and pinned bar

**Files:**
- Create: `mobile/src/components/ledger/LedgerTitleBar.tsx`
- Create: `mobile/src/components/ledger/LedgerTitleBar.test.tsx`

**Interfaces:**
- Produces:
  - `PINNED_BAR_HEIGHT = 54`, `PINNED_BAR_SOLID_AFTER = 24`
  - `LedgerTitleBlock({ countLabel, onAdd, onHeight }: { countLabel: string; onAdd: () => void; onHeight: (height: number) => void })`
  - `LedgerPinnedBar({ solid, compact, topInset, onSearch, onAdd }: { solid: boolean; compact: boolean; topInset: number; onSearch: () => void; onAdd: () => void })`
  - Test ids: `ledger-title-block`, `ledger-pinned-bar`, `ledger-pinned-fill`, `ledger-pinned-blur`, `ledger-pinned-contents`. (`ledger-pinned-glass` exists only in the test's stand-in for the glass view.)

How the bar's surface is drawn, by case:

| Case | Surface |
|---|---|
| Reduce Transparency on or not yet known; Android | A plain view in the solid card colour with a bottom edge, faded in and out by its own opacity |
| iOS with Liquid Glass | A `GlassView` whose `glassEffectStyle` goes between `none` and `regular` with the library's own animation (0.15 s), and the tint as a child view that fades |
| iOS without Liquid Glass (16.4 to 25) | A `BlurView` mounted only while solid, appearing at once. A blur view cannot be faded by opacity either, and there is no simulator here below iOS 26 to check an animated blur on, so this case does not animate |

- [ ] **Step 1: Write the failing test**

```tsx
import { Platform } from 'react-native';
import { fireEvent, render, screen } from '@testing-library/react-native';
import { LedgerPinnedBar, LedgerTitleBlock } from './LedgerTitleBar';
import { ThemeProvider } from '../../theme';
import { light } from '../../theme/palette';

jest.mock('../../lib/useReduceTransparency', () => ({ useReduceTransparency: jest.fn(() => false) }));
const { useReduceTransparency } = jest.requireMock('../../lib/useReduceTransparency');
jest.mock('expo-glass-effect', () => {
  const { View } = require('react-native');
  return {
    GlassView: (props: object) => <View testID="ledger-pinned-glass" {...props} />,
    isLiquidGlassAvailable: jest.fn(() => true),
    isGlassEffectAPIAvailable: jest.fn(() => true),
  };
});
const glass = jest.requireMock('expo-glass-effect');

const originalOS = Platform.OS;
afterEach(() => {
  (Platform as { OS: string }).OS = originalOS;
  useReduceTransparency.mockReturnValue(false);
  glass.isLiquidGlassAvailable.mockReturnValue(true);
  jest.clearAllMocks();
});

describe('LedgerTitleBlock', () => {
  it('shows the title as a heading, the count, and the add button', () => {
    const onAdd = jest.fn();
    render(<ThemeProvider><LedgerTitleBlock countLabel="392 transactions" onAdd={onAdd} onHeight={jest.fn()} /></ThemeProvider>);
    expect(screen.getByRole('header', { name: 'Transactions' })).toBeTruthy();
    expect(screen.getByText('392 transactions')).toBeTruthy();
    fireEvent.press(screen.getByLabelText('Add transaction'));
    expect(onAdd).toHaveBeenCalledTimes(1);
  });

  it('reports its height, which decides when the pinned title appears', () => {
    const onHeight = jest.fn();
    render(<ThemeProvider><LedgerTitleBlock countLabel="" onAdd={jest.fn()} onHeight={onHeight} /></ThemeProvider>);
    fireEvent(screen.getByTestId('ledger-title-block'), 'layout', { nativeEvent: { layout: { height: 61 } } });
    expect(onHeight).toHaveBeenCalledWith(61);
  });
});

describe('LedgerPinnedBar', () => {
  const handlers = { onSearch: jest.fn(), onAdd: jest.fn() };
  const show = (solid: boolean, compact: boolean) =>
    render(<ThemeProvider><LedgerPinnedBar solid={solid} compact={compact} topInset={59} {...handlers} /></ThemeProvider>);

  it('covers the status bar area and 54 points under it', () => {
    show(false, false);
    expect(screen.getByTestId('ledger-pinned-bar')).toHaveStyle({ position: 'absolute', top: 0, height: 113 });
  });

  it('at rest lets touches through and hides its buttons from everyone', () => {
    show(false, false);
    expect(screen.getByTestId('ledger-pinned-bar').props.pointerEvents).toBe('none');
    expect(screen.queryByLabelText('Search transactions')).toBeNull();
    expect(screen.queryByLabelText('Add transaction')).toBeNull();
  });

  it('once the large title has gone, offers the title, search and add', () => {
    show(true, true);
    expect(screen.getByText('Transactions')).toBeTruthy();
    fireEvent.press(screen.getByLabelText('Search transactions'));
    fireEvent.press(screen.getByLabelText('Add transaction'));
    expect(handlers.onSearch).toHaveBeenCalledTimes(1);
    expect(handlers.onAdd).toHaveBeenCalledTimes(1);
    expect(screen.getByTestId('ledger-pinned-bar').props.pointerEvents).toBe('box-none');
  });

  it('stops the pinned title growing past 1.3 times', () => {
    show(true, true);
    expect(screen.getByText('Transactions').props.maxFontSizeMultiplier).toBe(1.3);
  });

  it('on Liquid Glass changes the glass style with the library animation, never by opacity', () => {
    const view = show(false, false);
    expect(screen.getByTestId('ledger-pinned-glass').props.glassEffectStyle).toEqual({ style: 'none', animate: true, animationDuration: 0.15 });
    view.rerender(<ThemeProvider><LedgerPinnedBar solid compact={false} topInset={59} {...handlers} /></ThemeProvider>);
    const g = screen.getByTestId('ledger-pinned-glass');
    expect(g.props.glassEffectStyle).toEqual({ style: 'regular', animate: true, animationDuration: 0.15 });
    expect(g).not.toHaveStyle({ opacity: 0 });
    expect(screen.getByTestId('ledger-pinned-bar')).not.toHaveStyle({ opacity: 0 });
  });

  it('is a solid card-coloured bar under Reduce Transparency, with no glass view', () => {
    useReduceTransparency.mockReturnValue(true);
    show(true, false);
    expect(screen.queryByTestId('ledger-pinned-glass')).toBeNull();
    expect(screen.getByTestId('ledger-pinned-fill')).toHaveStyle({ backgroundColor: light.card });
  });

  it('is a solid bar on Android, where a see-through fill would show the list through it', () => {
    (Platform as { OS: string }).OS = 'android';
    show(true, false);
    expect(screen.queryByTestId('ledger-pinned-glass')).toBeNull();
    expect(screen.getByTestId('ledger-pinned-fill')).toHaveStyle({ backgroundColor: light.card });
  });

  it('on iOS without Liquid Glass shows a blur only while solid', () => {
    glass.isLiquidGlassAvailable.mockReturnValue(false);
    const view = show(false, false);
    expect(screen.queryByTestId('ledger-pinned-blur')).toBeNull();
    view.rerender(<ThemeProvider><LedgerPinnedBar solid compact={false} topInset={59} {...handlers} /></ThemeProvider>);
    expect(screen.getByTestId('ledger-pinned-blur')).toBeTruthy();
  });
});
```

- [ ] **Step 2: Run it and see it fail**

Run: `zsh S/m22.sh jest src/components/ledger/LedgerTitleBar.test.tsx`
Expected: FAIL, "Cannot find module './LedgerTitleBar'".

- [ ] **Step 3: Write the component**

```tsx
import { useEffect } from 'react';
import { Platform, Pressable, StyleSheet, Text, View } from 'react-native';
import Animated, { useAnimatedStyle, useReducedMotion, useSharedValue, withTiming } from 'react-native-reanimated';
import { BlurView } from 'expo-blur';
import { GlassView, isGlassEffectAPIAvailable, isLiquidGlassAvailable } from 'expo-glass-effect';
import Ionicons from '@expo/vector-icons/Ionicons';
import { useGlassSurfaceStyle } from '../GlassSurface';
import { useReduceTransparency } from '../../lib/useReduceTransparency';
import { spacing, typography, useTheme } from '../../theme';
import { dark } from '../../theme/palette';
import { glassFill } from '../../theme/glass';

export const PINNED_BAR_HEIGHT = 54;
/** How far the list moves before the bar turns solid. */
export const PINNED_BAR_SOLID_AFTER = 24;
const FADE_MS = 150;

function RoundButton({ icon, label, onPress }: { icon: 'add' | 'search'; label: string; onPress: () => void }) {
  const c = useTheme();
  const glass = useGlassSurfaceStyle();
  return (
    <Pressable onPress={onPress} accessibilityRole="button" accessibilityLabel={label} style={[styles.round, glass]}>
      <Ionicons name={icon} size={22} color={c.ink} />
    </Pressable>
  );
}

/** The title, the count under it and the add button. Part of the list's header: it scrolls away. */
export function LedgerTitleBlock({
  countLabel, onAdd, onHeight,
}: { countLabel: string; onAdd: () => void; onHeight: (height: number) => void }) {
  const c = useTheme();
  return (
    <View testID="ledger-title-block" style={styles.block} onLayout={(e) => onHeight(e.nativeEvent.layout.height)}>
      <View style={styles.blockText}>
        <Text accessibilityRole="header" style={[typography.screenTitle, { color: c.ink }]}>Transactions</Text>
        {/* Always rendered, so the blocks under it do not jump when the count arrives. */}
        <Text style={[typography.bodyS, { color: c.mutedInk }]}>{countLabel}</Text>
      </View>
      <RoundButton icon="add" label="Add transaction" onPress={onAdd} />
    </View>
  );
}

/**
 * The bar's surface. Clear while the list is at rest, solid once it has moved, so nothing slides
 * behind the clock or is cut by a hard edge. A glass or blur view does not draw when it, or a view
 * above it, has an opacity below one (Expo's documented limit, from UIVisualEffectView), so glass
 * is never faded that way here: Liquid Glass animates its own style, and the tint over it is a
 * child, which may fade freely.
 */
function PinnedSurface({ solid }: { solid: boolean }) {
  const c = useTheme();
  const reducedMotion = useReducedMotion();
  const opaque = useReduceTransparency() !== false || Platform.OS !== 'ios';
  const shown = useSharedValue(solid ? 1 : 0);
  // Reanimated's timing follows the system's Reduce Motion setting and becomes immediate under it.
  useEffect(() => { shown.set(withTiming(solid ? 1 : 0, { duration: FADE_MS })); }, [solid, shown]);
  const fade = useAnimatedStyle(() => ({ opacity: shown.get() }));

  if (opaque) {
    return (
      <Animated.View
        testID="ledger-pinned-fill"
        pointerEvents="none"
        style={[StyleSheet.absoluteFill, styles.edge, { backgroundColor: c.card, borderBottomColor: c.border }, fade]}
      />
    );
  }

  const tint = { backgroundColor: glassFill(c), borderBottomColor: c.glassEdge };
  if (isLiquidGlassAvailable() && isGlassEffectAPIAvailable()) {
    return (
      <GlassView
        pointerEvents="none"
        colorScheme={c === dark ? 'dark' : 'light'}
        glassEffectStyle={{ style: solid ? 'regular' : 'none', animate: !reducedMotion, animationDuration: FADE_MS / 1000 }}
        style={StyleSheet.absoluteFill}
      >
        <Animated.View testID="ledger-pinned-fill" pointerEvents="none" style={[StyleSheet.absoluteFill, styles.edge, tint, fade]} />
      </GlassView>
    );
  }
  return solid ? (
    <BlurView
      testID="ledger-pinned-blur"
      pointerEvents="none"
      intensity={60}
      tint={c === dark ? 'systemThinMaterialDark' : 'systemThinMaterialLight'}
      style={StyleSheet.absoluteFill}
    >
      <View testID="ledger-pinned-fill" pointerEvents="none" style={[StyleSheet.absoluteFill, styles.edge, tint]} />
    </BlurView>
  ) : null;
}

/**
 * Sits over the top of the list, from the very top of the screen to 54 points under the status
 * bar. `solid` is the surface; `compact` is the small title and the two buttons, which appear once
 * the large title has scrolled under the bar.
 */
export function LedgerPinnedBar({
  solid, compact, topInset, onSearch, onAdd,
}: { solid: boolean; compact: boolean; topInset: number; onSearch: () => void; onAdd: () => void }) {
  const c = useTheme();
  const shown = useSharedValue(compact ? 1 : 0);
  useEffect(() => { shown.set(withTiming(compact ? 1 : 0, { duration: FADE_MS })); }, [compact, shown]);
  const fade = useAnimatedStyle(() => ({ opacity: shown.get() }));

  return (
    <View
      testID="ledger-pinned-bar"
      // Never catches a touch itself; its buttons do, and only while they are shown.
      pointerEvents={compact ? 'box-none' : 'none'}
      style={[styles.bar, { height: topInset + PINNED_BAR_HEIGHT, paddingTop: topInset }]}
    >
      <PinnedSurface solid={solid} />
      <Animated.View
        testID="ledger-pinned-contents"
        pointerEvents={compact ? 'box-none' : 'none'}
        accessibilityElementsHidden={!compact}
        importantForAccessibility={compact ? 'auto' : 'no-hide-descendants'}
        style={[styles.contents, fade]}
      >
        <Text style={[typography.cardTitle, styles.barTitle, { color: c.ink }]} numberOfLines={1} maxFontSizeMultiplier={1.3}>
          Transactions
        </Text>
        <RoundButton icon="search" label="Search transactions" onPress={onSearch} />
        <RoundButton icon="add" label="Add transaction" onPress={onAdd} />
      </Animated.View>
    </View>
  );
}

const styles = StyleSheet.create({
  block: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: spacing.ms, paddingTop: spacing.sm },
  blockText: { flex: 1 },
  round: { width: 44, height: 44, borderRadius: 22, borderWidth: StyleSheet.hairlineWidth, alignItems: 'center', justifyContent: 'center' },
  bar: { position: 'absolute', top: 0, left: 0, right: 0 },
  edge: { borderBottomWidth: StyleSheet.hairlineWidth },
  contents: { flex: 1, flexDirection: 'row', alignItems: 'center', gap: spacing.sm, paddingHorizontal: spacing.ml },
  barTitle: { flex: 1 },
});
```

- [ ] **Step 4: Run the test**

Run: `zsh S/m22.sh jest src/components/ledger/LedgerTitleBar.test.tsx`
Expected: PASS, 10 tests. Reanimated runs in its test mode (`setUpTests()` in `src/test/setup.ts`), so the animated styles resolve without a native runtime. If `queryByLabelText` still finds the hidden buttons at rest, the cause is RNTL's hidden-element rule, not the component: pass `{ includeHiddenElements: false }` explicitly and keep the assertion.

- [ ] **Step 5: Types, lint, commit**

Run: `zsh S/m22.sh typecheck`, `zsh S/m22.sh lint`. Expected: clean.

```bash
git -C /Users/sid/Downloads/finora/.claude/worktrees/mobile-dashboard-cards add mobile/src/components/ledger/LedgerTitleBar.tsx mobile/src/components/ledger/LedgerTitleBar.test.tsx
git -C /Users/sid/Downloads/finora/.claude/worktrees/mobile-dashboard-cards commit -m "feat(mobile): scrolling title and pinned bar for the Transactions tab"
```

---

### Task 8: Put the screen together

**Files:**
- Modify: `mobile/src/screens/LedgerScreen.tsx`
- Modify: `mobile/src/screens/LedgerScreen.test.tsx`

**Interfaces:**
- Consumes: everything Tasks 1 and 3 to 7 produce, with the names given there.
- Produces: no new export. `STATUS_FILTERS` leaves this file (it was not exported).

- [ ] **Step 1: Change the tests first**

In `LedgerScreen.test.tsx`:

1. The fixture gets a merchant shaped like the server's, so a test that sets only `description` still finds it as the title. Replace the `merchant` line of `txn()` and the return:

```tsx
function txn(over: Partial<Transaction> = {}): Transaction {
  const description = over.description ?? 'Grocery run';
  return {
    id: 't-1',
    date: '2026-07-14',
    description,
    // As the server derives it: the description in lower case, punctuation gone, four words at most.
    merchant: description.toLowerCase().replace(/[^a-z0-9]+/g, ' ').trim().split(' ').slice(0, 4).join(' '),
    amount: -1250,
    type: 'EXPENSE',
    category: 'Food',
    accountId: 'a-1',
    accountName: 'HDFC Savings',
    needsCategoryReview: false,
    recurring: false,
    categoryManuallySet: false,
    ...over,
  } as Transaction;
}
```

2. Add a helper under `renderScreen` and use it at the start of every test in `status filter (Phase 4)` and `manual date-range filter (Phase 5)` that presses a status, the international control or a date field (right after the first `await`):

```tsx
/** Status, date range and International live in the Filters sheet. */
function openFilters() {
  fireEvent.press(screen.getByLabelText(/^Filters/));
}
```

   In `is cleared by a brand new drill-through…` the sheet is opened once, before the first date press; it stays open across the rerender.

3. Replace the whole `describe('status badges (Phase 5)')` block with:

```tsx
describe('row tags', () => {
  it('shows no tag for an ordinary row: Categorized and Reviewed are the normal state', async () => {
    transactions.search.mockResolvedValue(page([txn(), txn({ id: 't-2', description: 'Salary', categoryManuallySet: true })]) as never);

    renderScreen();

    await screen.findByText('Grocery run');
    expect(screen.queryByText('Categorized')).toBeNull();
    expect(screen.queryByText('Reviewed')).toBeNull();
  });

  it('marks an international transaction, and says so to a screen reader', async () => {
    transactions.search.mockResolvedValue(page([
      txn({ id: 't-intl', international: true, foreignCurrency: 'USD', foreignAmount: 12.5 }),
      txn({ id: 't-home', description: 'Salary' }),
    ]) as never);

    renderScreen();

    expect(await screen.findByTestId('international-badge-t-intl')).toBeTruthy();
    expect(screen.queryByTestId('international-badge-t-home')).toBeNull();
    expect(screen.getByLabelText(/Grocery run.*, international$/)).toBeTruthy();
  });

  it('shows Needs Review and Recurring at once: independent facts, not a priority chain', async () => {
    transactions.search.mockResolvedValue(page([txn({ needsCategoryReview: true, recurring: true })]) as never);

    renderScreen();

    await screen.findByText('Needs Review');
    expect(screen.getByText('Recurring')).toBeTruthy();
  });

  it('shows Bank Correction only when the bank changed the row', async () => {
    transactions.search.mockResolvedValue(page([
      txn({ pendingBankCorrection: true }), txn({ id: 't-2', description: 'Salary' }),
    ]) as never);

    renderScreen();

    expect(await screen.findAllByText('Bank Correction')).toHaveLength(1);
  });

  it('names every tag in the row\'s spoken name', async () => {
    transactions.search.mockResolvedValue(page([txn({ recurring: true })]) as never);

    renderScreen();

    expect(await screen.findByLabelText(/Recurring/)).toBeTruthy();
  });
});
```

4. In `the header count`, the assertion becomes `expect(screen.queryByText(/^0 /)).toBeNull();` and a test is added:

```tsx
  it('counts in words, singular for one', async () => {
    transactions.search.mockResolvedValue(page([txn()], { totalElements: 392 }) as never);
    const many = renderScreen();
    expect(await screen.findByText('392 transactions')).toBeTruthy();
    many.unmount();

    transactions.search.mockResolvedValue(page([txn()]) as never);
    renderScreen();
    expect(await screen.findByText('1 transaction')).toBeTruthy();
  });
```

5. Add a new block at the end of the file:

```tsx
describe('the redesigned list', () => {
  it('titles a row by the payee name with softened capitals, not by the narration', async () => {
    transactions.search.mockResolvedValue(page([
      txn({ description: 'UPI/DR/600000000001/RAHUL SH/YESB/rahulsh@ybl', merchant: 'rahul sh' }),
    ]) as never);

    renderScreen();

    expect(await screen.findByText('Rahul Sh')).toBeTruthy();
    expect(screen.queryByText(/UPI\/DR/)).toBeNull();
  });

  it('heads each day with a short date and no longer prints the date on rows', async () => {
    transactions.search.mockResolvedValue(page([txn({ date: '2026-07-14' })]) as never);

    renderScreen();

    expect(await screen.findByRole('header', { name: dayHeading('2026-07-14', new Date()) })).toBeTruthy();
    expect(screen.queryByText(/2026-07-14/)).toBeNull();
  });

  it('lists and counts once a row the server returned on two pages', async () => {
    const repeated = txn({ id: 't-rep', description: 'Rent', amount: -100 });
    transactions.search
      .mockResolvedValueOnce(page([txn({ id: 't-a', description: 'Tea', amount: -10 }), repeated], { totalPages: 2, totalElements: 3 }) as never)
      .mockResolvedValueOnce(page([repeated, txn({ id: 't-b', description: 'Bus', amount: -5 })], { page: 1, totalPages: 2, totalElements: 3 }) as never);

    renderScreen();
    await screen.findByText('Rent');
    fireEvent(screen.getByTestId('ledger-list'), 'endReached');

    expect(await screen.findByText('Bus')).toBeTruthy();
    expect(screen.getAllByText('Rent')).toHaveLength(1);
    // 10 + 100 + 5, the repeated 100 counted once.
    expect(screen.getByText('-₹115')).toBeTruthy();
  });

  it('says on the Filters button how many of its filters are on, and shows a chip for each', async () => {
    transactions.search.mockResolvedValue(page([]) as never);
    renderScreen();
    await waitFor(() => expect(transactions.search).toHaveBeenCalled());

    openFilters();
    fireEvent.press(screen.getByLabelText('Filter by status: Transfer'));
    fireEvent.press(screen.getByLabelText('Show only international transactions'));

    expect(screen.getByLabelText('Filters, 2 on')).toBeTruthy();
    expect(screen.getByLabelText('Clear filter: Transfer')).toBeTruthy();
    expect(screen.getByLabelText('Clear filter: International')).toBeTruthy();
  });

  it('turns a filter off from its chip', async () => {
    transactions.search.mockResolvedValue(page([]) as never);
    renderScreen();
    await waitFor(() => expect(transactions.search).toHaveBeenCalled());
    openFilters();
    fireEvent.press(screen.getByLabelText('Filter by status: Transfer'));
    await waitFor(() => expect(transactions.search).toHaveBeenCalledWith(expect.objectContaining({ status: 'TRANSFER' })));

    fireEvent.press(screen.getByLabelText('Clear filter: Transfer'));

    await waitFor(() => expect(transactions.search).toHaveBeenLastCalledWith(expect.objectContaining({ status: undefined })));
    expect(screen.getByLabelText('Filters')).toBeTruthy();
  });

  it('Clear all turns off status, dates and international, and leaves search, type and an arriving filter alone', async () => {
    mockRouteParams = { filters: { label: 'Dining', nonce: 7, categoryId: 'c-1' } };
    transactions.search.mockResolvedValue(page([]) as never);
    renderScreen();
    await waitFor(() => expect(transactions.search).toHaveBeenCalled());
    fireEvent.changeText(screen.getByLabelText('Search transactions'), 'tea');
    fireEvent.press(screen.getByLabelText('Filter: expense'));
    openFilters();
    fireEvent.press(screen.getByLabelText('Filter by status: Refund'));
    fireEvent.press(screen.getByLabelText('Show only international transactions'));

    fireEvent.press(screen.getByLabelText('Clear all filters'));

    await waitFor(() => expect(transactions.search).toHaveBeenLastCalledWith(expect.objectContaining({
      status: undefined, international: undefined, dateFrom: undefined, dateTo: undefined,
      type: 'EXPENSE', keyword: 'tea', categoryId: 'c-1',
    })));
    expect(screen.getByLabelText('Clear filter: Dining')).toBeTruthy();
  });

  it('closes the sheet from a button that says how many match', async () => {
    transactions.search.mockResolvedValue(page([txn(), txn({ id: 't-2', description: 'Salary' })]) as never);
    renderScreen();
    await screen.findByText('Grocery run');
    openFilters();

    expect(screen.getByTestId('filters-done')).toHaveTextContent('Show 2 transactions');
    fireEvent.press(screen.getByTestId('filters-done'));

    expect(screen.queryByTestId('filters-done')).toBeNull();
  });

  it('says Done on that button while the count is loading', async () => {
    transactions.search.mockReturnValue(new Promise(() => {}));
    renderScreen();
    openFilters();
    expect(screen.getByTestId('filters-done')).toHaveTextContent('Done');
  });

  it('shows the pinned title and its buttons only after the large title has scrolled away', async () => {
    transactions.search.mockResolvedValue(page([txn()]) as never);
    renderScreen();
    await screen.findByText('Grocery run');
    expect(screen.getAllByLabelText('Add transaction')).toHaveLength(1);

    fireEvent.scroll(screen.getByTestId('ledger-list'), { nativeEvent: { contentOffset: { y: 300 } } });

    expect(screen.getAllByLabelText('Add transaction')).toHaveLength(2);
    // The field and the pinned bar's button.
    expect(screen.getAllByLabelText('Search transactions')).toHaveLength(2);
  });

  it('puts the pinned bar away again at the top, and during a pull to refresh', async () => {
    transactions.search.mockResolvedValue(page([txn()]) as never);
    renderScreen();
    await screen.findByText('Grocery run');
    const list = screen.getByTestId('ledger-list');

    fireEvent.scroll(list, { nativeEvent: { contentOffset: { y: 300 } } });
    fireEvent.scroll(list, { nativeEvent: { contentOffset: { y: 0 } } });
    expect(screen.getAllByLabelText('Add transaction')).toHaveLength(1);

    fireEvent.scroll(list, { nativeEvent: { contentOffset: { y: -80 } } });
    expect(screen.getAllByLabelText('Add transaction')).toHaveLength(1);
  });

  it('scrolls to the top from the pinned search button', async () => {
    const scrollToOffset = jest.spyOn(FlatList.prototype, 'scrollToOffset').mockImplementation(() => {});
    transactions.search.mockResolvedValue(page([txn()]) as never);
    renderScreen();
    await screen.findByText('Grocery run');
    fireEvent.scroll(screen.getByTestId('ledger-list'), { nativeEvent: { contentOffset: { y: 300 } } });

    fireEvent.press(screen.getAllByLabelText('Search transactions')[0]);

    expect(scrollToOffset).toHaveBeenCalledWith({ offset: 0, animated: true });
    scrollToOffset.mockRestore();
  });
});
```

   with `FlatList` added to the `react-native` import and `import { dayHeading } from '../lib/ledgerRows';` added. Which of the two "Search transactions" elements is the pinned button is found by role in the test as written (`getAllByRole('button', { name: 'Search transactions' })[0]`) if index 0 turns out to be the field.

   The cursor landing in the search field is not asserted here: the test renderer has no real focus. It is checked on the simulator in Task 10.

- [ ] **Step 2: Run the screen tests and see the new ones fail**

Run: `JEST_LINES=200 zsh S/m22.sh jest src/screens/LedgerScreen.test.tsx`
Expected: FAIL. The new block and the moved-control tests fail (no "Filters" button, "Categorized" still shown, "392 total"), and the tests that were not touched still pass. Read the list of failures and confirm every one is a test changed or added in Step 1; an untouched test failing here means the fixture change broke it, and that is fixed before going on.

- [ ] **Step 3: Rewrite the screen's rendering**

In `LedgerScreen.tsx`:

1. Imports. Remove `ScrollView`, `GlassSurface`, `AnimatedNumber`, `DateField`, `MerchantLogo`, `KpiItem`, `counterpartyLabel`, `reconciliationBadge`, `fromLocalDateString`, `toLocalDateString`, `radius` where no longer used; keep `useGlassSurfaceStyle`. Add:

```tsx
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  ActivityIndicator, FlatList, Pressable, StyleSheet, Text, View,
  type NativeScrollEvent, type NativeSyntheticEvent, type TextInput,
} from 'react-native';
import { LedgerControls, type ActiveFilterChip, type TypeFilter } from '../components/ledger/LedgerControls';
import { LedgerDayCard } from '../components/ledger/LedgerDayCard';
import { LedgerFiltersSheet, statusFilterLabel, type StatusFilter } from '../components/ledger/LedgerFiltersSheet';
import { LedgerMonthCard } from '../components/ledger/LedgerMonthCard';
import { LedgerPinnedBar, LedgerTitleBlock, PINNED_BAR_SOLID_AFTER } from '../components/ledger/LedgerTitleBar';
import { buildDayPieces, mergePagesById, type DayPiece } from '../lib/ledgerRows';
import { fmtCurrency, fmtDate } from '../lib/format';
```

   Delete the local `TypeFilter`, `StatusFilter` and `STATUS_FILTERS` (now imported), the `import { groupTransactionsByDay }` added in Task 1 (the re-export line stays), and the inline `LedgerMonthSummary` function.

2. State and derived values, added after `viewingDetail`:

```tsx
  const [filtersOpen, setFiltersOpen] = useState(false);
  // The pinned bar: solid once the list has moved, its small title once the large one has gone.
  const [barSolid, setBarSolid] = useState(false);
  const [barCompact, setBarCompact] = useState(false);
  // The large title's measured height. Until it is measured, a height that is right at the
  // default text size.
  const titleHeight = useRef(60);
  const listRef = useRef<FlatList<DayPiece>>(null);
  const searchRef = useRef<TextInput>(null);
```

   Replace the `useDashboardKpis` line with `const { snapshotKpis, periodTitle, deltaLabel, deltaSpokenLabel } = useDashboardKpis(summary);`.

   Replace `txns` and `groupedRows`:

```tsx
  // Each transaction once: the server has returned the same row on two pages (see mergePagesById).
  const txns = useMemo(() => mergePagesById(data?.pages ?? []), [data]);
  const totalElements = data?.pages[0]?.totalElements ?? 0;
  // Listed as a dependency so the "Today" and "Yesterday" headings are redone once the day rolls
  // over on a tab that stays mounted; buildDayPieces reads the clock itself through its default.
  const todayDateKey = new Date().toDateString();
  // eslint-disable-next-line react-hooks/exhaustive-deps
  const pieces = useMemo(() => buildDayPieces(txns), [txns, todayDateKey]);
```

   keeping the existing explanatory comments that still apply.

3. Make the row handlers stable so memoised rows are not redrawn by an unrelated render. `handleDelete` and `confirmDelete` become `useCallback`s (`handleDelete` on `[queryClient]`, `confirmDelete` on `[handleDelete]`), with `handleDelete` declared first. Their bodies do not change.

4. Scroll, search and filter helpers, before `return`:

```tsx
  const dateRangeOn = manualDateFrom !== null || manualDateTo !== null;
  const filtersOn = (statusFilter !== 'ALL' ? 1 : 0) + (dateRangeOn ? 1 : 0) + (internationalOnly ? 1 : 0);
  // Blank while the first load runs and when it failed with nothing on screen: totalElements
  // falls back to 0 then, and a failed request is not an answer of zero.
  const countLabel = isLoading || (isError && txns.length === 0)
    ? ''
    : `${totalElements.toLocaleString('en-IN')} ${totalElements === 1 ? 'transaction' : 'transactions'}`;

  function onListScroll(e: NativeSyntheticEvent<NativeScrollEvent>) {
    const y = e.nativeEvent.contentOffset.y;
    // Setting the same value again does not render, so this costs a render only on a change.
    setBarSolid(y > PINNED_BAR_SOLID_AFTER);
    setBarCompact(y > titleHeight.current);
  }

  function focusSearch() {
    listRef.current?.scrollToOffset({ offset: 0, animated: true });
    searchRef.current?.focus();
  }

  function clearDrillThrough() {
    setActiveDrillThrough(null);
    // A keyword-only arrival has no other field this clears. Only when the box still holds the
    // seeded value: the person's own typing is never cleared by this.
    if (drillThroughKeyword !== null) {
      setKeywordInput('');
      setDrillThroughKeyword(null);
    }
  }

  const chips: ActiveFilterChip[] = [];
  if (activeDrillThrough) chips.push({ key: 'arrived', label: activeDrillThrough.label, onClear: clearDrillThrough });
  if (statusFilter !== 'ALL') {
    chips.push({ key: 'status', label: statusFilterLabel(statusFilter), onClear: () => setStatusFilter('ALL') });
  }
  if (dateRangeOn) {
    const from = fmtDate(manualDateFrom);
    const to = fmtDate(manualDateTo);
    chips.push({
      key: 'dates',
      label: from && to ? `${from} to ${to}` : from ? `From ${from}` : `Until ${to}`,
      onClear: () => { setManualDateFrom(null); setManualDateTo(null); },
    });
  }
  if (internationalOnly) chips.push({ key: 'international', label: 'International', onClear: () => setInternationalOnly(false) });
```

   The long comments that sat on the old drill-through chip's `onPress` move onto `clearDrillThrough`.

5. The returned tree, from `<GlassScreen>` down to the end of `<FlatList>`:

```tsx
    <GlassScreen style={styles.flex}>
      <FlatList
        ref={listRef}
        testID="ledger-list"
        data={isLoading || (isError && txns.length === 0) ? [] : pieces}
        keyExtractor={(piece) => piece.key}
        // An item is a piece of a day, up to 25 rows, not one row: four pieces fill the first
        // screen on every account measured, where the old twelve would now be up to 300 rows.
        // No getItemLayout: a piece's height follows its row count and the text size.
        initialNumToRender={4}
        windowSize={9}
        removeClippedSubviews
        onScroll={onListScroll}
        scrollEventThrottle={16}
        onEndReached={() => {
          if (hasNextPage && !isFetchingNextPage) void fetchNextPage();
        }}
        onEndReachedThreshold={0.4}
        refreshing={isFetching && !isFetchingNextPage}
        onRefresh={() => void refetch()}
        // The list runs under the status bar so the pinned bar can cover it; the spinner must not.
        progressViewOffset={insets.top}
        contentContainerStyle={[styles.listContent, { paddingTop: insets.top }]}
        ListHeaderComponent={
          <View style={styles.header}>
            <LedgerTitleBlock
              countLabel={countLabel}
              onAdd={() => setAddingTransaction(true)}
              onHeight={(height) => { titleHeight.current = height; }}
            />
            {summary ? (
              <LedgerMonthCard
                kpis={snapshotKpis}
                title={periodTitle}
                deltaLabel={deltaLabel}
                deltaSpokenLabel={deltaSpokenLabel}
                stacked={largeText}
              />
            ) : null}
            <LedgerControls
              keyword={keywordInput}
              onKeywordChange={(text) => {
                setKeywordInput(text);
                // Real typing supersedes a keyword that arrived from another screen.
                setDrillThroughKeyword(null);
              }}
              searchRef={searchRef}
              typeFilter={typeFilter}
              onTypeFilter={setTypeFilter}
              filtersOn={filtersOn}
              onOpenFilters={() => setFiltersOpen(true)}
              chips={chips}
              stacked={largeText}
            />
            {error ? <Text style={[styles.error, { color: c.dangerInk }]}>{error}</Text> : null}
          </View>
        }
        ListEmptyComponent={
          <View style={[styles.stateCard, glassRow]}>
            {isLoading ? (
              Array.from({ length: 8 }).map((_, i) => <SkeletonTransactionRow key={i} />)
            ) : isError && txns.length === 0 ? (
              <View style={styles.state}>
                <Text style={[styles.errorText, { color: c.muted }]}>Couldn't load your transactions.</Text>
                <Pressable onPress={() => void refetch()} style={styles.retryButton} accessibilityRole="button">
                  <Text style={[styles.retry, { color: c.primary }]}>Try again</Text>
                </Pressable>
              </View>
            ) : (
              <Text style={[styles.empty, { color: c.muted }]}>
                {debouncedKeyword || typeFilter !== 'ALL' || statusFilter !== 'ALL' || internationalOnly || activeDrillThrough
                  || manualDateFrom || manualDateTo
                  ? 'No transactions match these filters.'
                  : 'No transactions yet. Import a statement to get started.'}
              </Text>
            )}
          </View>
        }
        ListFooterComponent={/* unchanged */}
        renderItem={({ item }) => (
          <LedgerDayCard
            piece={item}
            deletingId={deletingId}
            largeText={largeText}
            onOpen={setViewingDetail}
            onDelete={confirmDelete}
          />
        )}
      />

      <LedgerPinnedBar
        solid={barSolid}
        compact={barCompact}
        topInset={insets.top}
        onSearch={focusSearch}
        onAdd={() => setAddingTransaction(true)}
      />

      {filtersOpen ? (
        <LedgerFiltersSheet
          status={statusFilter}
          onStatus={setStatusFilter}
          dateFrom={manualDateFrom}
          dateTo={manualDateTo}
          onDateFrom={setManualDateFrom}
          onDateTo={setManualDateTo}
          internationalOnly={internationalOnly}
          onInternational={setInternationalOnly}
          onClearAll={() => {
            setStatusFilter('ALL');
            setManualDateFrom(null);
            setManualDateTo(null);
            setInternationalOnly(false);
          }}
          resultCount={isFetching || isError ? null : totalElements}
          onClose={() => setFiltersOpen(false)}
        />
      ) : null}
```

   `ListFooterComponent` keeps its present value, written out in full in the file (it is shown as a comment here only because it does not change). The long explanatory comments on the first-load error branch and on the header stay with the code they describe. The modals after the list are untouched.

6. Styles. Remove every style the old header, chips, date row, month card and row used. What remains and what is new:

```tsx
const styles = StyleSheet.create({
  flex: { flex: 1 },
  listContent: { paddingHorizontal: spacing.ml, paddingBottom: spacing.xl },
  header: { gap: spacing.ms },
  error: { fontSize: 13 },
  // The loading, failed and empty states sit in one card, where a day's card would be.
  stateCard: {
    borderWidth: StyleSheet.hairlineWidth, borderRadius: radius.xxl,
    paddingHorizontal: spacing.md, paddingVertical: spacing.xs, marginTop: spacing.md,
  },
  state: { alignItems: 'center', paddingVertical: spacing.lg },
  empty: { fontSize: 13, textAlign: 'center', paddingVertical: spacing.xl },
  footer: { paddingVertical: spacing.md, alignItems: 'center', gap: spacing.xs },
  errorText: { fontSize: 14 },
  retry: { fontSize: 14, fontWeight: '600' },
  retryButton: { /* as it is today */ },
});
```

   `retryButton` keeps the value and comment it has today. `radius` stays imported for `stateCard`.

- [ ] **Step 4: Run the screen tests**

Run: `JEST_LINES=200 zsh S/m22.sh jest src/screens/LedgerScreen.test.tsx`
Expected: PASS, every test. For any failure, read what the test rendered before changing anything; a test that asserts the old screen and was missed in Step 1 is updated to assert the same behaviour where it now lives, and is listed in the ledger.

- [ ] **Step 5: Whole suite, types, lint, bundle, guards**

Run, in this order: `zsh S/m22.sh jest`, `zsh S/m22.sh typecheck`, `zsh S/m22.sh lint`, `zsh S/m22.sh export`, `zsh S/guards.sh`.
Expected: all test suites pass with no failures; typecheck and lint print no errors; the export ends with the bundle written; each guard prints `exit=0`. Other screens' tests that render `LedgerScreen` (`AppTabs.test.tsx`, `DashboardScreen.test.tsx` through prefetch) are part of this run.

- [ ] **Step 6: Commit**

```bash
git -C /Users/sid/Downloads/finora/.claude/worktrees/mobile-dashboard-cards add mobile/src/screens/LedgerScreen.tsx mobile/src/screens/LedgerScreen.test.tsx
git -C /Users/sid/Downloads/finora/.claude/worktrees/mobile-dashboard-cards commit -m "feat(mobile): redesigned Transactions tab"
```

---

### Task 9: Read the whole change for bugs and gaps

The owner's standing rule: a self-review of the diff before it is called done, with what it finds fixed, not noted.

- [ ] **Step 1:** `git -C <worktree> diff origin/main...HEAD --stat` and then read every changed source file top to bottom.
- [ ] **Step 2: Check each line of the spec's "What does not change"** against the code: search and its delay; the three filters and their parameters; the date range's priority over an arriving one; arriving from a chart, budget, insight or import and the chip that clears it; paging, pull to refresh, the cache key, the prefetch; day grouping and totals; tap, long press, every action of the details sheet; the checklist credit; screen-capture protection; navigation tracking. For each, name the test that proves it or add one.
- [ ] **Step 3: Check the seven content changes** in the spec each have a test.
- [ ] **Step 4: Search the change** for leftovers: `grep -rnE "console\.log|TODO|FIXME|debugger" mobile/src/components/ledger mobile/src/lib/ledgerRows.ts mobile/src/screens/LedgerScreen.tsx`. Expected: nothing.
- [ ] **Step 5:** Anything found is fixed test-first and the Task 8 Step 5 checks are run again. Findings and fixes go in the ledger.

---

### Task 10: On the iPhone simulator

Read only. Production account. Each number goes in the ledger with the command that produced it.

- [ ] **Step 1: First row position.** Default text size, top of the list. Read the first row's top from the native view tree (`native-find-views`, not the accessibility tree, which lists elements scrolled out of view) and divide by the screen height. Expected: below 0.50 (the drawing measured 0.46; today is 0.58). If it is not, the spacing is corrected against the "Design reference" table before anything else.
- [ ] **Step 2: Compare with the approved picture** by pixel, not by eye: title area, month card, controls, first day card, scrolled state with the pinned bar. Differences are fixed or listed with the reason.
- [ ] **Step 3: The pinned bar.** Scroll slowly: clear at rest, solid after 24 points, small title after the large one has gone, both back at the top, nothing visible behind the clock. Record a short screen recording and look at its frames around the change. Tap the pinned search button: the list returns to the top and the keyboard opens on the search field.
- [ ] **Step 4: Titles on real data.** Read the list's data through the React DevTools hook and count which rule gave each title, as before. Expected: close to the earlier 8 / 373 / 5 of 386 (the function was rewritten, so an exact match is not assumed). Read twenty titles on screen for anything that looks wrong; no real name is copied into the repository.
- [ ] **Step 5: Repeated rows.** Scroll to the end. Expected: no duplicate-key warning in the Metro log, and the number of rows drawn equals the number of different ids. Whether six are still missing depends on whether the server fix (#2125) has been deployed; say which was seen.
- [ ] **Step 6: Scroll and first draw**, the same recordings as Task 0, three runs each. Expected: not worse than Task 0 on either thread. A run that is worse is investigated by reading what the profile says changed, before any change to the code.
- [ ] **Step 7: A very long day.** Temporary edit: make `buildDayPieces` treat every loaded row as one date. Scroll through it and record first draw and scroll once each; look at the seams between pieces (no gap, no doubled edge, shadow only at the bottom). Revert the edit and confirm with `git status --short`.
- [ ] **Step 8: Large text.** `accessibility-large` text size: walk the whole screen and the sheet. Nothing cut off, no overlap, amounts readable, the pinned title one line.
- [ ] **Step 9: Dark theme** (temporary edit in `ThemeContext`, reverted) and **Reduce Transparency** on: the same walk, shorter. The pinned bar is solid under Reduce Transparency.
- [ ] **Step 10: The behaviours.** Search for a word; switch to Income and back; open Filters, pick a status, a From date and International, read the count on the button, clear from a chip and from Clear all; pull to refresh; open a row's details and close it; long-press a row and press Cancel; open Add and close it without saving; arrive from a dashboard category and clear its chip.
- [ ] **Step 11:** Stop Metro, shut the simulator down at the default text size, `stop-all-simulator-servers` for this device, and confirm `git status --short` is clean.

---

### Task 11: On an Android emulator

- [ ] **Step 1:** Build and install a debug build on the Pixel emulator (the memory note `pixel10-emulator-signed-build-blocks-debug-install`: uninstall the signed build first). If the build cannot be made in this session, write exactly why in the ledger and in the pull request, and do not describe Android as checked.
- [ ] **Step 2:** Look at the day cards and the month card for the grey patch that elevation under a see-through fill is known to draw, on this screen and on the dashboard. If it is there on the day cards, the day card's Android style loses its elevation and keeps its edge, the component test is updated first, and the dashboard's cards are reported to the owner as a separate finding, not changed here.
- [ ] **Step 3:** The pinned bar is solid, the ripple stays inside the card's corners, the date picker opens as a dialog from the sheet, and the back button closes the sheet.

---

### Task 12: Finish

- [ ] **Step 1:** Add an "Execution record" section at the end of this plan: what was measured in Tasks 0, 10 and 11, every ruling made, and anything not verified. Update the spec where the build differs from it (the blur case that does not animate is already stated here and is added to the spec's "Pinned bar" paragraph). Commit both.
- [ ] **Step 2:** `git fetch origin`, then `git log --oneline origin/main..HEAD` and a check that nothing this branch touches changed on `main` meanwhile (`git diff HEAD...origin/main --stat -- mobile/src/screens/LedgerScreen.tsx mobile/src/components scripts/check-reporting-period-labels.py`). If it did, merge `origin/main` into the branch and run the Task 8 Step 5 checks again.
- [ ] **Step 3:** `gh repo view --json visibility`. Push the branch. If the repository is public, open the pull request; if it is private, stop and ask the owner. The description lists the seven content changes, what was measured, and what was not verified (iOS below 26, a physical phone, and Android if Task 11 could not be done). No screenshot with real balances or names goes into it.
- [ ] **Step 4:** Bind the pull request, read its checks, and report to the owner with the plan and spec sent as files.

## Not in this plan

- The server's cut names and the linked-merchant route: a production count the owner runs, then his decision.
- The details sheet's title, which still shows the narration.
- The web Ledger's lower-case titles.
- Copying the design into the Figma file, when the connector unlocks.
