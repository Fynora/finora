import type { ComponentType, ReactNode } from 'react';
import { fireEvent, render, screen } from '@testing-library/react-native';
import { Text } from 'react-native';
import { AppTabs } from './AppTabs';
import { TourTargetProvider, useTourTarget } from '../onboarding/TourTargetRegistry';
import { expectHaptics } from '../test/haptics';

// AppTabs calls useNavigation() directly (for the floating "+" button's QuickActionSheet) -- in
// the real app that resolves against RootNavigator.tsx's one NavigationContainer, but this file
// renders AppTabs in isolation with no NavigationContainer at all (Tab/Stack navigators are
// mocked below), so useNavigation() would otherwise throw. mockNavigate is asserted against by
// the new "floating + button" test further down.
const mockNavigate = jest.fn();
// The Insights tab's dot reads this count (lib/useInsightsLookAtCount has its own tests); a fixed
// value here keeps the tab bar free of a query client.
let mockLookAtCount = 0;
jest.mock('../lib/useInsightsLookAtCount', () => ({ useInsightsLookAtCount: () => mockLookAtCount }));
jest.mock('@react-navigation/native', () => ({
  useNavigation: () => ({ navigate: mockNavigate }),
  // The floating pill builds its navigate action with this, same as the library's own bar.
  CommonActions: { navigate: (name: string, params?: object) => ({ type: 'NAVIGATE', payload: { name, params } }) },
}));

// Same pre-existing ESM/native-stack gap RootNavigator.test.tsx's own mock comment documents --
// @react-navigation/bottom-tabs' published "main" is the same kind of unbuilt ESM file. Mocked
// here, scoped to this file, for the same reason.
jest.mock('@react-navigation/bottom-tabs', () => {
  const { Children, isValidElement, createContext } = require('react');
  return {
    BottomTabBarHeightCallbackContext: createContext(undefined),
    createBottomTabNavigator: () => ({
      // Builds the same state/descriptors the real navigator hands its `tabBar`, so the pill under
      // test renders each tab's real tabBarIcon (where tour registration lives) and the Import
      // tab's real tabBarButton (the "+"). Untyped on purpose: jest.mock's factory is hoisted
      // above imports, and an inline TS parameter type here confuses the "out-of-scope variable"
      // check with a false positive on the type name itself.
      Navigator: (props: any) => {
        const screens = Children.toArray(props.children).filter((child: any) => isValidElement(child));
        const routes = screens.map((child: any) => ({ key: `${child.props.name}-k`, name: child.props.name, params: undefined }));
        const descriptors = Object.fromEntries(screens.map((child: any, i: number) => [routes[i].key, {
          options: { ...(props.screenOptions ? props.screenOptions({ route: routes[i] }) : {}), ...(child.props.options ?? {}) },
        }]));
        const navigation = { emit: jest.fn(() => ({ defaultPrevented: false })), dispatch: jest.fn() };
        return props.tabBar({ state: { key: 'tabs-k', index: 0, routes }, descriptors, navigation, insets: { top: 0, bottom: 34, left: 0, right: 0 } });
      },
      Screen: () => null,
    }),
  };
});

jest.mock('@react-navigation/native-stack', () => ({
  createNativeStackNavigator: () => ({
    Navigator: ({ children }: { children: ReactNode }) => children,
    Screen: ({ component: Component }: { component: ComponentType }) => <Component />,
  }),
}));

jest.mock('../theme', () => ({
  useTheme: () => ({
    bg: '#fff', primary: '#000', card: '#fff', ink: '#000', border: '#ccc', muted: '#888', onPrimary: '#fff',
    // GlassScreen/GlassSurface (the tab-bar background and the tabs root) read these.
    glassTint: '#FFFFFF', glassAlpha: 0.72, glassEdge: 'rgba(255,255,255,0.85)',
  }),
  radius: { md: 8, lg: 12, xl: 16 },
  spacing: { xs: 4, sm: 8, md: 16, lg: 24, xl: 32 },
  // The floating pill reads these.
  typography: { caption: {}, labelS: {} },
  shadows: { card: [] },
}));

