import { describe, expect, it } from 'vitest';
import { rowTitle, softenCapitals } from './rowTitle';

// The same cases as mobile/src/lib/ledgerRows.test.ts: the two apps share one titling rule.
const txn = (t: { description: string; merchant: string }) => t;

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
  // A title built from the server's lower-case merchant label, when its words are not found in
  // the description, is capitalised word by word; the banking terms keep their capitals there too.
  it('keeps banking terms in capitals in a title built from the merchant label', () => {
    expect(rowTitle(txn({ description: 'PAYMENT RECEIVED', merchant: 'upi collect' }))).toBe('UPI Collect');
  });

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
