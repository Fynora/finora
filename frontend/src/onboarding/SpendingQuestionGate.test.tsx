import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { SpendingQuestionGate } from './SpendingQuestionGate';
import { SPENDING_TRACKING_OPTIONS } from './SpendingTrackingQuestion';
import { onboardingApi } from '../api/endpoints';

vi.mock('../api/endpoints', () => ({
  onboardingApi: { status: vi.fn(), setSpendingTracking: vi.fn() },
}));
const { logout } = vi.hoisted(() => ({ logout: vi.fn() }));
vi.mock('../context/AuthContext', () => ({ useAuth: () => ({ email: 'gate-test@example.com', logout }) }));

function renderGate(holdWhileLoading = false) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <SpendingQuestionGate holdWhileLoading={holdWhileLoading}>
        <p>The app</p>
      </SpendingQuestionGate>
    </QueryClientProvider>
  );
}

const unanswered = { onboardingCompleted: true, financialFocus: [], spendingTrackingMethod: null };

beforeEach(() => {
  vi.mocked(onboardingApi.status).mockReset();
  vi.mocked(onboardingApi.setSpendingTracking).mockReset();
  logout.mockReset();
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe('SpendingQuestionGate', () => {
  it('shows the app, and no question, once the question has been answered', async () => {
    vi.mocked(onboardingApi.status).mockResolvedValue({ ...unanswered, spendingTrackingMethod: 'PAPER' });
    renderGate();

    expect(await screen.findByText('The app')).toBeInTheDocument();
    await waitFor(() => expect(onboardingApi.status).toHaveBeenCalled());
    expect(screen.queryByText('How do you keep track of your spending today?')).not.toBeInTheDocument();
  });

  it('shows only the question while it is unanswered -- even to someone who finished onboarding', async () => {
    vi.mocked(onboardingApi.status).mockResolvedValue(unanswered);
    renderGate();

    expect(await screen.findByText('How do you keep track of your spending today?')).toBeInTheDocument();
    expect(screen.queryByText('The app')).not.toBeInTheDocument();
    expect(screen.getAllByRole('radio')).toHaveLength(SPENDING_TRACKING_OPTIONS.length);
    // No way past it without an answer: Continue starts disabled and there is no skip.
    expect(screen.getByRole('button', { name: 'Continue' })).toBeDisabled();
    expect(screen.queryByRole('button', { name: /skip/i })).not.toBeInTheDocument();
  });

  it('saves the chosen answer and then lets the user through', async () => {
    vi.mocked(onboardingApi.status).mockResolvedValue(unanswered);
    vi.mocked(onboardingApi.setSpendingTracking).mockResolvedValue({ ...unanswered, spendingTrackingMethod: 'SPREADSHEET' });
    renderGate();

    fireEvent.click(await screen.findByRole('radio', { name: 'In a spreadsheet (Excel, Google Sheets)' }));
    expect(screen.getByRole('radio', { name: 'In a spreadsheet (Excel, Google Sheets)' })).toHaveAttribute('aria-checked', 'true');
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Continue' }));
    });

    expect(onboardingApi.setSpendingTracking).toHaveBeenCalledWith('SPREADSHEET');
    expect(await screen.findByText('The app')).toBeInTheDocument();
  });

  it('keeps the question up, with a message, when saving fails', async () => {
    vi.mocked(onboardingApi.status).mockResolvedValue(unanswered);
    vi.mocked(onboardingApi.setSpendingTracking).mockRejectedValue(new Error('offline'));
    renderGate();

    fireEvent.click(await screen.findByRole('radio', { name: 'Roughly, in my head' }));
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Continue' }));
    });

    expect(await screen.findByRole('alert')).toHaveTextContent('Check your connection');
    expect(screen.queryByText('The app')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Continue' })).toBeEnabled();
  });

  it('offers a way to sign out without answering, which saves nothing', async () => {
    vi.mocked(onboardingApi.status).mockResolvedValue(unanswered);
    renderGate();

    fireEvent.click(await screen.findByRole('button', { name: 'Sign out' }));

    expect(logout).toHaveBeenCalledTimes(1);
    expect(onboardingApi.setSpendingTracking).not.toHaveBeenCalled();
  });

  it('holds someone who has not finished onboarding on the loader until the answer is known', async () => {
    let resolve!: (v: typeof unanswered) => void;
    vi.mocked(onboardingApi.status).mockReturnValue(new Promise((r) => { resolve = r; }));
    renderGate(true);

    expect(screen.getByRole('status')).toHaveTextContent('Loading');
    expect(screen.queryByText('The app')).not.toBeInTheDocument();
    await act(async () => resolve(unanswered));
    expect(await screen.findByText('How do you keep track of your spending today?')).toBeInTheDocument();
  });

  it('does not hold a returning user: the page shows while the answer is looked up', () => {
    vi.mocked(onboardingApi.status).mockReturnValue(new Promise(() => {}));
    renderGate(false);

    expect(screen.getByText('The app')).toBeInTheDocument();
  });

  it('a held user whose lookup fails is let through, not left on the loader', async () => {
    vi.mocked(onboardingApi.status).mockRejectedValue(new Error('offline'));
    renderGate(true);

    expect(await screen.findByText('The app')).toBeInTheDocument();
  });

  it('does not lock anyone out when the status cannot be loaded', async () => {
    vi.mocked(onboardingApi.status).mockRejectedValue(new Error('offline'));
    renderGate();

    await waitFor(() => expect(onboardingApi.status).toHaveBeenCalled());
    expect(screen.getByText('The app')).toBeInTheDocument();
  });
});
