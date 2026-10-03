import { useState } from 'react';
import { useLocation } from 'react-router-dom';
import { X } from 'lucide-react';
import {
  DISMISSED_KEY,
  androidOpenAppUrl,
  androidOpenInAppEnabled,
  isAndroidBrowser,
} from '../lib/openInApp';
import { safeStorage } from '../lib/safeStorage';

/**
 * "Open in the Fynora app" bar for a visitor on an Android phone browser -- someone who tapped a
 * shared link and may already have the app. Off unless VITE_OPEN_IN_APP_ANDROID is "true" (see
 * lib/openInApp.ts for why, and for the iPhone side). Shown only on the public pages (landing,
 * sign-in, policies), never under /app, which is where a shared link lands. A strip at the top of
 * the page, in its flow, not a fixed bar: the landing page's own phone call-to-action is fixed to
 * the bottom of the screen (seen in the browser), and a fixed bar there covered it. Closing it is
 * remembered on this device.
 */
export function OpenInAppBanner() {
  const { pathname } = useLocation();
  const [dismissed, setDismissed] = useState(() => safeStorage.getItem(DISMISSED_KEY) === '1');

  if (dismissed || !androidOpenInAppEnabled()) return null;
  if (pathname === '/app' || pathname.startsWith('/app/')) return null;
  if (typeof navigator === 'undefined' || !isAndroidBrowser(navigator.userAgent)) return null;

  const dismiss = () => {
    safeStorage.setItem(DISMISSED_KEY, '1');
    setDismissed(true);
  };

  return (
    <div
      role="region"
      aria-label="Open in the Fynora app"
      className="w-full bg-card border-b border-border px-4 py-2.5 flex items-center gap-3 text-sm text-ink"
    >
      <span className="flex-1">Use Fynora in the Android app.</span>
      <a
        href={androidOpenAppUrl()}
        className="rounded-md bg-primary px-3 py-1.5 font-medium text-on-primary hover:bg-primary-dark"
      >
        Open app
      </a>
      <button
        type="button"
        onClick={dismiss}
        aria-label="Close"
        className="rounded-md p-1 text-muted hover:text-ink"
      >
        <X size={16} />
      </button>
    </div>
  );
}
