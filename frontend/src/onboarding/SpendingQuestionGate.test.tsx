import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { SpendingQuestionGate } from './SpendingQuestionGate';
import { SPENDING_TRACKING_OPTIONS } from './SpendingTrackingQuestion';
import { onboardingApi } from '../api/endpoints';

vi.mock('../api/endpoints', () => ({
  onboardingApi: { status: vi.fn(), setSpendingTracking: vi.fn() },
}));
vi.mock('../context/AuthContext', () => ({ useAuth: () => ({ email: 'gate-test@example.com' }) }));

function renderGate() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <SpendingQuestionGate>
        <p>The app</p>
      </SpendingQuestionGate>
    </QueryClientProvider>
  );
}

const unanswered = { onboardingCompleted: true, financialFocus: [], spendingTrackingMethod: null };

beforeEach(() => {
  vi.mocked(onboardingApi.status).mockReset();
  vi.mocked(onboardingApi.setSpendingTracking).mockReset();
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

  it('does not lock anyone out when the status cannot be loaded', async () => {
    vi.mocked(onboardingApi.status).mockRejectedValue(new Error('offline'));
    renderGate();

    await waitFor(() => expect(onboardingApi.status).toHaveBeenCalled());
    expect(screen.getByText('The app')).toBeInTheDocument();
  });
});
