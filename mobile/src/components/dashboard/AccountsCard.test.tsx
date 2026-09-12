import { fireEvent, render, screen } from '@testing-library/react-native';
import { AccountsCard } from './AccountsCard';
import { ThemeProvider } from '../../theme';
import type { Account } from '../../types';

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
});
