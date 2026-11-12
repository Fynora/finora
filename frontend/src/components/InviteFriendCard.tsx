import { useEffect, useRef, useState } from 'react';
import { invitePromptApi, referralsApi, type InvitePromptEvent } from '../api/endpoints';
import { Button } from '../design-system';
import { inviteMessage, inviteSignupLink } from '../lib/inviteLink';

/** Mirrors ReferralService.MILESTONE_REFERRALS on the backend (the Refer & Earn page's own copy). */
const REFERRAL_MILESTONE = 7;

type State =
  | { kind: 'hidden' }
  | { kind: 'shown'; code: string }
  | { kind: 'copied'; code: string }
  | { kind: 'copyFailed'; code: string }
  | { kind: 'shared' };

function report(event: InvitePromptEvent) {
  try {
    void invitePromptApi.event(event).catch(() => {});
  } catch {
    // Measurement must never break the screen.
  }
}

/** A share sheet the person closed without picking anything rejects with AbortError. */
function isAbort(e: unknown): boolean {
  return e instanceof DOMException ? e.name === 'AbortError' : (e as { name?: string })?.name === 'AbortError';
}

/**
 * "Know someone who'd want this?" on the import summary, after a person's first successful import.
 * Whether to show it is the server's call (InvitePromptService): the first import that saved
 * transactions, once more at least 180 days after "Not now", never after Share. A held or refused
 * statement never reaches the summary, so it never asks. Renders nothing while it checks, when it
 * should not show, or when the check or the referral code cannot be read -- the import itself
 * succeeded either way.
 *
 * Share opens the browser's share sheet (Web Share API) with the person's referral message. Where
 * there is none (most desktop browsers), it copies the sign-up link instead. Inline in the page, not
 * a dialog, so it never covers the summary's warnings.
 */
export function InviteFriendCard({ statementImportIds }: { statementImportIds: string[] }) {
  const [state, setState] = useState<State>({ kind: 'hidden' });
  const idsKey = statementImportIds.join(',');
  // React runs effects twice in development; the server ignores a same-day repeat, but one report
  // per summary is the intent.
  const reportedFor = useRef<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    setState({ kind: 'hidden' });
    if (!idsKey) return;
    // Wrapped so even a synchronous throw stays inside this card: it sits on the import summary,
    // which must never fail over it.
    Promise.resolve()
      .then(() => invitePromptApi.check(idsKey.split(',')))
      .then((check) => (check.show ? referralsApi.myCode() : null))
      .then((mine) => {
        if (cancelled || !mine?.code) return;
        setState({ kind: 'shown', code: mine.code });
        if (reportedFor.current !== idsKey) {
          reportedFor.current = idsKey;
          report('SHOWN');
        }
      })
      .catch(() => {});
    return () => { cancelled = true; };
  }, [idsKey]);

  if (state.kind === 'hidden') return null;

  if (state.kind === 'shared') {
    return (
      <div className="mb-5 rounded-xl2 border border-border bg-bg p-4" data-testid="invite-friend-card" role="status">
        <p className="text-sm font-semibold text-ink">Thanks for sharing Fynora.</p>
      </div>
    );
  }

  const { code } = state;
  const link = inviteSignupLink(window.location.origin, code);

  async function share() {
    report('SHARE_TAPPED');
    if (typeof navigator.share === 'function') {
      try {
        await navigator.share({ title: 'Fynora', text: inviteMessage(window.location.origin, code) });
        setState({ kind: 'shared' });
        return;
      } catch (e) {
        // Closed without sharing: leave the card as it was, so they can try again or say Not now.
        if (isAbort(e)) return;
        // Refused (NotAllowedError, an unsupported target): copy the link instead, below.
      }
    }
    try {
      await navigator.clipboard.writeText(link);
      setState({ kind: 'copied', code });
    } catch {
      // No clipboard either (an insecure context, a blocked permission): show the link to copy by hand.
      setState({ kind: 'copyFailed', code });
    }
  }

  function notNow() {
    report('DISMISSED');
    setState({ kind: 'hidden' });
  }

  return (
    <section className="mb-5 rounded-xl2 border border-border bg-bg p-4 space-y-3" data-testid="invite-friend-card"
      aria-labelledby="invite-friend-title">
      <div>
        <p id="invite-friend-title" className="text-sm font-semibold text-ink">Fynora sorted your statement.</p>
        <p className="text-sm text-ink mt-0.5">Know someone who&apos;d want this?</p>
        <p className="text-xs text-muted mt-1">
          When {REFERRAL_MILESTONE} friends you invite start paying for Plus, you earn a month of Plus free.
        </p>
      </div>
      {state.kind === 'copied' && (
        <p className="text-xs text-success" role="status">Link copied. Paste it to a friend.</p>
      )}
      {state.kind === 'copyFailed' && (
        <div className="space-y-1">
          <label htmlFor="invite-friend-link" className="text-xs text-muted">Copy your link:</label>
          <input
            id="invite-friend-link"
            readOnly
            value={link}
            onFocus={(e) => e.target.select()}
            className="w-full border border-border rounded-lg px-3 py-2 text-sm bg-card text-ink"
          />
        </div>
      )}
      <div className="flex flex-wrap items-center gap-4">
        <Button size="sm" variant="secondary" onClick={share}>Share</Button>
        <button type="button" onClick={notNow} className="text-sm font-medium text-muted hover:text-ink">
          Not now
        </button>
      </div>
    </section>
  );
}
