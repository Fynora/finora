import { useNavigate } from 'react-router-dom';
import { Button } from '../design-system';
import { CHECKLIST_ITEMS } from './checklistItems';

interface Props {
  // Async in practice (the real prop, OnboardingFlow's finishOnboarding, awaits
  // onboardingApi.complete() before resolving) -- goThenNavigate below awaits it, so the type has
  // to say so, not just `() => void`.
  onDone: () => void | Promise<void>;
  error?: string | null;
}

export function SuccessScreen({ onDone, error }: Props) {
  const navigate = useNavigate();

  // Bug fix: this used to fire onDone() (the real prop is async -- it awaits
  // onboardingApi.complete() before flipping onboardingCompleted) and navigate() in the same tick,
  // not sequenced. navigate() landed on the target route before onboardingCompleted actually
  // flipped true, so ProtectedRoute -- which gates on that flag, not on the URL -- still treated
  // the session as mid-onboarding and rendered OnboardingFlow's Success screen again at the new
  // URL. Awaiting onDone() first means the target page only ever renders once onboarding is
  // actually marked complete.
  //
  // Bug fix: onDone (finishOnboarding) can now reject -- OnboardingFlow's own comment explains
  // why it rethrows after recording the error -- and this used to have no catch, so a failed
  // completion still fell through to navigate() on an unhandled rejection. The catch here is what
  // actually stops that: don't navigate to a page that still requires onboarding to be complete.
  async function goThenNavigate(path: string) {
    try {
      await onDone();
      void navigate(path);
    } catch {
      // Already surfaced via the `error` prop above (OnboardingFlow's own state).
    }
  }

  // Same reasoning as goThenNavigate above, minus the navigate() call: "Go to Dashboard" doesn't
  // go anywhere itself (ProtectedRoute re-renders the real app once onboardingCompleted flips),
  // but onClick={onDone} directly would still leave a failed attempt as an unhandled rejection.
  async function goDone() {
    try {
      await onDone();
    } catch {
      // Already surfaced via the `error` prop above.
    }
  }

  return (
    <div className="flex flex-col items-center justify-center min-h-screen px-6 text-center">
      <h1 className="text-3xl font-bold text-ink mb-3">You're Ready to Go 🚀</h1>
      <p className="text-muted max-w-md mb-6">
        Start by importing your first bank statement or connecting an account. The more data you
        add, the smarter Fynora becomes.
      </p>
      <div className="text-left mb-8">
        <p className="text-sm font-semibold text-ink mb-2">Next steps:</p>
        <ul className="space-y-1">
          {CHECKLIST_ITEMS.map((item) => (
            <li key={item.key} className="text-sm text-muted">☐ {item.label}</li>
          ))}
        </ul>
      </div>
      {error && <p className="text-danger text-sm mb-4">{error}</p>}
      <div className="flex flex-col sm:flex-row gap-3">
        <Button variant="primary" onClick={() => goThenNavigate('/app/import')}>Import Statement</Button>
        <Button variant="secondary" onClick={() => goThenNavigate('/app/accounts')}>Connect Account</Button>
        <Button variant="secondary" onClick={goDone}>Go to Dashboard</Button>
      </div>
    </div>
  );
}
