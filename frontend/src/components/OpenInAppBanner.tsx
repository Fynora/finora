import { useEffect, useState } from 'react';
import { useLocation } from 'react-router-dom';
import { X } from 'lucide-react';
import {
  DISMISSED_KEY,
  androidOpenAppUrl,
  androidOpenInAppEnabled,
  isAndroidBrowser,
  isAppPromptPage,
} from '../lib/openInApp';
import { playStoreLink } from '../lib/playStoreLink';
import { safeStorage } from '../lib/safeStorage';

/** Height the fixed bar takes at the bottom of the screen; the page gets as much padding below. */
const FIXED_BAR_PADDING = '4.5rem';

/**
 * "Get the Fynora app" bar for a visitor on an Android phone browser. On unless
 * VITE_OPEN_IN_APP_ANDROID is "false" (see lib/openInApp.ts, which also covers the iPhone side).
 * Never on a computer, an iPhone, Firefox or an app's WebView (isAndroidBrowser). Only on the
 * homepage and the pages built for search (isAppPromptPage): not under /app, not on the sign-in or
 * one-time-token pages, and not on the policy pages the mobile app itself opens in the browser.
 * Closing it is remembered on this device (safeStorage, which never throws).
 *
 * Its button is a Chrome intent link: the installed app opens; without it, Chrome goes to the Play
 * listing carrying this page's install referrer (utm_term "banner").
 *
 * Where it sits:
 *  - On the homepage, a strip at the top of the page, in its flow: the landing page's own phone
 *    call-to-action is fixed to the bottom of the screen, and a fixed bar there covered it.
 *  - Everywhere else, fixed to the bottom of the screen. Those pages arrive as prerendered HTML,
 *    and this bar can only appear once the script has read the user agent. A strip in the flow
 *    above the page would push the text the visitor is already reading down the screen (layout
 *    shift); a fixed bar moves nothing. The page gets bottom padding while it shows, so the
 *    footer's last line can still be scrolled clear of it.
 */
export function OpenInAppBanner() {
  const { pathname } = useLocation();
  const [dismissed, setDismissed] = useState(() => safeStorage.getItem(DISMISSED_KEY) === '1');

  const path = pathname.replace(/\/+$/, '') || '/';
  const shown =
    !dismissed &&
    androidOpenInAppEnabled() &&
    isAppPromptPage(path) &&
    typeof navigator !== 'undefined' &&
    isAndroidBrowser(navigator.userAgent);
  const fixed = shown && path !== '/';

  useEffect(() => {
    if (!fixed) return;
    const body = document.body;
    const previous = body.style.paddingBottom;
    body.style.paddingBottom = FIXED_BAR_PADDING;
    return () => {
      body.style.paddingBottom = previous;
    };
  }, [fixed]);

  if (!shown) return null;

  const dismiss = () => {
    safeStorage.setItem(DISMISSED_KEY, '1');
    setDismissed(true);
  };

  return (
    <div
      role="region"
      aria-label="Get the Fynora app"
      className={
        fixed
          ? 'fixed inset-x-0 bottom-0 z-40 bg-card border-t border-border shadow-soft px-4 py-3 flex items-center gap-3 text-sm text-ink'
          : 'w-full bg-card border-b border-border px-4 py-2.5 flex items-center gap-3 text-sm text-ink'
      }
    >
      <span className="flex-1">Fynora is on Google Play. Use it in the Android app.</span>
      <a
        href={androidOpenAppUrl(playStoreLink(path, 'banner'))}
        className="shrink-0 rounded-md bg-primary px-3 py-1.5 font-medium text-on-primary hover:bg-primary-dark"
      >
        Get the app
      </a>
      <button
        type="button"
        onClick={dismiss}
        aria-label="Close"
        className="shrink-0 rounded-md p-1 text-muted hover:text-ink"
      >
        <X size={16} />
      </button>
    </div>
  );
}
