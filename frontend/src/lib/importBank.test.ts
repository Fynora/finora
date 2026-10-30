import { describe, expect, it } from 'vitest';
import { accountNameForBank, bankHint, bankSourceOf, initialBankChoice } from './importBank';
import { toNewAccountPayload, type NewAccountForm } from './newAccountPayload';
import type { BankInfo, DetectedAccountInfo } from '../types';

const HDFC: BankInfo = {
  id: 'HDFC', officialName: 'HDFC Bank', shortName: 'HDFC', colorHex: '#0B2447', initials: 'H',
  logoPath: '', category: 'PRIVATE', websiteUrl: null, ifscPrefix: 'HDFC', supportedAccountTypes: [],
};
const ICICI: BankInfo = { ...HDFC, id: 'ICICI', officialName: 'ICICI Bank', shortName: 'ICICI' };
const OTHER: BankInfo = {
  id: 'OTHER', officialName: null, shortName: 'Other', colorHex: '#000000', initials: 'OT',
  logoPath: '', category: null, websiteUrl: null, ifscPrefix: null, supportedAccountTypes: [],
};

function detected(over: Partial<DetectedAccountInfo>): DetectedAccountInfo {
  return {
    suggestedName: 'HDFC Bank', suggestedAccountType: 'SAVINGS', openingBalance: null,
    closingBalance: null, statementPeriodStart: null, statementPeriodEnd: null,
    accountNumberMasked: 'XXXX4587', creditLimit: null, totalAmountDue: null, paymentDueDate: null,
    accountHolderName: null, branchName: null, ifscCode: null, bank: HDFC, bankSource: 'FILE',
    detectedProduct: 'SAVINGS', productConfidence: 0.9, productNeedsReview: false, productEvidence: [],
    productIdentityHash: 'staged-key', principalAmount: null, interestRate: null, maturityDate: null,
    maturityAmount: null, installmentAmount: null, installmentsPaid: null, installmentsTotal: null,
    ...over,
  };
}

const form = (newBankId: string): NewAccountForm => ({
  newName: 'Salary', newType: 'SAVINGS', newOpeningBalance: '', newCreditLimit: '', newDueDate: '', newBankId,
});

describe('bankSourceOf', () => {
  it('reads the server value when it sent one', () => {
    expect(bankSourceOf(detected({ bankSource: 'FILE' }))).toBe('FILE');
    expect(bankSourceOf(detected({ bankSource: 'PROFILE' }))).toBe('PROFILE');
    expect(bankSourceOf(detected({ bank: OTHER, bankSource: 'NONE' }))).toBe('NONE');
  });

  it('reads a session staged before bankSource existed as FILE for a known bank, NONE for OTHER', () => {
    expect(bankSourceOf(detected({ bankSource: null }))).toBe('FILE');
    expect(bankSourceOf(detected({ bankSource: undefined }))).toBe('FILE');
    expect(bankSourceOf(detected({ bank: OTHER, bankSource: null }))).toBe('NONE');
  });

  it('never treats OTHER as a known bank, whatever the field says', () => {
    expect(bankSourceOf(detected({ bank: OTHER, bankSource: 'FILE' }))).toBe('NONE');
  });
});

describe('initialBankChoice', () => {
  it('pre-fills a known bank and leaves an unknown one empty', () => {
    expect(initialBankChoice(detected({}))).toBe('HDFC');
    expect(initialBankChoice(detected({ bankSource: 'PROFILE' }))).toBe('HDFC');
    expect(initialBankChoice(detected({ bank: OTHER, bankSource: 'NONE' }))).toBe('');
    expect(initialBankChoice(detected({ bank: OTHER, bankSource: null }))).toBe('');
    expect(initialBankChoice(null)).toBe('');
  });
});

describe('bankHint', () => {
  it('words where a pre-filled bank came from', () => {
    expect(bankHint('FILE')).toBe('Detected from this statement');
    expect(bankHint('PROFILE')).toBe("Recognised from this statement's format");
    expect(bankHint('NONE')).toBeNull();
  });
});

describe('accountNameForBank', () => {
  it("is the bank's name, or the generic import name for Other bank", () => {
    expect(accountNameForBank('HDFC', HDFC)).toBe('HDFC Bank');
    expect(accountNameForBank('OTHER', null)).toBe('Bank Statement Import');
  });
});

describe('toNewAccountPayload — the bank', () => {
  it("sends the user's final choice, not the staged bank", () => {
    expect(toNewAccountPayload(form('ICICI'), detected({})).bankId).toBe('ICICI');
    expect(toNewAccountPayload(form('OTHER'), detected({})).bankId).toBe('OTHER');
    expect(toNewAccountPayload(form('HDFC'), detected({ bank: OTHER, bankSource: 'NONE' })).bankId).toBe('HDFC');
  });

  it('keeps the product identity key only while the bank is the one it was built for', () => {
    expect(toNewAccountPayload(form('HDFC'), detected({})).productIdentityHash).toBe('staged-key');
    expect(toNewAccountPayload(form('ICICI'), detected({})).productIdentityHash).toBeNull();
    expect(toNewAccountPayload(form('OTHER'), detected({})).productIdentityHash).toBeNull();
  });

  it('a section staged with ICICI keeps its own key when ICICI is kept', () => {
    expect(toNewAccountPayload(form('ICICI'), detected({ bank: ICICI })).productIdentityHash).toBe('staged-key');
  });
});
