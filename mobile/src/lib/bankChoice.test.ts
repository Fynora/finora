import {
  bankDisplayName, bankOptionsFrom, bankSourceOf, filterBankOptions, importBankHint, initialBankChoice,
  matchingBanks, nameAfterBankChoice, OTHER_BANK_OPTION, shouldAskWhichBank, UNKNOWN_BANK_ACCOUNT_NAME,
  whichBankPromptKey,
} from './bankChoice';
import type { Account, BankInfo, DetectedAccountInfo } from '../types';

const bank = (over: Partial<BankInfo>): BankInfo => ({
  id: 'HDFC', officialName: 'HDFC Bank', shortName: 'HDFC', colorHex: '#004c8f', initials: 'H',
  logoPath: '', category: 'PRIVATE', websiteUrl: null, ifscPrefix: 'HDFC', supportedAccountTypes: [],
  ...over,
});
const OTHER = bank({ id: 'OTHER', officialName: null, shortName: 'Other', initials: '?' });

const detected = (over: Partial<DetectedAccountInfo>): DetectedAccountInfo =>
  ({ suggestedName: 'HDFC Bank', bank: bank({}), ...over }) as DetectedAccountInfo;

const account = (over: Partial<Account>): Account =>
  ({
    id: 'acc-1', name: 'Bank Statement Import', accountType: 'SAVINGS', balance: 0, bank: OTHER,
    lastImportedAt: '2026-10-20T10:00:00Z', statementsCount: 1, transactionsCount: 3,
    primarySource: 'MANUAL', ...over,
  }) as Account;

describe('bankSourceOf', () => {
  it('reads FILE and PROFILE as sent', () => {
    expect(bankSourceOf(detected({ bankSource: 'FILE' }))).toBe('FILE');
    expect(bankSourceOf(detected({ bankSource: 'PROFILE' }))).toBe('PROFILE');
  });

  it('treats a null bankSource as FILE for a real bank and NONE for OTHER', () => {
    // Sessions staged before the backend sent bankSource (#2316).
    expect(bankSourceOf(detected({ bankSource: null }))).toBe('FILE');
    expect(bankSourceOf(detected({ bankSource: undefined }))).toBe('FILE');
    expect(bankSourceOf(detected({ bank: OTHER, bankSource: null }))).toBe('NONE');
  });

  it('never calls OTHER known, whatever the source says', () => {
    // A wrong bank is worse than no bank: "Other bank" must never look confirmed.
    expect(bankSourceOf(detected({ bank: OTHER, bankSource: 'FILE' }))).toBe('NONE');
    expect(bankSourceOf(detected({ bank: OTHER, bankSource: 'PROFILE' }))).toBe('NONE');
    expect(bankSourceOf(null)).toBe('NONE');
  });
});

describe('initialBankChoice', () => {
  it('pre-fills a bank the file or its profile named', () => {
    expect(initialBankChoice(detected({ bankSource: 'FILE' }))).toEqual({ id: 'HDFC', name: 'HDFC Bank' });
    expect(initialBankChoice(detected({ bankSource: 'PROFILE' }))).toEqual({ id: 'HDFC', name: 'HDFC Bank' });
  });

  it('leaves it empty when nothing named one', () => {
    expect(initialBankChoice(detected({ bank: OTHER, bankSource: 'NONE' }))).toBeNull();
    expect(initialBankChoice(detected({ bank: OTHER, bankSource: null }))).toBeNull();
    expect(initialBankChoice(null)).toBeNull();
  });
});