jest.mock('../lib/useKeyboardVisible', () => ({ useKeyboardVisible: () => false }));

// Every screen AppTabs mounts (directly or via MoreNavigator) is stubbed -- this test is about
// the tab-icon ref registration AppTabs itself owns, not any individual screen's rendering.
jest.mock('../screens/DashboardScreen', () => ({ DashboardScreen: () => null }));
jest.mock('../screens/LedgerScreen', () => ({ LedgerScreen: () => null }));
jest.mock('../screens/AccountsScreen', () => ({ AccountsScreen: () => null }));
jest.mock('../screens/StatementHistoryScreen', () => ({ StatementHistoryScreen: () => null }));
jest.mock('../screens/import/ImportScreen', () => ({ ImportScreen: () => null }));
jest.mock('../screens/MoreScreen', () => ({ MoreScreen: () => null }));
jest.mock('../screens/CategoryReviewScreen', () => ({ CategoryReviewScreen: () => null }));
jest.mock('../screens/MoneyReviewScreen', () => ({ MoneyReviewScreen: () => null }));
jest.mock('../screens/GmailReviewScreen', () => ({ GmailReviewScreen: () => null }));
jest.mock('../screens/AdvancedReportsScreen', () => ({ AdvancedReportsScreen: () => null }));
jest.mock('../screens/BudgetsScreen', () => ({ BudgetsScreen: () => null }));
jest.mock('../screens/SubscriptionScreen', () => ({ SubscriptionScreen: () => null }));
jest.mock('../screens/GoalsScreen', () => ({ GoalsScreen: () => null }));
jest.mock('../screens/ReportsScreen', () => ({ ReportsScreen: () => null }));
jest.mock('../screens/InsightsScreen', () => ({ InsightsScreen: () => null }));
jest.mock('../screens/InvestmentsScreen', () => ({ InvestmentsScreen: () => null }));
jest.mock('../screens/ProfileScreen', () => ({ ProfileScreen: () => null }));
jest.mock('../screens/ReferralsScreen', () => ({ ReferralsScreen: () => null }));
jest.mock('../screens/SettingsScreen', () => ({ SettingsScreen: () => null }));
// Settings redesign: SettingsGeneralScreen and SettingsAccountScreen import the real
// AuthContext (for setOnboardingCompleted/logout), which imports revenueCat.ts, which imports
// react-native-purchases -- an ESM-only package Jest under Node 22 (this repo's CI runner) can't
// require as CommonJS. Stubbing every new screen here, same as every other screen above, avoids
// ever loading that chain in this file. Same pre-existing gap RootNavigator.test.tsx's own
// native-stack mock comment documents; SettingsGeneralScreen.test.tsx and
// SettingsAccountScreen.test.tsx already mock AuthContext directly for the same reason.
jest.mock('../screens/SettingsGeneralScreen', () => ({ SettingsGeneralScreen: () => null }));
jest.mock('../screens/SettingsSecurityScreen', () => ({ SettingsSecurityScreen: () => null }));
jest.mock('../screens/SettingsCategorizationScreen', () => ({ SettingsCategorizationScreen: () => null }));
jest.mock('../screens/SettingsDataScreen', () => ({ SettingsDataScreen: () => null }));
jest.mock('../screens/SettingsConnectedAppsScreen', () => ({ SettingsConnectedAppsScreen: () => null }));
jest.mock('../screens/SettingsBankSyncScreen', () => ({ SettingsBankSyncScreen: () => null }));
jest.mock('../screens/SettingsBankSyncConfirmScreen', () => ({ SettingsBankSyncConfirmScreen: () => null }));
jest.mock('../screens/SettingsAccountScreen', () => ({ SettingsAccountScreen: () => null }));
jest.mock('../screens/SupportTicketDetailScreen', () => ({ SupportTicketDetailScreen: () => null }));
jest.mock('../screens/SupportTicketsScreen', () => ({ SupportTicketsScreen: () => null }));
jest.mock('../screens/settings/VerifyEmailChangeScreen', () => ({ VerifyEmailChangeScreen: () => null }));

