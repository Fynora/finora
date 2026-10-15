import { readdirSync, readFileSync, statSync } from 'fs';
import { join, relative } from 'path';

/**
 * A skeleton means "your content is coming"; a spinner means "your action is running". Every
 * ActivityIndicator left in the app is on this list, by file and count, with the action it shows.
 * Adding one is a visible change in review: a first load that reaches for a spinner again fails
 * here and should get a skeleton instead.
 */
const KEEP: Record<string, { count: number; why: string }> = {
  'components/Button.tsx': { count: 1, why: 'a button whose action is running' },
  'screens/AccountsScreen.tsx': { count: 1, why: 'an account being deleted' },
  'components/ledger/LedgerDayCard.tsx': { count: 1, why: 'a transaction being deleted' },
  'screens/PaywallScreen.tsx': { count: 1, why: 'a plan being bought' },
  'components/ImportProgressCard.tsx': { count: 1, why: 'import progress' },
  'screens/LedgerScreen.tsx': { count: 1, why: 'the next page of transactions' },
  'screens/CategoryReviewScreen.tsx': { count: 1, why: 'the refetch after a review action' },
  'screens/GmailReviewScreen.tsx': { count: 1, why: 'an approve or reject running' },
  'screens/SettingsBankSyncConfirmScreen.tsx': { count: 1, why: 'the account-link step' },
  'screens/TransactionDetailSheet.tsx': { count: 1, why: 'a transaction action row running' },
  'screens/import/ImportScreen.tsx': { count: 1, why: 'resuming an unfinished import' },
  'screens/settings/DeviceSessionsSection.tsx': { count: 1, why: 'a device being signed out' },
  'screens/settings/GmailConnectionSection.tsx': { count: 1, why: 'Gmail syncing' },
  'navigation/RootNavigator.tsx': { count: 2, why: 'the app starting up' },
};

const SRC = join(__dirname, '..', '..');
const files = (d: string): string[] => readdirSync(d).flatMap((f) => {
  const p = join(d, f);
  return statSync(p).isDirectory() ? files(p) : /\.tsx$/.test(f) && !/\.test\.tsx$/.test(f) ? [p] : [];
});
const spinnerCounts = Object.fromEntries(
  files(SRC)
    .map((p) => [relative(SRC, p), (readFileSync(p, 'utf8').match(/<ActivityIndicator\b/g) ?? []).length] as const)
    .filter(([, n]) => n > 0)
);

it('every spinner left in the app is an action spinner on the keep list', () => {
  expect(spinnerCounts).toEqual(Object.fromEntries(Object.entries(KEEP).map(([f, { count }]) => [f, count])));
});
