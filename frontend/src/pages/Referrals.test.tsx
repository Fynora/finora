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

    it('shows the server error message under the right tier when redemption fails', async () => {
      vi.mocked(referralsApi.mine).mockResolvedValue({
        code: 'ABCD1234', referrals: [], walletBalance: 0, referralCount: 0,
        plusMilestoneCounter: 3, premiumMilestoneCounter: 3, grants: [],
      });
      vi.mocked(referralsApi.redeem).mockRejectedValue({
        response: { data: { message: 'This reward was just redeemed by another request.' } },
      });
      renderPage();

      const button = await screen.findByRole('button', { name: /redeem.*plus/i });
      button.click();

      expect(await screen.findByText('This reward was just redeemed by another request.')).toBeInTheDocument();
    });
  });

  describe('upgrade celebration', () => {
    beforeEach(() => {
      localStorage.clear();
    });

    it('shows the celebration once for a newly-active grant it has not shown before', async () => {
      vi.mocked(referralsApi.mine).mockResolvedValue({
        code: 'ABCD1234', referrals: [], walletBalance: 0, referralCount: 0,
        plusMilestoneCounter: 0, premiumMilestoneCounter: 0,
        grants: [{ id: 'grant-1', tier: 'PREMIUM', status: 'ACTIVE', activatedAt: '2026-09-14T00:00:00Z', expiresAt: '2026-10-14T00:00:00Z' }],
      });
      renderPage();

      expect(await screen.findByTestId('upgrade-celebration')).toBeInTheDocument();
    });

    it('does not show the celebration again for a grant already recorded as seen', async () => {
      localStorage.setItem('finora_seen_active_referral_grants', JSON.stringify(['grant-1']));
      vi.mocked(referralsApi.mine).mockResolvedValue({
        code: 'ABCD1234', referrals: [], walletBalance: 0, referralCount: 0,
        plusMilestoneCounter: 0, premiumMilestoneCounter: 0,
        grants: [{ id: 'grant-1', tier: 'PREMIUM', status: 'ACTIVE', activatedAt: '2026-09-14T00:00:00Z', expiresAt: '2026-10-14T00:00:00Z' }],
      });
      renderPage();

      await screen.findByText(/no referrals yet/i);
      expect(screen.queryByTestId('upgrade-celebration')).not.toBeInTheDocument();
    });

    it('does not show the celebration for a PENDING (not yet activated) grant', async () => {
      vi.mocked(referralsApi.mine).mockResolvedValue({
        code: 'ABCD1234', referrals: [], walletBalance: 0, referralCount: 0,
        plusMilestoneCounter: 0, premiumMilestoneCounter: 0,
        grants: [{ id: 'grant-1', tier: 'PLUS', status: 'PENDING', activatedAt: null, expiresAt: null }],
      });
      renderPage();

      await screen.findByText(/no referrals yet/i);
      expect(screen.queryByTestId('upgrade-celebration')).not.toBeInTheDocument();
    });
  });

  describe('your rewards (grant status)', () => {
    it('shows an ACTIVE grant with its expiry date', async () => {
      vi.mocked(referralsApi.mine).mockResolvedValue({
        code: 'ABCD1234', referrals: [], walletBalance: 0, referralCount: 0,
        plusMilestoneCounter: 0, premiumMilestoneCounter: 0,
        grants: [{ id: 'grant-1', tier: 'PREMIUM', status: 'ACTIVE', activatedAt: '2026-09-14T00:00:00Z', expiresAt: '2026-10-14T00:00:00Z' }],
      });
      renderPage();

      expect(await screen.findByText(/Premium active/i)).toBeInTheDocument();
    });

    it('shows a PENDING grant as queued', async () => {
      vi.mocked(referralsApi.mine).mockResolvedValue({
        code: 'ABCD1234', referrals: [], walletBalance: 0, referralCount: 0,
        plusMilestoneCounter: 0, premiumMilestoneCounter: 0,
        grants: [{ id: 'grant-1', tier: 'PLUS', status: 'PENDING', activatedAt: null, expiresAt: null }],
      });
      renderPage();

      expect(await screen.findByText(/Plus queued/i)).toBeInTheDocument();
    });

    it('shows nothing for an EXPIRED grant with no other active/pending ones', async () => {
      vi.mocked(referralsApi.mine).mockResolvedValue({
        code: 'ABCD1234', referrals: [], walletBalance: 0, referralCount: 0,
        plusMilestoneCounter: 0, premiumMilestoneCounter: 0,
        grants: [{ id: 'grant-1', tier: 'PLUS', status: 'EXPIRED', activatedAt: '2026-08-01T00:00:00Z', expiresAt: '2026-08-31T00:00:00Z' }],
      });
      renderPage();

      await screen.findByText(/no referrals yet/i);
      expect(screen.queryByText('Your rewards')).not.toBeInTheDocument();
    });
  });
});
