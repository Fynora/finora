import { render, screen, waitFor } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import Referrals from './Referrals';
import { referralsApi } from '../api/endpoints';
import type { MyReferralEntry } from '../api/endpoints';

vi.mock('../api/endpoints', () => ({
  referralsApi: { myCode: vi.fn(), mine: vi.fn(), redeem: vi.fn() },
}));

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <Referrals />
      </MemoryRouter>
    </QueryClientProvider>
  );
}

function entry(overrides: Partial<MyReferralEntry> = {}): MyReferralEntry {
  return {
    referralId: 'referral-1',
    referredUserFullName: 'Jane Doe',
    status: 'REGISTERED',
    reward: null,
    createdAt: '2026-08-20T10:00:00Z',
    ...overrides,
  };
}

describe('Referrals', () => {
  beforeEach(() => {
    vi.mocked(referralsApi.mine).mockReset();
  });

  it('shows the empty state and a zero balance when nothing has happened yet', async () => {
    vi.mocked(referralsApi.mine).mockResolvedValue({
      code: 'ABCD1234', referrals: [], walletBalance: 0, referralCount: 0,
      plusMilestoneCounter: 0, premiumMilestoneCounter: 0, grants: [],
    });
    renderPage();

    expect(await screen.findByText(/no referrals yet/i)).toBeInTheDocument();
    expect(screen.getByText('₹0')).toBeInTheDocument();
  });

  it("renders the user's own referral link once the code loads", async () => {
    vi.mocked(referralsApi.mine).mockResolvedValue({
      code: 'ABCD1234', referrals: [], walletBalance: 0, referralCount: 0,
      plusMilestoneCounter: 0, premiumMilestoneCounter: 0, grants: [],
    });
    renderPage();

    const input = await screen.findByDisplayValue(/\/register\?ref=ABCD1234$/);
    expect(input).toBeInTheDocument();
  });

  it('renders a referral row with its status and reward', async () => {
    vi.mocked(referralsApi.mine).mockResolvedValue({
      code: 'ABCD1234',
      referrals: [entry({ status: 'REWARDED', reward: 250 })],
      walletBalance: 250,
      referralCount: 1,
      plusMilestoneCounter: 0,
      premiumMilestoneCounter: 0,
      grants: [],
    });
    renderPage();

    expect(await screen.findByText('Jane Doe')).toBeInTheDocument();
    expect(screen.getByText('Rewarded')).toBeInTheDocument();
    expect(screen.getByText(/Earned ₹250/)).toBeInTheDocument();
    expect(screen.getByText('₹250')).toBeInTheDocument();
    expect(screen.queryByText(/no referrals yet/i)).not.toBeInTheDocument();
  });

  it('labels a merely-registered referral distinctly from a subscribed one', async () => {
    vi.mocked(referralsApi.mine).mockResolvedValue({
      code: 'ABCD1234',
      referrals: [entry({ status: 'SUBSCRIBED' })],
      walletBalance: 0,
      referralCount: 1,
      plusMilestoneCounter: 0,
      premiumMilestoneCounter: 0,
      grants: [],
    });
    renderPage();

    expect(await screen.findByText('Subscribed')).toBeInTheDocument();
  });

  describe('milestone redemption', () => {
    it('shows a persistent progress readout toward Plus below the threshold', async () => {
      vi.mocked(referralsApi.mine).mockResolvedValue({
        code: 'ABCD1234', referrals: [], walletBalance: 0, referralCount: 0,
        plusMilestoneCounter: 2, premiumMilestoneCounter: 2, grants: [],
      });
      renderPage();

      expect(await screen.findByText(/2\s*\/\s*3/)).toBeInTheDocument();
      expect(screen.queryByRole('button', { name: /redeem.*plus/i })).not.toBeInTheDocument();
    });

    it('shows both a redeem-Plus button and a Premium progress readout once Plus reaches 3', async () => {
      vi.mocked(referralsApi.mine).mockResolvedValue({
        code: 'ABCD1234', referrals: [], walletBalance: 0, referralCount: 0,
        plusMilestoneCounter: 3, premiumMilestoneCounter: 5, grants: [],
      });
      renderPage();

      expect(await screen.findByRole('button', { name: /redeem.*plus/i })).toBeInTheDocument();
      expect(screen.getByText(/5\s*\/\s*7/)).toBeInTheDocument();
    });

    it('shows both redeem buttons simultaneously once both thresholds are reached', async () => {
      vi.mocked(referralsApi.mine).mockResolvedValue({
        code: 'ABCD1234', referrals: [], walletBalance: 0, referralCount: 0,
        plusMilestoneCounter: 4, premiumMilestoneCounter: 7, grants: [],
      });
      renderPage();

      expect(await screen.findByRole('button', { name: /redeem.*plus/i })).toBeInTheDocument();
      expect(await screen.findByRole('button', { name: /redeem.*premium/i })).toBeInTheDocument();
    });

    it('calls referralsApi.redeem with the right tier on click', async () => {
      vi.mocked(referralsApi.mine).mockResolvedValue({
        code: 'ABCD1234', referrals: [], walletBalance: 0, referralCount: 0,
        plusMilestoneCounter: 3, premiumMilestoneCounter: 3, grants: [],
      });
      vi.mocked(referralsApi.redeem).mockResolvedValue(undefined);
      renderPage();

      const button = await screen.findByRole('button', { name: /redeem.*plus/i });
      button.click();
      await waitFor(() => expect(referralsApi.redeem).toHaveBeenCalledWith('PLUS'));
    });
  });
});
