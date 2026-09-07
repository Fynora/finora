import { render, screen, fireEvent, waitFor } from '@testing-library/react-native';
import { OnboardingNavigator } from './OnboardingNavigator';
import { onboardingApi } from '../api/endpoints';
import { useAuth } from '../context/AuthContext';
import { useOnboardingStep } from './OnboardingStepContext';

jest.mock('../api/endpoints', () => ({
  onboardingApi: {
    complete: jest.fn(),
    setFinancialFocus: jest.fn(),
  },
}));

jest.mock('../context/AuthContext', () => ({
  useAuth: jest.fn(),
}));

jest.mock('./OnboardingStepContext', () => ({
  useOnboardingStep: jest.fn(),
}));

jest.mock('../theme', () => ({
  useTheme: () => ({
    bg: '#fff', ink: '#000', muted: '#888', danger: '#dc2626', primary: '#000', border: '#ccc', card: '#fff',
  }),
  spacing: { sm: 8, md: 16 },
  radius: { md: 8, lg: 12, xl: 16 },
}));

// Bug fix regression coverage: mobile counterpart of frontend/src/onboarding/OnboardingFlow.test.tsx
// -- see that file's own comment. None of this component's API calls had error handling before;
// a rejected complete()/setFinancialFocus() left an unhandled promise rejection and the pressed
// button silently did nothing.
describe('OnboardingNavigator', () => {
  let setStep: jest.Mock;
  let setOnboardingCompleted: jest.Mock;

  beforeEach(() => {
    (onboardingApi.complete as jest.Mock).mockReset();
    (onboardingApi.setFinancialFocus as jest.Mock).mockReset();
    setStep = jest.fn();
    setOnboardingCompleted = jest.fn();
    (useAuth as jest.Mock).mockReturnValue({ setOnboardingCompleted });
  });

  function renderAtStep(step: 'welcome' | 'focus' | 'success') {
    (useOnboardingStep as jest.Mock).mockReturnValue({ step, setStep });
    render(<OnboardingNavigator />);
  }

  describe('welcome step', () => {
    it('completes onboarding when Skip succeeds', async () => {
      (onboardingApi.complete as jest.Mock).mockResolvedValue(undefined);
      renderAtStep('welcome');

      fireEvent.press(screen.getByText('Skip for Now'));

      await waitFor(() => expect(setOnboardingCompleted).toHaveBeenCalledWith(true));
      expect(screen.queryByText(/went wrong/i)).toBeNull();
    });

    it('shows an error and does not complete onboarding when Skip fails', async () => {
      (onboardingApi.complete as jest.Mock).mockRejectedValue(new Error('network error'));
      renderAtStep('welcome');

      fireEvent.press(screen.getByText('Skip for Now'));

      await waitFor(() => expect(screen.getByText(/went wrong/i)).toBeTruthy());
      expect(setOnboardingCompleted).not.toHaveBeenCalled();
      // Still there and pressable -- the whole point is that retrying works.
      expect(screen.getByText('Skip for Now')).toBeTruthy();
    });
  });

  describe('focus step', () => {
    it('advances to tourIntro when setFinancialFocus succeeds', async () => {
      (onboardingApi.setFinancialFocus as jest.Mock).mockResolvedValue({ onboardingCompleted: false, focusKeys: [] });
      renderAtStep('focus');

      fireEvent.press(screen.getByText('Continue'));

      await waitFor(() => expect(setStep).toHaveBeenCalledWith('tourIntro'));
    });

    it('shows an error and does not advance when setFinancialFocus fails', async () => {
      (onboardingApi.setFinancialFocus as jest.Mock).mockRejectedValue(new Error('network error'));
      renderAtStep('focus');

      fireEvent.press(screen.getByText('Continue'));

      await waitFor(() => expect(screen.getByText(/went wrong/i)).toBeTruthy());
      expect(setStep).not.toHaveBeenCalled();
    });
  });

  describe('success step', () => {
    it('completes onboarding when "Go to Dashboard" succeeds', async () => {
      (onboardingApi.complete as jest.Mock).mockResolvedValue(undefined);
      renderAtStep('success');

      fireEvent.press(screen.getByText('Go to Dashboard'));

      await waitFor(() => expect(setOnboardingCompleted).toHaveBeenCalledWith(true));
    });

    it('shows an error on the success screen when completion fails', async () => {
      (onboardingApi.complete as jest.Mock).mockRejectedValue(new Error('network error'));
      renderAtStep('success');

      fireEvent.press(screen.getByText('Go to Dashboard'));

      await waitFor(() => expect(screen.getByText(/went wrong/i)).toBeTruthy());
      expect(setOnboardingCompleted).not.toHaveBeenCalled();
    });
  });
});
