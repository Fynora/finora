import { useState } from 'react';
import { onboardingApi } from '../api/endpoints';
import { useAuth } from '../context/AuthContext';
import { Button } from '../design-system';
import { useOnboardingUI } from './OnboardingUIContext';
import { WelcomeScreen } from './WelcomeScreen';
import { FinancialFocusScreen } from './FinancialFocusScreen';
import { SuccessScreen } from './SuccessScreen';

const ERROR_MESSAGE = "Something went wrong. Check your connection and try again.";

export function OnboardingFlow() {
  const { step, setStep } = useOnboardingUI();
  const { setOnboardingCompleted } = useAuth();
  // Bug fix: none of this flow's API calls (complete/setFinancialFocus) had any error handling --
  // a failed request just left an unhandled rejection, the button's click silently doing nothing.
  // A new user stranded on Welcome/Focus/Success by a transient network blip right after
  // registering had no way to tell what happened or that clicking again would help. Every other
  // page in this codebase that calls an API catches and shows a message (see e.g. Budgets.tsx/
  // Goals.tsx); this flow was the one place that didn't.
  const [error, setError] = useState<string | null>(null);

  // Rethrows on failure (unlike submitFocusAndContinue below): SuccessScreen's own goThenNavigate
  // awaits this specifically to decide whether onboarding actually completed before it navigates
  // anywhere -- swallowing the error here would defeat that check (see that component's own
  // comment on why premature navigation is itself a bug). Every direct caller of finishOnboarding
  // in this file still needs its own catch, since the error is already recorded via setError
  // below regardless of whether the caller does anything further with it.
  async function finishOnboarding() {
    setError(null);
    try {
      await onboardingApi.complete();
      setOnboardingCompleted(true);
    } catch (e) {
      setError(ERROR_MESSAGE);
      throw e;
    }
  }

  async function skipEverything() {
    try {
      await finishOnboarding();
    } catch {
      // Already recorded via setError inside finishOnboarding; nothing further to do here.
    }
  }

  async function submitFocusAndContinue(selected: string[]) {
    setError(null);
    try {
      await onboardingApi.setFinancialFocus(selected);
      setStep('tourIntro');
    } catch {
      setError(ERROR_MESSAGE);
    }
  }

  if (step === 'welcome') {
    return <WelcomeScreen onStart={() => setStep('focus')} onSkip={skipEverything} error={error} />;
  }
  if (step === 'focus') {
    return <FinancialFocusScreen onContinue={submitFocusAndContinue} error={error} />;
  }
  if (step === 'tourIntro') {
    return (
      <div className="flex flex-col items-center justify-center min-h-screen px-6 text-center">
        <h1 className="text-2xl font-bold text-ink mb-2">Let's take a quick tour</h1>
        <p className="text-muted mb-8">
          This will only take about 30 seconds and will help you get the most out of Fynora.
        </p>
        <div className="flex gap-3">
          <Button variant="primary" onClick={() => setStep('tour')}>Start Tour</Button>
          <Button variant="secondary" onClick={() => setStep('success')}>Skip</Button>
        </div>
      </div>
    );
  }
  if (step === 'success') {
    return <SuccessScreen onDone={finishOnboarding} error={error} />;
  }
  // 'tour' is rendered by ProtectedRoute directly, never by OnboardingFlow -- see that
  // component's own comment.
  return <div data-testid="onboarding-flow">Onboarding flow placeholder</div>;
}
