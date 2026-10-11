import { useEffect } from 'react';
import { Dimensions, StyleSheet } from 'react-native';
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

const dimensionsGetSpy = jest.spyOn(Dimensions, 'get');
beforeEach(() => {
  dimensionsGetSpy.mockReturnValue({ width: 390, height: 844, scale: 2, fontScale: 1 });
});

const twoAccounts = [
  account({ id: 'a1', bank: { ...account({}).bank, id: 'hdfc', shortName: 'HDFC', initials: 'HD' } }),
  account({ id: 'a2', bank: { ...account({}).bank, id: 'sbi', shortName: 'SBI', initials: 'SB' } }),
];

describe('AccountsCard', () => {
  // Found on an iPhone 17 Pro simulator at an accessibility text size: the counts and "View
  // Accounts" shared one row, the link took most of it, and "3 Accounts · 3 Banks" was squeezed
  // into a column so narrow that "Accounts" broke in the middle of the word.
  it('stacks the counts above View Accounts under large Dynamic Type, and keeps them side by side otherwise', () => {
    render(<ThemeProvider><AccountsCard accounts={twoAccounts} totalBalance={0} caption="As of today" onViewAll={jest.fn()} /></ThemeProvider>);
    expect(StyleSheet.flatten(screen.getByTestId('accounts-card-footer').props.style).flexDirection).toBe('row');

    dimensionsGetSpy.mockReturnValue({ width: 390, height: 844, scale: 2, fontScale: 1.3 });
    render(<ThemeProvider><AccountsCard accounts={twoAccounts} totalBalance={0} caption="As of today" onViewAll={jest.fn()} /></ThemeProvider>);
    const stacked = StyleSheet.flatten(screen.getByTestId('accounts-card-footer').props.style);
    expect(stacked.flexDirection).toBe('column');
    expect(stacked.alignItems).toBe('flex-start');
  });

  // Same simulator run: the initials grew with the text size and the circle did not, so "BB",
  // "PNB" and "AXIS" ran out of their circles and into each other. The avatar is a fixed-size
  // graphic, so its text does not scale.
  it('keeps bank initials inside their fixed-size circles at any text size', () => {
    render(
      <ThemeProvider>
        <AccountsCard accounts={Array.from({ length: 5 }, (_, i) => account({ id: `a${i}` }))} totalBalance={0} caption="As of today" onViewAll={jest.fn()} />
      </ThemeProvider>
    );
    for (const initials of screen.getAllByText('HD')) expect(initials.props.maxFontSizeMultiplier).toBe(1);
    expect(screen.getByText('+1').props.maxFontSizeMultiplier).toBe(1);
  });

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

  it('gives View Accounts a 44 point target', () => {
    render(<ThemeProvider><AccountsCard accounts={[]} totalBalance={0} caption="As of today" onViewAll={jest.fn()} /></ThemeProvider>);
    const target = screen.getByLabelText('View Accounts');
    expect(StyleSheet.flatten(target.props.style).minHeight).toBe(44);
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
