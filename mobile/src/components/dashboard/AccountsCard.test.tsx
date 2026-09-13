import { useEffect } from 'react';
import { StyleSheet } from 'react-native';
import { fireEvent, render, screen } from '@testing-library/react-native';
import { AccountsCard } from './AccountsCard';
import { ThemeProvider, useThemeSetting } from '../../theme';
import { dark, light } from '../../theme/palette';
import type { Account } from '../../types';

// No existing test in this codebase forces dark mode (checked before writing this) -- the app
// exposes no ThemeProvider prop for it, only the real useThemeSetting().setSetting() switch a
// user would tap. Flips it on mount via that same public API rather than mocking react-native's
// useColorScheme, so this exercises the actual code path, not a reimplementation of it.
function ForceDarkTheme({ children }: { children: React.ReactNode }) {
  const { setSetting } = useThemeSetting();
  useEffect(() => { setSetting('dark'); }, [setSetting]);
  return <>{children}</>;
}

function account(overrides: Partial<Account>): Account {
  return {
    id: 'a1', name: 'Salary Account', accountType: 'SAVINGS', balance: 100000,
    bank: { id: 'hdfc', officialName: 'HDFC Bank', shortName: 'HDFC', colorHex: '#004C8F', initials: 'HD', logoPath: '', category: 'PRIVATE', websiteUrl: null, ifscPrefix: null, supportedAccountTypes: [] },
    lastImportedAt: null, lastStatementPeriodStart: null, lastStatementPeriodEnd: null,
    statementsCount: 0, transactionsCount: 0, status: 'ACTIVE',
    ...overrides,
  } as Account;
}

describe('AccountsCard', () => {
  it('shows account/bank counts, total balance and caption', () => {
    render(
      <ThemeProvider>
        <AccountsCard
          totalBalance={1248320}
          caption="As of today"
          onViewAll={jest.fn()}
          accounts={[
            account({ id: 'a1', bank: { ...account({}).bank, id: 'hdfc', shortName: 'HDFC', initials: 'HD' } }),
            account({ id: 'a2', bank: { ...account({}).bank, id: 'sbi', shortName: 'SBI', initials: 'SB' } }),
          ]}
        />
      </ThemeProvider>
    );

    expect(screen.getByText('2 Accounts · 2 Banks')).toBeTruthy();
    expect(screen.getByText('₹12,48,320')).toBeTruthy();
    expect(screen.getByText('As of today')).toBeTruthy();
    expect(screen.getByText('HD')).toBeTruthy();
    expect(screen.getByText('SB')).toBeTruthy();
  });

  it('navigates on View Accounts', () => {
    const onViewAll = jest.fn();
    render(<ThemeProvider><AccountsCard accounts={[]} totalBalance={0} caption="As of today" onViewAll={onViewAll} /></ThemeProvider>);
    fireEvent.press(screen.getByText('View Accounts'));
    expect(onViewAll).toHaveBeenCalled();
  });

  // Regression test for the bug fixed alongside this: the avatar-stack separator ring used to be
  // a hardcoded white borderColor, which rendered as a bright halo on a dark DashboardCard. Light
  // mode alone can't catch this -- light.card is '#ffffff', the exact value the bug hardcoded, so
  // a light-only test would pass whether or not the fix was actually applied. Checked before
  // writing this: light.card === '#ffffff' is real (verified in theme/palette.ts), not assumed.
  it('rings the avatar stack with the theme card color, not a hardcoded white', () => {
    const accounts = [
      account({ id: 'a1', bank: { ...account({}).bank, id: 'hdfc', shortName: 'HDFC', initials: 'HD' } }),
    ];

    const { unmount } = render(
      <ThemeProvider>
        <AccountsCard totalBalance={0} caption="As of today" onViewAll={jest.fn()} accounts={accounts} />
      </ThemeProvider>
    );
    expect(StyleSheet.flatten(screen.getByTestId('account-avatar-a1').props.style).borderColor).toBe(light.card);
    unmount();

    render(
      <ThemeProvider>
        <ForceDarkTheme>
          <AccountsCard totalBalance={0} caption="As of today" onViewAll={jest.fn()} accounts={accounts} />
        </ForceDarkTheme>
      </ThemeProvider>
    );
    expect(StyleSheet.flatten(screen.getByTestId('account-avatar-a1').props.style).borderColor).toBe(dark.card);
    // Not '#FFFFFF' or '#ffffff' -- the actual old bug, spelled out explicitly so this test still
    // fails loudly even if a future palette change ever made dark.card equal white again.
    expect(StyleSheet.flatten(screen.getByTestId('account-avatar-a1').props.style).borderColor).not.toBe('#FFFFFF');
  });

  it('rings the "+N overflow" avatar with the theme card color too', () => {
    const accounts = Array.from({ length: 6 }, (_, i) =>
      account({ id: `a${i}`, bank: { ...account({}).bank, id: `bank${i}`, initials: `B${i}` } })
    );

    render(
      <ThemeProvider>
        <ForceDarkTheme>
          <AccountsCard totalBalance={0} caption="As of today" onViewAll={jest.fn()} accounts={accounts} />
        </ForceDarkTheme>
      </ThemeProvider>
    );
    expect(StyleSheet.flatten(screen.getByTestId('account-avatar-overflow').props.style).borderColor).toBe(dark.card);
  });
});
