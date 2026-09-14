import { useEffect, useRef, useState } from 'react';
import { isGoogleLoginConfigured, loadGoogleIdentityServices, type GoogleCredentialResponse } from '../lib/googleIdentity';

interface GoogleSignInButtonProps {
  // Google's own button copy differs by context ("Sign up with Google" vs "Sign in with
  // Google") -- see https://developers.google.com/identity/gsi/web/reference/js-reference#text.
  text: 'signup_with' | 'signin_with';
  // Receives the raw Google ID token credential. Left to the caller (Register.tsx/Login.tsx)
  // rather than handled here, so this component doesn't need to know about AuthContext,
  // navigation, or which of the two flows it's embedded in -- it only renders Google's button
  // and hands back what Google gave it.
  onCredential: (idToken: string) => void | Promise<void>;
  onError: (message: string) => void;
  // Called with Google's own rendered button width once it's known, and again whenever it
  // changes -- see the comment above the renderedButtonResizeObserver below for why a caller
  // needs this at all instead of just reading the (documented, capped-at-400) `width` param back.
  onRenderedWidth?: (px: number) => void;
}

// D-23: renders Google's own Identity Services button. Deliberately NOT rendered at all when
// VITE_GOOGLE_LOGIN_CLIENT_ID is unset (isGoogleLoginConfigured()) -- same "unconfigured is a
// supported state, degrade silently" posture as BankLogo/MerchantLogo's Logo.dev fallback and
// Firebase's lazy init elsewhere in this codebase, rather than shipping a button that can't work.
export function GoogleSignInButton({ text, onCredential, onError, onRenderedWidth }: GoogleSignInButtonProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const [ready, setReady] = useState(false);
  // Held in a ref, not a dependency of the initialize() effect below, so a parent re-render
  // (e.g. the page's own `loading` state flipping while a credential is being processed) doesn't
  // re-run initialize()/renderButton() and flicker Google's own button.
  const onCredentialRef = useRef(onCredential);
  onCredentialRef.current = onCredential;
  const onErrorRef = useRef(onError);
  onErrorRef.current = onError;
  const onRenderedWidthRef = useRef(onRenderedWidth);
  onRenderedWidthRef.current = onRenderedWidth;

  useEffect(() => {
    if (!isGoogleLoginConfigured() || !containerRef.current) return;
    let cancelled = false;
    let resizeObserver: ResizeObserver | null = null;
    let renderedButtonResizeObserver: ResizeObserver | null = null;
    // Bug fix: reporting on every button re-render created a real feedback loop, live on
    // production -- onRenderedWidth changes the PARENT's formWidth, which resizes THIS
    // component's own container (it's `w-full` of the form), which re-triggers the outer
    // `resizeObserver` below, which re-requests a DIFFERENT button width from Google, which gets
    // measured and reported again, and so on. Observed live: formWidth collapsing to 147px while
    // Google's own button (which won't shrink below its min-content) stayed at 169px, wider than
    // its own now-too-narrow container. Reporting only once breaks the cycle: it still corrects
    // the parent's stale seed value against a real, settled measurement (a ResizeObserver
    // callback only ever fires after a genuine layout pass, so this first report isn't the same
    // "measured before layout settled" race the outer observer's own comment describes), but a
    // resize this correction itself causes no longer asks Google to redraw at a new width.
    let hasReportedWidth = false;

    loadGoogleIdentityServices()
      .then((accountsId) => {
        if (cancelled || !containerRef.current) return;
        accountsId.initialize({
          client_id: import.meta.env.VITE_GOOGLE_LOGIN_CLIENT_ID!,
          callback: (response: GoogleCredentialResponse) => {
            void onCredentialRef.current(response.credential);
          },
        });

        // GIS requires a pixel value here, not a percentage -- '100%' produced a silent
        // "[GSI_LOGGER]: Provided button width is invalid" console warning in production and fell
        // back to some GIS-internal default. A ONE-TIME measurement right when this promise
        // resolves is a race: production showed a button locked at 107px next to a full-width
        // Apple button, because the container's layout (grid columns, web fonts) hadn't finished
        // settling yet at that instant. A ResizeObserver re-renders whenever the container's real,
        // settled width changes, instead of trusting whatever width happened to be laid out first.
        let lastWidth = 0;
        const render = (entries: ResizeObserverEntry[]) => {
          if (!containerRef.current) return;
          const contentWidth = entries[0]?.contentRect.width ?? 0;
          // Capped at Google's documented max: https://developers.google.com/identity/gsi/web/reference/js-reference#width
          const measuredWidth = Math.min(Math.round(contentWidth), 400);
          if (measuredWidth === 0 || measuredWidth === lastWidth) return;
          lastWidth = measuredWidth;
          containerRef.current.replaceChildren();
          accountsId.renderButton(containerRef.current, {
            theme: 'outline',
            // 'medium', not the default 'large' -- Google's own tier ordering (small < medium <
            // large, per https://developers.google.com/identity/gsi/web/reference/js-reference#size)
            // makes this a real reduction regardless of exact pixel values, which Google doesn't
            // publish and which this component can't observe ahead of render (GIS never reports
            // its own rendered height back to the caller). The 44px this actually renders at in
            // production (measured live on app.fynora.net with the real client_id/origin -- a
            // synthetic test page with a placeholder client_id skips Google's real sizing path
            // and is not representative) is what min-h-[44px] below and
            // AppleSignInButton.tsx's py-2.5 are matched against.
            size: 'medium',
            width: String(measuredWidth),
            text,
            // Google defaults to a left-pinned logo with the text centered in the remaining
            // space; AppleSignInButton centers its icon+text as one unit. 'center' is the closest
            // GIS gets to matching that without abandoning Google's own rendered button.
            logo_alignment: 'center',
          });
          setReady(true);

          // Google's `width` param above is capped at 400 (its own documented max), but the
          // element it actually draws doesn't reliably come back at exactly that number, and
          // GIS has switched which element it draws before: this used to always be an <iframe>
          // (measured live on app.fynora.net: requesting 400 rendered a 420px-wide iframe), but
          // GIS now renders the button as a plain `<div role="button">` in the same container
          // instead (measured live again, 2026-09: `width:400px` inline, no iframe present at
          // all) -- so `querySelector('iframe')` alone silently found nothing, this whole
          // correction never ran, and the parent never learned the real width. Matching either
          // shape GIS might use is what keeps this working regardless of which one Google's
          // script decides to draw on a given load, rather than hardcoding today's answer.
          // Whichever it is, reporting that ELEMENT's own real rendered width, not the number we
          // asked Google for, is what lets a parent (SocialSignInButtons) size Apple to match
          // reality instead of a number Google doesn't actually honor.
          renderedButtonResizeObserver?.disconnect();
          if (!hasReportedWidth) {
            const renderedButton = containerRef.current.querySelector('iframe, [role="button"]');
            if (renderedButton) {
              renderedButtonResizeObserver = new ResizeObserver((buttonEntries) => {
                const width = buttonEntries[0]?.contentRect.width;
                if (!width) return;
                hasReportedWidth = true;
                onRenderedWidthRef.current?.(width);
                renderedButtonResizeObserver?.disconnect();
              });
              renderedButtonResizeObserver.observe(renderedButton);
            }
          }
        };

        resizeObserver = new ResizeObserver(render);
        resizeObserver.observe(containerRef.current);
      })
      .catch(() => {
        if (!cancelled) onErrorRef.current('Sign in with Google is unavailable right now. Please try again later.');
      });

    return () => {
      cancelled = true;
      resizeObserver?.disconnect();
      renderedButtonResizeObserver?.disconnect();
    };
    // text intentionally omitted: Register.tsx and Login.tsx each mount their own instance with a
    // fixed text prop that never changes across that instance's lifetime.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  if (!isGoogleLoginConfigured()) return null;

  return (
    <div>
      {/* Google measures and draws its own button into this div once renderButton() runs --
          fixed height reserves the space up front so the rest of the form doesn't jump once it
          appears. */}
      <div ref={containerRef} className="w-full min-h-[44px]" aria-busy={!ready} />
    </div>
  );
}
