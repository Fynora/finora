import { useCallback, useEffect, useRef, useState, type ReactNode } from 'react';
import { useAdminAuth } from '../context/AdminAuthContext';
import { ADMIN_SESSION_ENDED_REASON_KEY } from '../api/client';
import { Button } from '../design-system';
import { safeStorage } from '../lib/safeStorage';
import { IDLE_LIMIT_MS, LAST_ACTIVITY_KEY, WARNING_MS, markActivity, msSinceActivity, recordActivityNow } from '../lib/idleSignOut';

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
 * Signs the admin out after IDLE_LIMIT_MS without activity in any portal tab, with a WARNING_MS
 * warning first -- the same rule as the customer web app (frontend/src/components/IdleSignOut.tsx).
 * The server's own limits (24 hours idle, 30 days) stay as a backstop for a closed tab.
 *
 * Wraps the routes so a session restored on load after a long absence is signed out before any
 * admin page renders. logout() ends the session on the server too; ProtectedRoute then sends the
 * admin to /login, which reads ADMIN_SESSION_ENDED_REASON_KEY to say why.
 *
 * One difference from the web app: on a page load the portal sets the token BEFORE its permission
 * check (/users/me/access) has answered, and keeps `loading` true until it has. Signing out in that
 * gap would revoke the session under the check, so nothing happens until loading is done; the
 * Loading screen hides every page until then anyway.
 */
export function IdleSignOut({ children }: { children: ReactNode }) {
  const { token, loading, logout } = useAdminAuth();
  const [warning, setWarning] = useState(false);

  // Whether THIS session was already past the limit when it appeared. Adjusted during render so no
  // admin page paints for a session that is about to be signed out.
  const [startCheck, setStartCheck] = useState<{ token: string | null; expired: boolean }>({
    token: null,
    expired: false,
  });
  if (startCheck.token !== token) setStartCheck({ token, expired: token !== null && isExpired() });
  const blocked = token !== null && (startCheck.token !== token || startCheck.expired);

  const signOut = useCallback(() => {
    setWarning(false);
    safeStorage.setItem(ADMIN_SESSION_ENDED_REASON_KEY, SIGNED_OUT_REASON);
    logout();
  }, [logout]);

  // Activity counts whether or not anyone is signed in: typing a password on the sign-in page is
  // what makes the session that follows start with a fresh clock.
  useEffect(() => {
    const onActivity = () => markActivity();
    // Capture, not bubble: a scroll inside a scrollable panel (a long table) does not bubble to
    // window, so only a capturing listener sees it.
    ACTIVITY_EVENTS.forEach((type) => window.addEventListener(type, onActivity, { capture: true, passive: true }));
    return () => ACTIVITY_EVENTS.forEach((type) => window.removeEventListener(type, onActivity, { capture: true }));
  }, []);

  useEffect(() => {
    if (token === null || loading) return;
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
  }, [token, loading, blocked, signOut]);

  if (blocked) return null;

  return (
    <>
      {children}
      {token !== null && warning ? (
        <IdleWarning
          onStay={() => {
            // Explicit, not throttled: the click itself is the activity that must count.
            recordActivityNow();
            setWarning(false);
          }}
        />
      ) : null}
    </>
  );
}

/** A one-button dialog: focus starts on "Stay signed in", Escape means stay too, and Tab cannot
 *  leave it for the page behind. */
function IdleWarning({ onStay }: { onStay: () => void }) {
  const stayRef = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    const previouslyFocused = document.activeElement as HTMLElement | null;
    stayRef.current?.focus();
    return () => previouslyFocused?.focus?.();
  }, []);

  useEffect(() => {
    function onKeyDown(e: KeyboardEvent) {
      if (e.key === 'Escape') {
        onStay();
        return;
      }
      if (e.key === 'Tab') {
        // The only control in the dialog: keep focus on it.
        e.preventDefault();
        stayRef.current?.focus();
      }
    }
    document.addEventListener('keydown', onKeyDown);
    return () => document.removeEventListener('keydown', onKeyDown);
  }, [onStay]);

  return (
    // Same look as ConfirmDialog. The backdrop needs no handler: any click or key already counts as
    // activity, which closes the warning at the next check.
    <div className="fixed inset-0 z-50 flex items-center justify-center px-4">
      <div className="absolute inset-0 bg-black/30" />
      <div
        role="dialog"
        aria-modal="true"
        aria-labelledby="idle-signout-title"
        aria-describedby="idle-signout-body"
        className="relative bg-card border border-border rounded-xl2 shadow-soft w-full max-w-sm p-5"
      >
        <h2 id="idle-signout-title" className="font-semibold text-ink text-sm">
          You'll be signed out in 60 seconds
        </h2>
        <p id="idle-signout-body" className="text-sm text-muted mt-2">
          For security, the admin portal signs you out after 15 minutes without activity.
        </p>
        <div className="mt-5 flex justify-end">
          <Button ref={stayRef} onClick={onStay}>Stay signed in</Button>
        </div>
      </div>
    </div>
  );
}