describe('importBankHint', () => {
  const hdfc = { id: 'HDFC', name: 'HDFC Bank' };
  const icici = { id: 'ICICI', name: 'ICICI Bank' };

  it('says where a pre-filled bank came from while it is still the one shown', () => {
    expect(importBankHint('FILE', hdfc, hdfc)).toBe('Detected from this statement.');
    expect(importBankHint('PROFILE', hdfc, hdfc)).toBe("Recognised from this statement's format.");
  });

  it('drops the source once the person picks another bank', () => {
    expect(importBankHint('FILE', icici, hdfc)).toBeNull();
    expect(importBankHint('NONE', icici, null)).toBeNull();
  });

  it('explains why a choice is needed when there is none', () => {
    expect(importBankHint('NONE', null, null)).toMatch(/doesn't say which bank/);
  });
});

describe('nameAfterBankChoice', () => {
  it('follows the bank until the person types a name', () => {
    expect(nameAfterBankChoice('Bank Statement Import', false, { id: 'HDFC', name: 'HDFC Bank' })).toBe('HDFC Bank');
  });

  it('keeps a name the person typed', () => {
    expect(nameAfterBankChoice('Salary account', true, { id: 'HDFC', name: 'HDFC Bank' })).toBe('Salary account');
  });

  it('follows the bank again when the typed name was cleared', () => {
    expect(nameAfterBankChoice('  ', true, { id: 'HDFC', name: 'HDFC Bank' })).toBe('HDFC Bank');
  });

  it('goes back to the generic name on "Other bank" rather than keeping an earlier bank name', () => {
    expect(nameAfterBankChoice('HDFC Bank', false, OTHER_BANK_OPTION)).toBe(UNKNOWN_BANK_ACCOUNT_NAME);
  });
});

describe('bank list', () => {
  const options = bankOptionsFrom([
    bank({ id: 'SBI', officialName: 'State Bank of India', shortName: 'SBI' }),
    OTHER,
    bank({ id: 'HDFC' }),
    bank({ id: 'CUSTOM1', officialName: null, shortName: 'Co-op Bank' }),
  ]);

  it('drops OTHER from the server list, names each bank, and sorts by name', () => {
    expect(options).toEqual([
      { id: 'CUSTOM1', name: 'Co-op Bank' },
      { id: 'HDFC', name: 'HDFC Bank' },
      { id: 'SBI', name: 'State Bank of India' },
    ]);
  });

  it('always ends with "Other bank", even when nothing matches or the list is empty', () => {
    expect(filterBankOptions(options, 'zzz', 8)).toEqual([OTHER_BANK_OPTION]);
    expect(filterBankOptions([], '', 8)).toEqual([OTHER_BANK_OPTION]);
    expect(filterBankOptions(options, '', 1)).toEqual([{ id: 'CUSTOM1', name: 'Co-op Bank' }, OTHER_BANK_OPTION]);
  });

  it('searches names and ids, ignoring case', () => {
    expect(matchingBanks(options, 'state').map((b) => b.id)).toEqual(['SBI']);
    expect(matchingBanks(options, 'sbi').map((b) => b.id)).toEqual(['SBI']);
    expect(matchingBanks(options, '  ').length).toBe(3);
  });

  it('names OTHER "Other bank"', () => {
    expect(bankDisplayName(OTHER)).toBe('Other bank');
    expect(bankDisplayName(null)).toBe('Other bank');
  });
});

describe('shouldAskWhichBank', () => {
  it('asks on an imported account whose bank is unknown', () => {
    expect(shouldAskWhichBank(account({}))).toBe(true);
    expect(shouldAskWhichBank(account({ lastImportedAt: null, statementsCount: 2 }))).toBe(true);
  });

  it('does not ask on an account added by hand that never had a statement', () => {
    expect(shouldAskWhichBank(account({ lastImportedAt: null, statementsCount: 0 }))).toBe(false);
  });

  it('does not ask on an Account Aggregator account', () => {
    expect(shouldAskWhichBank(account({ primarySource: 'ACCOUNT_AGGREGATOR' }))).toBe(false);
  });

  it('does not ask once the account has a bank', () => {
    expect(shouldAskWhichBank(account({ bank: bank({}) }))).toBe(false);
  });

  it('keys the stored answer by account, within SecureStore key characters', () => {
    const key = whichBankPromptKey('3f2b9c1e-0000-4000-8000-000000000001');
    expect(key).toMatch(/^[A-Za-z0-9._-]+$/);
    expect(key).not.toBe(whichBankPromptKey('3f2b9c1e-0000-4000-8000-000000000002'));
  });
});
