import { fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { AxiosError, AxiosHeaders } from 'axios';
import { ReferralCodePrompt } from './ReferralCodePrompt';
import { referralsApi } from '../api/endpoints';

const mockAuth = { referralPromptPending: true, dismissReferralPrompt: jest.fn() };
jest.mock('../context/AuthContext', () => ({
  useAuth: () => mockAuth,
}));

jest.mock('../api/endpoints', () => ({
  referralsApi: { mine: jest.fn(), applyCode: jest.fn() },
}));

const api = referralsApi as jest.Mocked<typeof referralsApi>;

function mine(canApplyCode: boolean) {
  return {
    code: 'OWN00001', referrals: [], walletBalance: 0, referralCount: 0,
    plusMilestoneCounter: 0, premiumMilestoneCounter: 0, grants: [], canApplyCode,
  };
}

function conflict(message: string): AxiosError {
  const err = new AxiosError('Request failed');
  err.response = {
    status: 409, statusText: '', headers: {}, config: { headers: new AxiosHeaders() },
    data: { message },
  };
  return err;
}

function renderPrompt() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <ReferralCodePrompt />
    </QueryClientProvider>
  );
}

beforeEach(() => {
  mockAuth.referralPromptPending = true;
  mockAuth.dismissReferralPrompt.mockReset();
  api.mine.mockReset();
  api.applyCode.mockReset();
});

describe('ReferralCodePrompt', () => {
  it('asks once a Google/Apple sign-up is pending and the account can still take a code', async () => {
    api.mine.mockResolvedValue(mine(true));
    renderPrompt();

    expect(await screen.findByText('Have a referral code?')).toBeTruthy();
    expect(screen.getByText(/only use one code/)).toBeTruthy();
  });

  it('shows nothing and never asks the server when nothing is pending', async () => {
    mockAuth.referralPromptPending = false;
    renderPrompt();

    await waitFor(() => expect(api.mine).not.toHaveBeenCalled());
    expect(screen.queryByText('Have a referral code?')).toBeNull();
  });

  // Already referred, or already subscribed: nothing to ask, so the pending flag is spent.
  it('spends the flag without showing anything when the server says a code can no longer be added', async () => {
    api.mine.mockResolvedValue(mine(false));
    renderPrompt();

    await waitFor(() => expect(mockAuth.dismissReferralPrompt).toHaveBeenCalled());
    expect(screen.queryByText('Have a referral code?')).toBeNull();
  });

  it('adds the code (trimmed, upper-cased) and clears the prompt for good', async () => {
    api.mine.mockResolvedValue(mine(true));
    api.applyCode.mockResolvedValue(undefined);
    renderPrompt();

    fireEvent.changeText(await screen.findByLabelText('Referral code'), ' abcd1234 ');
    fireEvent.press(screen.getByText('Add code'));

    await waitFor(() => expect(api.applyCode).toHaveBeenCalledWith('ABCD1234'));
    await waitFor(() => expect(mockAuth.dismissReferralPrompt).toHaveBeenCalled());
  });

  it('keeps the prompt open and shows the reason when the server refuses the code', async () => {
    api.mine.mockResolvedValue(mine(true));
    api.applyCode.mockRejectedValue(conflict("You've already used a referral code."));
    renderPrompt();

    fireEvent.changeText(await screen.findByLabelText('Referral code'), 'ABCD1234');
    fireEvent.press(screen.getByText('Add code'));

    expect(await screen.findByText("You've already used a referral code.")).toBeTruthy();
    expect(mockAuth.dismissReferralPrompt).not.toHaveBeenCalled();
  });

  it('Skip clears the prompt without sending anything', async () => {
    api.mine.mockResolvedValue(mine(true));
    renderPrompt();

    fireEvent.press(await screen.findByText('Skip'));

    expect(mockAuth.dismissReferralPrompt).toHaveBeenCalled();
    expect(api.applyCode).not.toHaveBeenCalled();
  });

  it('does not submit an empty code', async () => {
    api.mine.mockResolvedValue(mine(true));
    renderPrompt();

    fireEvent.press(await screen.findByText('Add code'));

    expect(api.applyCode).not.toHaveBeenCalled();
  });
});
