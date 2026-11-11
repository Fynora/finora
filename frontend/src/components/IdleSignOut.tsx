import { useCallback, useEffect, useState, type ReactNode } from 'react';
import { useAuth } from '../context/AuthContext';
import { SESSION_ENDED_REASON_KEY } from '../api/client';
import { Button, useDialogA11y } from '../design-system';
import { safeStorage } from '../lib/safeStorage';
import { IDLE_LIMIT_MS, LAST_ACTIVITY_KEY, WARNING_MS, markActivity, msSinceActivity } from '../lib/idleSignOut';

const ACTIVITY_EVENTS = ['mousemove', 'mousedown', 'keydown', 'scroll', 'touchstart', 'wheel'] as const;
const CHECK_EVERY_MS = 5_000;
const SIGNED_OUT_REASON = 'You were signed out after 15 minutes of inactivity.';

/** Expired means a recorded last activity older than the limit. None recorded is not expired: the
 *  first visit after this ships must not sign everybody out. */
function isExpired(): boolean {
  const since = msSinceActivity();
  return since !== null && since > IDLE_LIMIT_MS;
}

/**
 * Signs the web user out after IDLE_LIMIT_MS without activity in any tab, with a WARNING_MS
 * warning first (design 2026-11-10). The server's own limits (24 hours idle, 30 days) stay as a
 * backstop for a tab that was closed rather than left open.
 *
 * Wraps the app so that a session restored on load after a long absence -- a laptop opened the
 * next morning, a tab reopened hours later -- is signed out before any protected content renders.
 * logout() ends the session on the server too, and ProtectedRoute then shows the sign-in page,
 * which reads SESSION_ENDED_REASON_KEY to say why.
 */
export function IdleSignOut({ children }: { children: ReactNode }) {
  const { token, logout } = useAuth();
  const [warning, setWarning] = useState(false);

  // Whether THIS session was already past the limit when it appeared. Adjusted during render so
  // the protected tree never paints for a session that is about to be signed out.
  const [startCheck, setStartCheck] = useState<{ token: string | null; expired: boolean }>({
    token: null,
    expired: false,
  });
  if (startCheck.token !== token) setStartCheck({ token, expired: token !== null && isExpired() });
  const blocked = token !== null && (startCheck.token !== token || startCheck.expired);

  const signOut = useCallback(() => {
    setWarning(false);
    safeStorage.setItem(SESSION_ENDED_REASON_KEY, SIGNED_OUT_REASON);
    logout();
  }, [logout]);

  // Activity counts whether or not anyone is signed in: typing a password on the sign-in page is
  // what makes the session that follows start with a fresh clock.
  useEffect(() => {
    const onActivity = () => markActivity();
    // Capture, not bubble: a scroll inside a scrollable panel (a long transaction list) does not
    // bubble to window, so only a capturing listener sees it.
    ACTIVITY_EVENTS.forEach((type) => window.addEventListener(type, onActivity, { capture: true, passive: true }));
    return () => ACTIVITY_EVENTS.forEach((type) => window.removeEventListener(type, onActivity, { capture: true }));
  }, []);

  useEffect(() => {
    if (token === null) return;
    if (blocked) {
      signOut();
      return;
    }
    // A first visit after this ships has no recorded activity: start the clock now.
    if (msSinceActivity() === null) markActivity();

    const check = () => {
      const since = msSinceActivity() ?? 0;
      if (since > IDLE_LIMIT_MS) {
        signOut();
        return;
      }
      setWarning(since >= IDLE_LIMIT_MS - WARNING_MS);
    };
    const onVisible = () => {
      if (document.visibilityState !== 'hidden') check();
    };
    const onStorage = (e: StorageEvent) => {
      if (e.key === LAST_ACTIVITY_KEY) check();
    };
    const timer = window.setInterval(check, CHECK_EVERY_MS);
    document.addEventListener('visibilitychange', onVisible);
    window.addEventListener('storage', onStorage);
    return () => {
      window.clearInterval(timer);
      document.removeEventListener('visibilitychange', onVisible);
      window.removeEventListener('storage', onStorage);
    };
  }, [token, blocked, signOut]);

  if (blocked) return null;

  return (
    <>
      {children}
      {token !== null && warning ? (
        <IdleWarning
          onStay={() => {
            // Explicit, not throttled: the click itself is the activity that must count.
            safeStorage.setItem(LAST_ACTIVITY_KEY, String(Date.now()));
            setWarning(false);
          }}
        />
      ) : null}
    </>
  );
}

function IdleWarning({ onStay }: { onStay: () => void }) {
  const panelRef = useDialogA11y({ onClose: onStay });
  return (
    <div className="fixed inset-0 bg-black/40 flex items-center justify-center z-50">
      <div
        ref={panelRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby="idle-signout-title"
        aria-describedby="idle-signout-body"
        tabIndex={-1}
        className="bg-card rounded-xl2 shadow-card p-6 w-[400px] max-w-[90vw]"
      >
        <h2 id="idle-signout-title" className="text-ink font-semibold text-base">
          You'll be signed out in 60 seconds
        </h2>
        <p id="idle-signout-body" className="text-muted text-sm mt-2">
          For your security, Fynora signs you out after 15 minutes without activity.
        </p>
        <div className="mt-5 flex justify-end">
          <Button onClick={onStay}>Stay signed in</Button>
        </div>
      </div>
    </div>
  );
}
