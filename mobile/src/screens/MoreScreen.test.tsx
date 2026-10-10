import { fireEvent, render, screen } from '@testing-library/react-native';
import { MoreScreen } from './MoreScreen';
import { trackNavigation } from '../lib/trackNavigation';
import { NAV_TAXONOMY } from '../navigation/taxonomy';

jest.mock('../lib/trackNavigation', () => ({ trackNavigation: jest.fn() }));
// A mutable holder read through useAuth(), so one file covers a named account and a bare one. The
// `mock` prefix is what lets jest's hoisting allow this variable inside the factory.
const mockAuth: { email: string | null; fullName: string | null } = { email: 'you@example.com', fullName: 'Ada Lovelace' };
jest.mock('../context/AuthContext', () => ({
  useAuth: () => ({ ...mockAuth, logout: jest.fn() }),
}));
jest.mock('react-native-safe-area-context', () => ({
  useSafeAreaInsets: () => ({ top: 0, bottom: 0, left: 0, right: 0 }),
}));

// One spy per tour key, so a test can tell WHICH row a target landed on rather than only that
// some row registered.
const mockTourRegistrations: Record<string, jest.Mock> = {};
jest.mock('../onboarding/TourTargetRegistry', () => ({
  useRegisterTourTarget: (key: string) => (mockTourRegistrations[key] ??= jest.fn()),
}));

// `navigate` pushes within the More stack; the parent is the tab navigator a promoted row jumps to.
const tabNavigate = jest.fn();
const navigation = { navigate: jest.fn(), getParent: () => ({ navigate: tabNavigate }) };

function renderScreen() {
  return render(<MoreScreen navigation={navigation as never} route={{ key: 'm', name: 'MoreHome' } as never} />);
}

beforeEach(() => {
  jest.clearAllMocks();
  mockAuth.email = 'you@example.com';
  mockAuth.fullName = 'Ada Lovelace';
});

// The approved grouping (docs/superpowers/specs/2026-09-23-shared-nav-taxonomy-design.md), written
// out in full rather than read from the screen or the taxonomy: a test that imports the data it
// checks can only fail on a typo in the test. Each row is [label, taxonomy id, where it goes].
type Row = [label: string, id: string, target: { screen: string } | { tab: string }];
const GROUPS: [string, Row[]][] = [
  ['Money', [
    ['Accounts', 'accounts', { screen: 'Accounts' }],
    ['Transactions', 'transactions', { tab: 'Transactions' }],
  ]],
  ['Statements', [
    ['Import Statement', 'import-statement', { tab: 'Import' }],
    ['Statement History', 'statement-history', { screen: 'Statements' }],
    ['Review Categories', 'review-categories', { screen: 'CategoryReview' }],
    ['Financial Memory', 'financial-memory', { screen: 'FinancialMemory' }],
  ]],
  ['Planning', [
    ['Budgets', 'budgets', { screen: 'Budgets' }],
    ['Goals', 'goals', { screen: 'Goals' }],
    ['Investments', 'investments', { screen: 'Investments' }],
  ]],
  ['Analysis', [
    ['Insights', 'insights', { tab: 'Insights' }],
    ['Reports', 'reports', { screen: 'Reports' }],
    ['Advanced Reports', 'advanced-reports', { screen: 'AdvancedReports' }],
    ['Ask Fyn', 'ask-fyn', { screen: 'Fyn' }],
  ]],
  ['Your Account', [
    ['Profile', 'profile', { screen: 'Profile' }],
    ['Subscription', 'subscription', { screen: 'Subscription' }],
    ['Refer & Earn', 'referrals', { screen: 'Referrals' }],
    ['Settings', 'settings', { screen: 'Settings' }],
    ['Support', 'support', { screen: 'SupportTickets' }],
  ]],
];
const ROWS = GROUPS.flatMap(([, rows]) => rows);

/** The row for `label`. Matched by its visible text, so the Profile row's longer spoken label
 *  ("Profile: Ada Lovelace, ...") does not need special-casing. */
const row = (label: string) => screen.getByText(label);

test('shows the five group headings, in taxonomy order', () => {
  renderScreen();
  expect(screen.getAllByRole('header').map((h) => h.props.children))
    .toEqual(['Money', 'Statements', 'Planning', 'Analysis', 'Your Account']);
});

