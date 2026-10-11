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

// RNTL's default text normalizer turns a no-break space into an ordinary one; these compare
// the text exactly as it is written.
const AS_WRITTEN = { normalizer: (text: string) => text };

const twoAccounts = [
  account({ id: 'a1', bank: { ...account({}).bank, id: 'hdfc', shortName: 'HDFC', initials: 'HD' } }),
  account({ id: 'a2', bank: { ...account({}).bank, id: 'sbi', shortName: 'SBI', initials: 'SB' } }),
];

describe('AccountsCard', () => {
  // The approved design: one row under the balance (the date, then the counts) and an arrow
  // that opens Accounts. The card used to carry a second row with the counts and a "View
  // Accounts" text link; at an accessibility text size that row broke "Accounts" mid-word.
  it('puts the date and the counts on one line under the balance, with no second row', () => {
    render(<ThemeProvider><AccountsCard accounts={twoAccounts} totalBalance={0} caption="As of today" onViewAll={jest.fn()} /></ThemeProvider>);
    expect(screen.getByText('As of today')).toBeTruthy();
    expect(screen.getByText(' · 2\u00A0accounts · 2\u00A0banks', AS_WRITTEN)).toBeTruthy();
    expect(screen.queryByTestId('accounts-card-footer')).toBeNull();
    expect(screen.queryByText('View Accounts')).toBeNull();
  });

  // A count and its noun stay together when the line wraps (a no-break space between them):
  // on an iPhone 17 Pro simulator the line broke as "... 3" / "banks".
  it('counts one account at one bank in the singular', () => {
    render(<ThemeProvider><AccountsCard accounts={[twoAccounts[0]]} totalBalance={0} caption="As of today" onViewAll={jest.fn()} /></ThemeProvider>);
    expect(screen.getByText(' · 1\u00A0account · 1\u00A0bank', AS_WRITTEN)).toBeTruthy();
  });

  it('writes the counts alone, with no stray separator, when there is no date to show', () => {
    render(<ThemeProvider><AccountsCard accounts={twoAccounts} totalBalance={0} caption="" onViewAll={jest.fn()} /></ThemeProvider>);
    expect(screen.getByText('2\u00A0accounts · 2\u00A0banks', AS_WRITTEN)).toBeTruthy();
  });

  // The counts sit inside the balance's one accessible node, so they have to be in its label.
  it('reads the balance, the date and the counts as one item', () => {
    render(<ThemeProvider><AccountsCard accounts={twoAccounts} totalBalance={1248320} caption="As of today" onViewAll={jest.fn()} /></ThemeProvider>);
    expect(screen.getByLabelText('Total Balance: ₹12,48,320, As of today, 2 accounts, 2 banks')).toBeTruthy();
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

    expect(screen.getByText(' · 2\u00A0accounts · 2\u00A0banks', AS_WRITTEN)).toBeTruthy();
    expect(screen.getByText('₹12,48,320')).toBeTruthy();
    expect(screen.getByText('As of today')).toBeTruthy();
    expect(screen.getByText('HD')).toBeTruthy();
    expect(screen.getByText('SB')).toBeTruthy();
  });

  it('navigates on View Accounts', () => {
    const onViewAll = jest.fn();
    render(<ThemeProvider><AccountsCard accounts={[]} totalBalance={0} caption="As of today" onViewAll={onViewAll} /></ThemeProvider>);
    fireEvent.press(screen.getByRole('button', { name: 'View Accounts' }));
    expect(onViewAll).toHaveBeenCalled();
  });

  it('gives View Accounts a 44 point target', () => {
    render(<ThemeProvider><AccountsCard accounts={[]} totalBalance={0} caption="As of today" onViewAll={jest.fn()} /></ThemeProvider>);
    const target = StyleSheet.flatten(screen.getByLabelText('View Accounts').props.style);
    expect(target.minHeight).toBe(44);
    expect(target.minWidth).toBe(44);
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
