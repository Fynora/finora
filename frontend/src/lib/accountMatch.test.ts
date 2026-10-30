import { describe, expect, it } from 'vitest';
import { matchExistingAccount, sameBankAccounts } from './accountMatch';
import type { Account, BankInfo, DetectedAccountInfo } from '../types';

const KOTAK: BankInfo = {
  id: 'bank-kotak', officialName: 'Kotak Mahindra Bank', shortName: 'Kotak', colorHex: '#003874',
  initials: 'K', logoPath: '', category: 'PRIVATE', websiteUrl: null, ifscPrefix: 'KKBK',
  supportedAccountTypes: ['SAVINGS'],
};
const HDFC: BankInfo = { ...KOTAK, id: 'bank-hdfc', shortName: 'HDFC', ifscPrefix: 'HDFC' };

function account(over: Partial<Account>): Account {
  return {
    id: 'acc-1', name: 'Kotak Savings', accountType: 'SAVINGS', balance: 0, bank: KOTAK,
    accountNumberMasked: null, lastImportedAt: null, lastStatementPeriodStart: null,
    lastStatementPeriodEnd: null, statementsCount: 0, transactionsCount: 0, status: 'ACTIVE',
    aaSyncStale: false,
    ...over,
  } as Account;
}

function detected(over: Partial<DetectedAccountInfo>): DetectedAccountInfo {
  return {
    suggestedName: 'Kotak Savings', suggestedAccountType: 'SAVINGS', openingBalance: null,
    closingBalance: null, statementPeriodStart: null, statementPeriodEnd: null,
    accountNumberMasked: null, creditLimit: null, totalAmountDue: null, paymentDueDate: null, accountHolderName: null,
    branchName: null, ifscCode: null, bank: KOTAK, detectedProduct: 'SAVINGS',
    productConfidence: 0.9, productNeedsReview: false, productEvidence: [],
    productIdentityHash: null, principalAmount: null, interestRate: null, maturityDate: null,
    maturityAmount: null, installmentAmount: null, installmentsPaid: null, installmentsTotal: null,
    ...over,
  } as DetectedAccountInfo;
}

