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

  // The owner's follow-up (2026-10-16): payment-rail and banking abbreviations stay in capitals.
  // "Upi to Sample Person" and "Neft Cr" read as typos; every other word keeps the 12 Oct rule.
  it.each([
    ['UPI TO SAMPLE PERSON', 'UPI To Sample Person'],
    ['NEFT CR SAMPLE EMPLOYER', 'NEFT Cr Sample Employer'],
    ['IMPS transfer to own account', 'IMPS transfer to own account'],
    ['RTGS ATM POS EMI ECS NACH GST TDS SIP', 'RTGS ATM POS EMI ECS NACH GST TDS SIP'],
    ['UPI123 PAY', 'UPI123 Pay'],
  ])('keeps banking abbreviations in capitals: %s', (input, expected) => {
    expect(softenCapitals(input)).toBe(expected);
  });

  it('keeps an abbreviation only as a whole word, not inside a longer one', () => {
    expect(softenCapitals('POSTAL ORDER')).toBe('Postal Order');
    expect(softenCapitals('ATMOSPHERE CAFE')).toBe('Atmosphere Cafe');
  });
});

describe('rowTitle', () => {
  it('uses the description when there is no merchant', () => {
    expect(rowTitle(txn({ description: 'NEFT INWARD 0042', merchant: '' }))).toBe('NEFT Inward 0042');
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
    // The printed stretch ends at the last word, so the full stop after it is not part of the title.
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

  it('orders match, bank correction, needs review, recurring, international, card instalment', () => {
    const tags = rowTags(txn({
      reconciliationStatus: 'TRANSFER', pendingBankCorrection: true, needsCategoryReview: true,
      recurring: true, international: true, cardInstalment: true,
    }));
    expect(tags.map((t) => t.label)).toEqual(['Transfer', 'Bank Correction', 'Needs Review', 'Recurring', 'International', 'EMI instalment']);
    expect(tags.map((t) => t.key)).toEqual(['match', 'bank-correction', 'needs-review', 'recurring', 'international', 'card-instalment']);
  });

  it('marks a card instalment only when the server says so', () => {
    expect(rowTags(txn({ cardInstalment: false }))).toEqual([]);
    // A server that predates the flag sends none.
    expect(rowTags(txn())).toEqual([]);
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
    expect(rowTags(txn({ cardInstalment: true }))[0].tone).toBe('neutral');
  });
});

describe('rowSpokenLabel', () => {
  it('starts with the title a sighted person sees and keeps the date', () => {
    const label = rowSpokenLabel(txn({ description: 'UPI/DR/600000000001/RAHUL SH/YESB', merchant: 'rahul sh', counterpartyType: 'PERSON' }));
    expect(label).toBe('Rahul Sh, expense ₹1,250, Food, 14 Jul 2026, Sent to a person');
  });

  it('adds the match explanation and the tags shown, with international last', () => {
    const label = rowSpokenLabel(txn({ reconciliationStatus: 'DUPLICATE', recurring: true, international: true }));
    expect(label).toBe('Grocery run, expense ₹1,250, Food, 14 Jul 2026, Matched as a repeat of another transaction, Recurring, international');
  });

  it('speaks the instalment mark as its full note, not its two words', () => {
    const label = rowSpokenLabel(txn({ cardInstalment: true }));
    expect(label).toBe(
      'Grocery run, expense ₹1,250, Food, 14 Jul 2026, A monthly instalment on an earlier card purchase. '
        + 'If that purchase is also in Fynora, your spending totals count both the purchase and its instalments.'
    );
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
