import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ReferralCodePrompt } from './ReferralCodePrompt';
import { referralsApi } from '../api/endpoints';
import { useAuth } from '../context/AuthContext';

vi.mock('../api/endpoints', () => ({
  referralsApi: { mine: vi.fn(), applyCode: vi.fn() },
}));
vi.mock('../context/AuthContext', () => ({ useAuth: vi.fn() }));

const dismissReferralPrompt = vi.fn();

function mine(canApplyCode: boolean) {
  return {
    code: 'OWN00001', referrals: [], walletBalance: 0, referralCount: 0,
    plusMilestoneCounter: 0, premiumMilestoneCounter: 0, grants: [], canApplyCode,
  };
}

function renderPrompt(pending = true) {
  vi.mocked(useAuth).mockReturnValue({ referralPromptPending: pending, dismissReferralPrompt } as any);
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <ReferralCodePrompt />
    </QueryClientProvider>
  );
}

beforeEach(() => {
  dismissReferralPrompt.mockReset();
  vi.mocked(referralsApi.mine).mockReset();
  vi.mocked(referralsApi.applyCode).mockReset();
});

describe('ReferralCodePrompt (web)', () => {
  it('asks once a Google/Apple sign-up is pending and the account can still take a code', async () => {
    vi.mocked(referralsApi.mine).mockResolvedValue(mine(true));
    renderPrompt();

    expect(await screen.findByRole('dialog', { name: 'Have a referral code?' })).toBeInTheDocument();
  });

  it('renders nothing and makes no request when nothing is pending', () => {
    renderPrompt(false);

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(referralsApi.mine).not.toHaveBeenCalled();
  });

  it('spends the flag without showing anything once a code can no longer be added', async () => {
    vi.mocked(referralsApi.mine).mockResolvedValue(mine(false));
    renderPrompt();

    await waitFor(() => expect(dismissReferralPrompt).toHaveBeenCalled());
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('adds the code (trimmed, upper-cased) and clears the prompt for good', async () => {
    const user = userEvent.setup();
    vi.mocked(referralsApi.mine).mockResolvedValue(mine(true));
    vi.mocked(referralsApi.applyCode).mockResolvedValue(undefined);
    renderPrompt();

    await user.type(await screen.findByLabelText('Referral code'), ' abcd1234 ');
    await user.click(screen.getByRole('button', { name: 'Add code' }));

    await waitFor(() => expect(referralsApi.applyCode).toHaveBeenCalledWith('ABCD1234'));
    await waitFor(() => expect(dismissReferralPrompt).toHaveBeenCalled());
  });

  it("keeps the dialog open and shows the server's reason when the code is refused", async () => {
    const user = userEvent.setup();
    vi.mocked(referralsApi.mine).mockResolvedValue(mine(true));
    vi.mocked(referralsApi.applyCode).mockRejectedValue({
      response: { data: { message: "You've already used a referral code." } },
    });
    renderPrompt();

    await user.type(await screen.findByLabelText('Referral code'), 'ABCD1234');
    await user.click(screen.getByRole('button', { name: 'Add code' }));

    expect(await screen.findByRole('alert')).toHaveTextContent("You've already used a referral code.");
    expect(dismissReferralPrompt).not.toHaveBeenCalled();
  });

  it('Skip clears the prompt without sending anything', async () => {
    const user = userEvent.setup();
    vi.mocked(referralsApi.mine).mockResolvedValue(mine(true));
    renderPrompt();

    await user.click(await screen.findByRole('button', { name: 'Skip' }));

    expect(dismissReferralPrompt).toHaveBeenCalled();
    expect(referralsApi.applyCode).not.toHaveBeenCalled();
  });

  it('does not submit an empty code', async () => {
    vi.mocked(referralsApi.mine).mockResolvedValue(mine(true));
    renderPrompt();

    expect(await screen.findByRole('button', { name: 'Add code' })).toBeDisabled();
  });
});