describe('matchExistingAccount', () => {
  it('returns null when there are no accounts yet', () => {
    expect(matchExistingAccount(detected({}), [])).toBeNull();
  });

  it('does not match an account at a different bank', () => {
    // The reported bug: import Kotak, then import any other bank's statement, and the page
    // preselected "use an existing account: Kotak".
    const kotak = account({ id: 'acc-kotak', bank: KOTAK });
    const fromHdfc = detected({ bank: HDFC, suggestedName: 'HDFC Savings' });

    expect(matchExistingAccount(fromHdfc, [kotak])).toBeNull();
  });

  it('matches on bank and account number', () => {
    const kotak = account({ id: 'acc-kotak', accountNumberMasked: 'XXXXXX4587' });
    const same = detected({ accountNumberMasked: 'XXXXXX4587' });

    expect(matchExistingAccount(same, [kotak])?.id).toBe('acc-kotak');
  });

  it('matches the same account across statements that mask it differently', () => {
    // The same account is not masked consistently between statements, so only the digits can be
    // compared -- "XX4587" and "XXXXXX4587" are the same account.
    const kotak = account({ id: 'acc-kotak', accountNumberMasked: 'XX4587' });
    const same = detected({ accountNumberMasked: 'XXXXXX4587' });

    expect(matchExistingAccount(same, [kotak])?.id).toBe('acc-kotak');
  });

  it('does not match a different account at the same bank', () => {
    // Two Kotak accounts. The number is what tells them apart, and getting this wrong merges one
    // account's transactions into the other's.
    const first = account({ id: 'acc-1', accountNumberMasked: 'XXXXXX4587' });
    const second = detected({ accountNumberMasked: 'XXXXXX9921' });

    expect(matchExistingAccount(second, [first])).toBeNull();
  });

  it('treats a known number as a veto, not just a missed match', () => {
    // Sole account at the bank, same type -- but this statement names a DIFFERENT number, so it is
    // known to be a different account however few candidates there are.
    const first = account({ id: 'acc-1', accountNumberMasked: 'XXXXXX4587', accountType: 'SAVINGS' });
    const second = detected({ accountNumberMasked: 'XXXXXX9921', suggestedAccountType: 'SAVINGS' });

    expect(matchExistingAccount(second, [first])).toBeNull();
  });

  it('matches a sole same-type account at the bank when neither side has a number', () => {
    const kotak = account({ id: 'acc-kotak', accountNumberMasked: null, accountType: 'SAVINGS' });
    const noNumber = detected({ accountNumberMasked: null, suggestedAccountType: 'SAVINGS' });

    expect(matchExistingAccount(noNumber, [kotak])?.id).toBe('acc-kotak');
  });

  it('does not guess between two same-type accounts at the same bank', () => {
    const a = account({ id: 'acc-1', accountType: 'SAVINGS' });
    const b = account({ id: 'acc-2', accountType: 'SAVINGS' });

    expect(matchExistingAccount(detected({}), [a, b])).toBeNull();
  });

  it('does not match a credit card statement to a savings account at the same bank', () => {
    const savings = account({ id: 'acc-savings', accountType: 'SAVINGS' });
    const card = detected({ suggestedAccountType: 'CREDIT_CARD' });

    expect(matchExistingAccount(card, [savings])).toBeNull();
  });

  it('ignores a masked number too short to identify anything', () => {
    // "XX87" is two digits of signal. Matching on that would pair unrelated accounts; it is
    // treated as absent, which falls through to the single-same-type-account rule.
    const kotak = account({ id: 'acc-kotak', accountNumberMasked: 'XX87', accountType: 'SAVINGS' });
    const short = detected({ accountNumberMasked: 'XX87', suggestedAccountType: 'SAVINGS' });

    expect(matchExistingAccount(short, [kotak])?.id).toBe('acc-kotak');
  });

  it('picks the right account out of several at the same bank', () => {
    const savings = account({ id: 'acc-savings', accountType: 'SAVINGS', accountNumberMasked: 'XXXXXX4587' });
    const card = account({ id: 'acc-card', accountType: 'CREDIT_CARD', accountNumberMasked: 'XXXXXX9921' });
    const cardStatement = detected({ suggestedAccountType: 'CREDIT_CARD', accountNumberMasked: 'XXXXXX9921' });

    expect(matchExistingAccount(cardStatement, [savings, card])?.id).toBe('acc-card');
  });

  // Bug fix (found during Plan 3's post-implementation review): an AA-linked account was never
  // excluded from consideration here, so a confident match (same bank + number, or the only
  // account of that type) could silently preselect "use an existing account" pointing at one --
  // defeating the whole point of Import.tsx's disabled dropdown option, since the user never has
  // to open the dropdown at all when a match auto-selects it for them. They'd only discover the
  // account is blocked after clicking "Confirm Import" and hitting AccountAggregatorGuard's 409,
  // exactly the dead-end interaction this plan set out to prevent proactively.
  it('never matches an AA-linked account, even with an otherwise-conclusive account number match', () => {
    const aaLinked = account({
      id: 'acc-aa', accountNumberMasked: 'XXXXXX4587', primarySource: 'ACCOUNT_AGGREGATOR',
    });
    const statement = detected({ accountNumberMasked: 'XXXXXX4587' });

    expect(matchExistingAccount(statement, [aaLinked])).toBeNull();
  });

  it('never matches an AA-linked account via the single-same-type-at-bank fallback', () => {
    const aaLinked = account({ id: 'acc-aa', accountType: 'SAVINGS', primarySource: 'ACCOUNT_AGGREGATOR' });
    const statement = detected({ suggestedAccountType: 'SAVINGS' });

    expect(matchExistingAccount(statement, [aaLinked])).toBeNull();
  });

  // Plan 4 (the outage escape hatch): the exclusion above has one exception -- a stale AA-linked
  // account is eligible, since the backend guard itself allows manual import into one. The picker
  // must not be stricter than what the backend will actually accept.
  it('matches an AA-linked account when its sync is currently stale', () => {
    const staleAaLinked = account({
      id: 'acc-aa', accountNumberMasked: 'XXXXXX4587',
      primarySource: 'ACCOUNT_AGGREGATOR', aaSyncStale: true,
    });
    const statement = detected({ accountNumberMasked: 'XXXXXX4587' });

    expect(matchExistingAccount(statement, [staleAaLinked])?.id).toBe('acc-aa');
  });
});

