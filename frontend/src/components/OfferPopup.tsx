import { Component, useState, type ReactNode } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useAuth } from '../context/AuthContext';
import { useLocation, useNavigate } from 'react-router-dom';
import { Sparkles, X } from 'lucide-react';
import { billingApi } from '../api/endpoints';
import { useDialogA11y } from '../design-system';
import { offerHeadline, useBillingOffers } from '../pages/landing/offers';
import { trackNavigation } from '../lib/trackNavigation';

/** Per browser and per offer: closing the Diwali pop-up keeps it closed, but a later offer (a new
 *  code) shows once again. */
export const OFFER_POPUP_DISMISSED_PREFIX = 'fynora:offer-popup-dismissed:';

/** If the announcement itself ever fails to render, show nothing rather than the app's error
 *  panel: a promotion is never worth an error on the page someone is using. */
export class OfferPopupBoundary extends Component<{ children: ReactNode }, { failed: boolean }> {
  state = { failed: false };

  static getDerivedStateFromError() {
    return { failed: true };
  }

  render() {
    return this.state.failed ? null : this.props.children;
  }
}

function wasDismissed(code: string): boolean {
  try {
    return localStorage.getItem(OFFER_POPUP_DISMISSED_PREFIX + code) !== null;
  } catch {
    return false;
  }
}

function rememberDismissed(code: string) {
  try {
    localStorage.setItem(OFFER_POPUP_DISMISSED_PREFIX + code, new Date().toISOString());
  } catch {
    // Storage blocked: it closes for this visit and may show again on the next one.
  }
}

/**
 * Announces a live Plus offer (backend V297) once, when a Free user opens the app -- on the
 * dashboard only. Shown only to someone who can take it: the offer applies to a first paid
 * checkout, so anyone already on a paid plan -- through billing or a complimentary grant -- never
 * sees it. Closing it (the cross, Escape, the backdrop or the button) remembers that for this
 * offer.
 */
export function OfferPopup() {
  const offers = useBillingOffers();
  const location = useLocation();
  const { data: subscription } = useQuery({
    queryKey: ['my-subscription'],
    queryFn: () => billingApi.mySubscription(),
  });
  const [closedCode, setClosedCode] = useState<string | null>(null);
  // Never on top of the first-run onboarding flow, which is its own full-screen sequence.
  const { onboardingCompleted } = useAuth();

  const headline = offerHeadline(offers, 'PLUS', 'Plus');
  const eligible = subscription != null && subscription.planCode === 'FREE' && !subscription.hasBillingSubscription;
  // Only on the dashboard, where opening the app lands: anywhere else the customer is in the middle
  // of something (an import, a form), and a modal over it gets in their way.
  const onDashboard = location.pathname === '/app' || location.pathname === '/app/';
  if (!headline || !eligible || !onboardingCompleted || !onDashboard) return null;
  if (closedCode === headline.code || wasDismissed(headline.code)) return null;

  return (
    <OfferDialog
      label={headline.label}
      text={headline.text}
      lastDay={headline.lastDay}
      onClose={() => {
        rememberDismissed(headline.code);
        setClosedCode(headline.code);
      }}
    />
  );
}

function OfferDialog({ label, text, lastDay, onClose }: {
  label: string; text: string; lastDay: string; onClose: () => void;
}) {
  const navigate = useNavigate();
  const panelRef = useDialogA11y({ onClose });

  return (
    <>
      <div className="fixed inset-0 bg-black/40 z-30" onClick={onClose} />
      <div className="fixed inset-0 z-40 flex items-center justify-center p-4 pointer-events-none">
        <div
          ref={panelRef}
          role="dialog"
          aria-modal="true"
          aria-labelledby="offer-popup-title"
          tabIndex={-1}
          data-testid="offer-popup"
          className="relative bg-card border border-border rounded-xl2 shadow-soft w-full max-w-sm p-6 pointer-events-auto focus:outline-none"
        >
          <button
            type="button"
            onClick={onClose}
            aria-label="Close"
            className="absolute top-3 right-3 w-8 h-8 grid place-items-center rounded-lg text-muted hover:text-ink hover:bg-bg"
          >
            <X size={16} aria-hidden="true" />
          </button>
          <div className="w-10 h-10 rounded-full bg-warning-bg flex items-center justify-center mb-3">
            <Sparkles size={18} className="text-warning" aria-hidden="true" />
          </div>
          <h2 id="offer-popup-title" className="font-semibold text-ink text-base pr-8">{label}</h2>
          <p className="text-sm text-muted mt-1.5">{text}. Ends {lastDay}.</p>
          <button
            type="button"
            onClick={() => {
              onClose();
              trackNavigation('subscription', 'contextual');
              void navigate('/app/billing');
            }}
            className="mt-5 w-full text-sm font-semibold rounded-lg px-4 py-2.5 bg-primary text-on-primary"
          >
            Get Plus
          </button>
        </div>
      </div>
    </>
  );
}