function TargetProbe({ tourKey }: { tourKey: string }) {
  const target = useTourTarget(tourKey);
  return <Text testID={`probe-${tourKey}`}>{target ? 'found' : 'missing'}</Text>;
}

describe('AppTabs tour target registration', () => {
  it('registers home/transactions/import/insights refs on a real TourTargetProvider, without throwing', () => {
    render(
      <TourTargetProvider>
        <AppTabs />
        <TargetProbe tourKey="home" />
        <TargetProbe tourKey="transactions" />
        <TargetProbe tourKey="import" />
        <TargetProbe tourKey="insights" />
      </TourTargetProvider>
    );

    expect(screen.getByTestId('probe-home')).toHaveTextContent('found');
    expect(screen.getByTestId('probe-transactions')).toHaveTextContent('found');
    expect(screen.getByTestId('probe-import')).toHaveTextContent('found');
    expect(screen.getByTestId('probe-insights')).toHaveTextContent('found');
  });

  it('throws a clear error if rendered without a TourTargetProvider (this is why RootNavigator always wraps one)', () => {
    // console.error is expected here -- React logs the thrown error before this assertion sees it.
    const consoleErrorSpy = jest.spyOn(console, 'error').mockImplementation(() => {});
    expect(() => render(<AppTabs />)).toThrow('useRegisterTourTarget must be used within TourTargetProvider');
    consoleErrorSpy.mockRestore();
  });
});

describe('AppTabs floating tab pill', () => {
  it('draws the tabs in the floating glass pill, "+" in the middle', () => {
    render(
      <TourTargetProvider>
        <AppTabs />
      </TourTargetProvider>
    );
    expect(screen.getByTestId('tab-pill-glass')).toBeTruthy();
    const order = screen.getAllByRole('button').map((b) => b.props.accessibilityLabel);
    expect(order).toEqual(['Home', 'Transactions', 'Quick actions', 'Insights', 'More']);
  });
});

describe('AppTabs floating + button', () => {
  beforeEach(() => mockNavigate.mockClear());

  it('opens the quick-action sheet and navigates on each row, closing after', () => {
    render(
      <TourTargetProvider>
        <AppTabs />
      </TourTargetProvider>
    );

    fireEvent.press(screen.getByLabelText('Quick actions'));
    expect(screen.getByText('Import Statement')).toBeTruthy();

    fireEvent.press(screen.getByText('Import Statement'));
    expect(mockNavigate).toHaveBeenCalledWith('Import');
    // Sheet closes after a row is picked.
    expect(screen.queryByText('Import Statement')).toBeNull();

    fireEvent.press(screen.getByLabelText('Quick actions'));
    fireEvent.press(screen.getByText('Add Transaction'));
    expect(mockNavigate).toHaveBeenCalledWith('Home', { openAddTransaction: true, nonce: expect.any(Number) });

    fireEvent.press(screen.getByLabelText('Quick actions'));
    fireEvent.press(screen.getByText('Add Goal'));
    expect(mockNavigate).toHaveBeenCalledWith('More', { screen: 'Goals' });
  });

  it('ticks once on "+", and not again for the row that opens a screen', () => {
    render(
      <TourTargetProvider>
        <AppTabs />
      </TourTargetProvider>
    );
    fireEvent.press(screen.getByLabelText('Quick actions'));
    expectHaptics({ selection: 1 });
    fireEvent.press(screen.getByText('Import Statement'));
    expectHaptics({ selection: 1 });
  });
});

describe('AppTabs Insights dot', () => {
  afterEach(() => { mockLookAtCount = 0; });

  function showTabs() {
    render(
      <TourTargetProvider>
        <AppTabs />
      </TourTargetProvider>
    );
  }

  it('shows no dot and the plain label when nothing needs a look', () => {
    showTabs();
    expect(screen.queryByTestId('insights-tab-dot')).toBeNull();
    expect(screen.getByRole('button', { name: 'Insights' })).toBeTruthy();
  });

  it('shows a dot, and says so to a screen reader, while something needs a look', () => {
    mockLookAtCount = 2;
    showTabs();
    expect(screen.getByTestId('insights-tab-dot')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Insights, has things to look at' })).toBeTruthy();
  });
});
