import type { ComponentType, ReactNode } from 'react';
import { fireEvent, render, screen } from '@testing-library/react-native';
import { Text } from 'react-native';
import { AppTabs } from './AppTabs';
import { TourTargetProvider, useTourTarget } from '../onboarding/TourTargetRegistry';

// AppTabs calls useNavigation() directly (for the floating "+" button's QuickActionSheet) -- in
// the real app that resolves against RootNavigator.tsx's one NavigationContainer, but this file
// renders AppTabs in isolation with no NavigationContainer at all (Tab/Stack navigators are
// mocked below), so useNavigation() would otherwise throw. mockNavigate is asserted against by
// the new "floating + button" test further down.
const mockNavigate = jest.fn();
jest.mock('@react-navigation/native', () => ({
  useNavigation: () => ({ navigate: mockNavigate }),
}));

// Same pre-existing ESM/native-stack gap RootNavigator.test.tsx's own mock comment documents --
// @react-navigation/bottom-tabs' published "main" is the same kind of unbuilt ESM file. Mocked
// here, scoped to this file, for the same reason.
jest.mock('@react-navigation/bottom-tabs', () => ({
  createBottomTabNavigator: () => ({
    // Exercises the real tabBarIcon function (where the ref-registration this test is actually
    // about lives) for each of AppTabs' own <Tab.Screen> children, the same way the real
    // navigator would call it once per tab -- without that, this mock would render AppTabs
    // without ever calling the code under test. Untyped/plain on purpose: jest.mock's factory is
    // hoisted above imports, and an inline TS parameter type here confuses the "out-of-scope
    // variable" check with a false positive on the type name itself.
    Navigator: (props: any) => {
      const { Children, isValidElement, Fragment, createElement } = require('react');
      const { Text } = require('react-native');
      const screens = Children.toArray(props.children).filter((child: any) => isValidElement(child));
      return createElement(
        Fragment,
        null,
        screens.map((child: any) => {
          const name = child.props.name;
          const options = props.screenOptions ? props.screenOptions({ route: { name } }) : {};
          const icon = typeof options.tabBarIcon === 'function'
            ? options.tabBarIcon({ focused: false, color: '#000', size: 20 })
            : null;
          // Also exercises a per-Tab.Screen `options.tabBarButton` override (the floating "+"
          // button's Import tab) -- real react-native-navigation calls this per-screen options
          // function the same way it calls the Navigator-level screenOptions above.
          const button = typeof child.props.options?.tabBarButton === 'function'
            ? child.props.options.tabBarButton({})
            : null;
          return createElement(Fragment, { key: name }, createElement(Text, null, icon), button);
        })
      );
    },
    Screen: () => null,
  }),
}));

jest.mock('@react-navigation/native-stack', () => ({
  createNativeStackNavigator: () => ({
    Navigator: ({ children }: { children: ReactNode }) => children,
    Screen: ({ component: Component }: { component: ComponentType }) => <Component />,
  }),
}));

jest.mock('../theme', () => ({
  useTheme: () => ({
    bg: '#fff', primary: '#000', card: '#fff', ink: '#000', border: '#ccc', muted: '#888', onPrimary: '#fff',
  }),
  radius: { md: 8, lg: 12, xl: 16 },
  spacing: { xs: 4, sm: 8, md: 16, lg: 24, xl: 32 },
}));

// Every screen AppTabs mounts (directly or via MoreNavigator) is stubbed -- this test is about
// the tab-icon ref registration AppTabs itself owns, not any individual screen's rendering.
jest.mock('../screens/DashboardScreen', () => ({ DashboardScreen: () => null }));
jest.mock('../screens/LedgerScreen', () => ({ LedgerScreen: () => null }));
jest.mock('../screens/AccountsScreen', () => ({ AccountsScreen: () => null }));
jest.mock('../screens/StatementHistoryScreen', () => ({ StatementHistoryScreen: () => null }));
jest.mock('../screens/import/ImportScreen', () => ({ ImportScreen: () => null }));
jest.mock('../screens/MoreScreen', () => ({ MoreScreen: () => null }));
jest.mock('../screens/CategoryReviewScreen', () => ({ CategoryReviewScreen: () => null }));
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
});