test('lists all eighteen grouped destinations, each exactly once', () => {
  renderScreen();
  expect(ROWS).toHaveLength(18);
  for (const [label] of ROWS) {
    expect(screen.getAllByText(label)).toHaveLength(1);
  }
});

test('every row sits under its own heading, in the order the taxonomy lists it', () => {
  renderScreen();
  // Every heading and every row label, in the order they are laid out on screen.
  const labels = new Set(ROWS.map(([label]) => label));
  const sequence = screen.root.findAll((node) =>
    (node.type as unknown) === 'Text'
    && (node.props.accessibilityRole === 'header' || labels.has(node.props.children))
  ).map((node) => (node.props.accessibilityRole === 'header' ? `# ${node.props.children}` : node.props.children));

  expect(sequence).toEqual(GROUPS.flatMap(([label, rows]) => [`# ${label}`, ...rows.map(([r]) => r)]));
});

test('the menu and the shared taxonomy agree: nothing outside the root is missing or extra', () => {
  // The one place this file reads the taxonomy, and deliberately: the expectations above pin
  // today's contents, this pins the rule. A destination added to taxonomy.ts with no entry in
  // MoreScreen's DESTINATIONS would render nothing and fail here, not go quietly missing.
  renderScreen();
  const grouped = NAV_TAXONOMY.filter((entry) => entry.group !== 'root');
  expect(grouped.map((entry) => entry.label).sort()).toEqual(ROWS.map(([label]) => label).sort());
  for (const { label } of grouped) {
    expect(screen.getAllByText(label)).toHaveLength(1);
  }
  // Home is the taxonomy's ungrouped root and has no row here.
  expect(screen.queryByText('Home')).toBeNull();
});

describe('where each row goes', () => {
  test.each(ROWS)('%s reports itself as %s and opens the right place', (label, id, target) => {
    renderScreen();
    fireEvent.press(row(label));

    expect(trackNavigation).toHaveBeenCalledTimes(1);
    expect(trackNavigation).toHaveBeenCalledWith(id, 'group');
    if ('tab' in target) {
      // Also a tab: the row jumps to that tab rather than pushing a second copy inside More.
      expect(tabNavigate).toHaveBeenCalledTimes(1);
      expect(tabNavigate).toHaveBeenCalledWith(target.tab);
      expect(navigation.navigate).not.toHaveBeenCalled();
    } else {
      expect(navigation.navigate).toHaveBeenCalledTimes(1);
      expect(navigation.navigate).toHaveBeenCalledWith(target.screen);
      expect(tabNavigate).not.toHaveBeenCalled();
    }
  });
});

describe('Profile', () => {
  test('is a row inside Your Account that still says whose account this is', () => {
    renderScreen();
    // Name and email on separate lines; read out as one comma-separated phrase.
    expect(screen.getByText('Ada Lovelace\nyou@example.com')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Profile: Ada Lovelace, you@example.com' })).toBeTruthy();
  });

  test('shows the email alone when the account has no name', () => {
    mockAuth.fullName = null;
    renderScreen();
    expect(screen.getByText('you@example.com')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Profile: you@example.com' })).toBeTruthy();
  });

  test('is a plain row when neither name nor email is known yet', () => {
    mockAuth.fullName = null;
    mockAuth.email = null;
    renderScreen();
    expect(screen.getByRole('button', { name: 'Profile' })).toBeTruthy();
  });
});

test('the tour still finds the Accounts, Budgets and Goals rows', () => {
  renderScreen();
  for (const [key, label] of [['accounts', 'Accounts'], ['budgets', 'Budgets'], ['goals', 'Goals']] as const) {
    const registered = mockTourRegistrations[key].mock.calls.map(([node]) => node).filter(Boolean);
    expect(registered).toHaveLength(1);
    // The registered node is the pressable row itself -- the tour measures it to draw the
    // spotlight, so landing on a wrapper or on a neighbouring row would move the highlight.
    expect(registered[0].props.accessibilityRole).toBe('button');
    expect(registered[0].props.accessibilityLabel).toBe(label);
  }
});

test('Sign out is still offered, outside the groups', () => {
  renderScreen();
  expect(screen.getByRole('button', { name: 'Sign out' })).toBeTruthy();
});
