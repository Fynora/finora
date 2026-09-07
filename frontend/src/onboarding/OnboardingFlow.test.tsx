import { render, screen, fireEvent, act } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { OnboardingFlow } from './OnboardingFlow';
import { onboardingApi } from '../api/endpoints';
import { useAuth } from '../context/AuthContext';
import { useOnboardingUI } from './OnboardingUIContext';

vi.mock('../api/endpoints', () => ({
  onboardingApi: {
    complete: vi.fn(),
    setFinancialFocus: vi.fn(),
  },
}));

vi.mock('../context/AuthContext', () => ({
  useAuth: vi.fn(),
}));

vi.mock('./OnboardingUIContext', () => ({
  useOnboardingUI: vi.fn(),
}));

// Bug fix regression coverage: none of this flow's API calls had error handling at all -- a
// rejected complete()/setFinancialFocus() left an unhandled promise rejection and the clicked
// button silently did nothing, with no way for a stranded new user to tell what happened or that
// retrying would help. Every test below exercises that failure path explicitly, not just the
// happy path the pre-existing ProtectedRoute.test.tsx coverage already had.
describe('OnboardingFlow', () => {
  let setStep: ReturnType<typeof vi.fn>;
  let setOnboardingCompleted: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    vi.mocked(onboardingApi.complete).mockReset();
    vi.mocked(onboardingApi.setFinancialFocus).mockReset();
    setStep = vi.fn();
    setOnboardingCompleted = vi.fn();
    vi.mocked(useAuth).mockReturnValue({ setOnboardingCompleted } as unknown as ReturnType<typeof useAuth>);
  });

  function renderAtStep(step: 'welcome' | 'focus' | 'success') {
    vi.mocked(useOnboardingUI).mockReturnValue({ step, setStep });
    // SuccessScreen (the 'success' step) calls useNavigate() -- needs a Router even for the
    // welcome/focus steps that don't render it, since a single helper is simpler than branching.
    render(<MemoryRouter><OnboardingFlow /></MemoryRouter>);
  }

  describe('welcome step', () => {
    it('completes onboarding when Skip succeeds', async () => {
      vi.mocked(onboardingApi.complete).mockResolvedValue(undefined as never);
      renderAtStep('welcome');

      await act(async () => {
        fireEvent.click(screen.getByRole('button', { name: 'Skip for Now' }));
      });

      expect(setOnboardingCompleted).toHaveBeenCalledWith(true);
      expect(screen.queryByText(/went wrong/i)).not.toBeInTheDocument();
    });

    it('shows an error and does not complete onboarding when Skip fails', async () => {
      vi.mocked(onboardingApi.complete).mockRejectedValue(new Error('network error'));
      renderAtStep('welcome');

      await act(async () => {
        fireEvent.click(screen.getByRole('button', { name: 'Skip for Now' }));
      });

      expect(setOnboardingCompleted).not.toHaveBeenCalled();
      expect(screen.getByText(/went wrong/i)).toBeInTheDocument();
      // The button must still be there and clickable -- the whole point is that retrying works,
      // not that the screen is now stuck showing only an error.
      expect(screen.getByRole('button', { name: 'Skip for Now' })).toBeInTheDocument();
    });

    it('clears a previous error once a retry succeeds', async () => {
      vi.mocked(onboardingApi.complete).mockRejectedValueOnce(new Error('network error'));
      vi.mocked(onboardingApi.complete).mockResolvedValueOnce(undefined as never);
      renderAtStep('welcome');

      await act(async () => {
        fireEvent.click(screen.getByRole('button', { name: 'Skip for Now' }));
      });
      expect(screen.getByText(/went wrong/i)).toBeInTheDocument();

      await act(async () => {
        fireEvent.click(screen.getByRole('button', { name: 'Skip for Now' }));
      });
      expect(screen.queryByText(/went wrong/i)).not.toBeInTheDocument();
      expect(setOnboardingCompleted).toHaveBeenCalledWith(true);
    });
  });

  describe('focus step', () => {
    it('advances to tourIntro when setFinancialFocus succeeds', async () => {
      vi.mocked(onboardingApi.setFinancialFocus).mockResolvedValue({ onboardingCompleted: false, focusKeys: [] });
      renderAtStep('focus');

      await act(async () => {
        fireEvent.click(screen.getByRole('button', { name: 'Continue' }));
      });

      expect(setStep).toHaveBeenCalledWith('tourIntro');
    });

    it('shows an error and does not advance when setFinancialFocus fails', async () => {
      vi.mocked(onboardingApi.setFinancialFocus).mockRejectedValue(new Error('network error'));
      renderAtStep('focus');

      await act(async () => {
        fireEvent.click(screen.getByRole('button', { name: 'Continue' }));
      });

      expect(setStep).not.toHaveBeenCalled();
      expect(screen.getByText(/went wrong/i)).toBeInTheDocument();
    });
  });

  describe('success step', () => {
    it('completes onboarding when "Go to Dashboard" succeeds', async () => {
      vi.mocked(onboardingApi.complete).mockResolvedValue(undefined as never);
      renderAtStep('success');

      await act(async () => {
        fireEvent.click(screen.getByRole('button', { name: 'Go to Dashboard' }));
      });

      expect(setOnboardingCompleted).toHaveBeenCalledWith(true);
    });

    it('shows an error on the success screen when completion fails', async () => {
      vi.mocked(onboardingApi.complete).mockRejectedValue(new Error('network error'));
      renderAtStep('success');

      await act(async () => {
        fireEvent.click(screen.getByRole('button', { name: 'Go to Dashboard' }));
      });

      expect(setOnboardingCompleted).not.toHaveBeenCalled();
      expect(screen.getByText(/went wrong/i)).toBeInTheDocument();
    });
  });
});