const OTHER: BankInfo = {
  id: 'OTHER', officialName: null, shortName: 'Other', colorHex: '#000000', initials: 'OT',
  logoPath: '', category: null, websiteUrl: null, ifscPrefix: null, supportedAccountTypes: [],
};

describe('matchExistingAccount — "Other bank" is not a bank', () => {
  it('never matches an OTHER statement to an OTHER account, even as the sole same-type account', () => {
    // The bug: one "Other bank" savings account got every unknown-bank savings statement
    // preselected into it, whatever bank the statement really came from.
    const otherSavings = account({ id: 'acc-other', bank: OTHER });
    const unknownStatement = detected({ bank: OTHER, suggestedName: 'Bank Statement Import' });

    expect(matchExistingAccount(unknownStatement, [otherSavings])).toBeNull();
  });

  it('never matches an OTHER statement to an OTHER account, even with the same account number', () => {
    const otherSavings = account({ id: 'acc-other', bank: OTHER, accountNumberMasked: 'XXXX4587' });
    const unknownStatement = detected({ bank: OTHER, accountNumberMasked: 'XXXXXX4587' });

    expect(matchExistingAccount(unknownStatement, [otherSavings])).toBeNull();
  });

  it('still matches a real bank to the same real bank', () => {
    const kotak = account({ id: 'acc-kotak' });
    expect(matchExistingAccount(detected({}), [kotak, account({ id: 'acc-other', bank: OTHER })])?.id).toBe('acc-kotak');
  });
});

describe('sameBankAccounts', () => {
  const kotakSavings = account({ id: 'acc-kotak-sav' });
  const kotakCard = account({ id: 'acc-kotak-card', accountType: 'CREDIT_CARD' });
  const hdfcSavings = account({ id: 'acc-hdfc-sav', bank: HDFC });
  const otherSavings = account({ id: 'acc-other', bank: OTHER });

  it('lists accounts of the chosen bank and type only', () => {
    expect(sameBankAccounts(KOTAK.id, 'SAVINGS', [kotakSavings, kotakCard, hdfcSavings]).map((a) => a.id))
      .toEqual(['acc-kotak-sav']);
  });

  it('offers nothing for "Other bank" or no choice yet', () => {
    expect(sameBankAccounts('OTHER', 'SAVINGS', [otherSavings])).toEqual([]);
    expect(sameBankAccounts('', 'SAVINGS', [otherSavings, kotakSavings])).toEqual([]);
  });

  it('leaves out an account whose number is known to differ from the statement', () => {
    const numbered = account({ id: 'acc-4587', accountNumberMasked: 'XXXX4587' });
    const otherNumber = account({ id: 'acc-9911', accountNumberMasked: 'XXXX9911' });
    const unnumbered = account({ id: 'acc-none', accountNumberMasked: null });

    expect(sameBankAccounts(KOTAK.id, 'SAVINGS', [numbered, otherNumber, unnumbered], 'XXXXXX4587').map((a) => a.id))
      .toEqual(['acc-4587', 'acc-none']);
    // No number on the statement: nothing to rule out.
    expect(sameBankAccounts(KOTAK.id, 'SAVINGS', [numbered, otherNumber], null).map((a) => a.id))
      .toEqual(['acc-4587', 'acc-9911']);
  });

  it('leaves out an AA-linked account unless its sync is stale', () => {
    const linked = account({ id: 'acc-aa', primarySource: 'ACCOUNT_AGGREGATOR', aaSyncStale: false });
    const stale = account({ id: 'acc-aa-stale', primarySource: 'ACCOUNT_AGGREGATOR', aaSyncStale: true });
    expect(sameBankAccounts(KOTAK.id, 'SAVINGS', [linked, stale]).map((a) => a.id)).toEqual(['acc-aa-stale']);
  });
});
