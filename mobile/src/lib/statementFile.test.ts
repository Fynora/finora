import * as DocumentPicker from 'expo-document-picker';
import * as appLock from './appLock';
import { detectStatementFormat, pickStatement } from './statementFile';

jest.mock('expo-document-picker', () => ({ getDocumentAsync: jest.fn() }));

const picker = DocumentPicker as jest.Mocked<typeof DocumentPicker>;

describe('pickStatement', () => {
  beforeEach(() => {
    picker.getDocumentAsync.mockReset();
    appLock.__resetSharingStateForTests();
  });

  // Bug found in review (Track D/D5): the native picker backgrounds this app the same way
  // Sharing.shareAsync does; without withShareSuppression, AppLockGate would show a spurious lock
  // prompt the instant the picker (or a provider like Drive/iCloud) returns focus here.
  it('suppresses AppLockGate for the duration of the picker call', async () => {
    picker.getDocumentAsync.mockImplementation(async () => {
      expect(appLock.isSharing()).toBe(true);
      return { canceled: true, assets: null };
    });

    expect(appLock.isSharing()).toBe(false);
    await pickStatement();
    expect(appLock.isSharing()).toBe(false);
  });
});

describe('detectStatementFormat', () => {
  it('recognises a .pdf extension', () => {
    expect(detectStatementFormat('statement.pdf')).toBe('PDF');
  });

  it('recognises a .csv extension', () => {
    expect(detectStatementFormat('statement.csv')).toBe('CSV');
  });

  it('is case-insensitive', () => {
    expect(detectStatementFormat('STATEMENT.PDF')).toBe('PDF');
    expect(detectStatementFormat('Statement.Csv')).toBe('CSV');
  });

  it('returns null for an unsupported extension', () => {
    expect(detectStatementFormat('statement.txt')).toBeNull();
  });

  it('returns null for a name with no extension', () => {
    expect(detectStatementFormat('statement')).toBeNull();
  });
});
