import { fireEvent, render, screen } from '@testing-library/react-native';
import { MoreScreen } from './MoreScreen';
import { trackNavigation } from '../lib/trackNavigation';

jest.mock('../lib/trackNavigation', () => ({ trackNavigation: jest.fn() }));
jest.mock('../context/AuthContext', () => ({
  useAuth: () => ({ email: 'you@example.com', fullName: 'Ada Lovelace', logout: jest.fn() }),
}));
jest.mock('react-native-safe-area-context', () => ({
  useSafeAreaInsets: () => ({ top: 0, bottom: 0, left: 0, right: 0 }),
}));

// One spy per tour key, so a test can tell WHICH row a target landed on rather than only that
// some row registered. The `mock` prefix is what lets jest's hoisting allow this inside the factory.
const mockTourRegistrations: Record<string, jest.Mock> = {};
jest.mock('../onboarding/TourTargetRegistry', () => ({
  useRegisterTourTarget: (key: string) => (mockTourRegistrations[key] ??= jest.fn()),
}));

const navigation = { navigate: jest.fn() };

function renderScreen() {
  return render(<MoreScreen navigation={navigation as never} route={{ key: 'm', name: 'MoreHome' } as never} />);
}

beforeEach(() => {
  jest.clearAllMocks();
});

// [group label, [row label, route it opens, taxonomy id it reports]]. Written out in full rather
// than read from the screen's own table: a test that imports the data it checks can only fail on
// a typo in the test.
const GROUPS: [string, [string, string, string][]][] = [
  ['Money', [
    ['Accounts', 'Accounts', 'accounts'],
    ['Investments', 'Investments', 'investments'],
    ['Budgets', 'Budgets', 'budgets'],
    ['Goals', 'Goals', 'goals'],
  ]],
  ['Insights', [
    ['Reports', 'Reports', 'reports'],
    ['Advanced Reports', 'AdvancedReports', 'advanced-reports'],
    ['Ask Fyn', 'Fyn', 'ask-fyn'],
    ['Financial Memory', 'FinancialMemory', 'financial-memory'],
  ]],
  ['Statements', [
    ['Statement History', 'Statements', 'statement-history'],
    ['Review Categories', 'CategoryReview', 'review-categories'],
  ]],
  ['Plan', [
    ['Subscription', 'Subscription', 'subscription'],
    ['Refer & Earn', 'Referrals', 'referrals'],
  ]],
  ['App', [
    ['Settings', 'Settings', 'settings'],
  ]],
];
const ROWS = GROUPS.flatMap(([, rows]) => rows);

test('shows the five group headings, in order', () => {
  renderScreen();
  expect(screen.getAllByRole('header').map((h) => h.props.children)).toEqual(GROUPS.map(([label]) => label));
});

test('keeps all thirteen destinations, each exactly once', () => {
  renderScreen();
  expect(ROWS).toHaveLength(13);
  for (const [label] of ROWS) {
    expect(screen.getAllByRole('button', { name: label })).toHaveLength(1);
  }
});

test.each(ROWS)('%s opens %s and reports itself as %s', (label, route, id) => {
  renderScreen();
  fireEvent.press(screen.getByRole('button', { name: label }));
  expect(navigation.navigate).toHaveBeenCalledTimes(1);
  expect(navigation.navigate).toHaveBeenCalledWith(route);
  expect(trackNavigation).toHaveBeenCalledWith(id, 'group');
});

test('each row sits under its own heading, in the order the group lists it', () => {
  renderScreen();
  // Every heading and every menu row, in the order they are laid out on screen.
  const sequence = screen.root.findAll((node) =>
    typeof node.type === 'string'
    && (node.props.accessibilityRole === 'header'
      || (node.props.accessibilityRole === 'button' && ROWS.some(([label]) => label === node.props.accessibilityLabel)))
  ).map((node) => (node.props.accessibilityRole === 'header' ? `# ${node.props.children}` : node.props.accessibilityLabel));

  expect(sequence).toEqual(GROUPS.flatMap(([label, rows]) => [`# ${label}`, ...rows.map(([row]) => row)]));
});

test('the tour still finds the Accounts, Budgets and Goals rows after regrouping', () => {
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

test('the profile card still opens Profile', () => {
  renderScreen();
  fireEvent.press(screen.getByLabelText('Profile: Ada Lovelace'));
  expect(navigation.navigate).toHaveBeenCalledWith('Profile');
  expect(trackNavigation).toHaveBeenCalledWith('profile', 'group');
});

test('Sign out is still offered, outside the groups', () => {
  renderScreen();
  expect(screen.getByRole('button', { name: 'Sign out' })).toBeTruthy();
});
