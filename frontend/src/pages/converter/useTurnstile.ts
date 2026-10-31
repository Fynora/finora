import { useCallback, useEffect, useRef, useState } from 'react';

/**
 * Cloudflare Turnstile for the free statement converter: loads Cloudflare's script once (explicit
 * rendering, so nothing renders until this hook asks), renders the widget into `container`, and
 * holds the current token. A token is single-use on the server, so `reset()` asks for a new one
 * after every conversion attempt.
 *
 * The script comes from https://challenges.cloudflare.com, allowed in public/_headers' CSP
 * (script-src and frame-src). With no site key (VITE_TURNSTILE_SITE_KEY unset) nothing loads and
 * the page shows the converter as unavailable.
 */

interface TurnstileApi {
  render(container: HTMLElement, options: Record<string, unknown>): string;
  reset(widgetId: string): void;
  remove(widgetId: string): void;
}

declare global {
  interface Window {
    turnstile?: TurnstileApi;
  }
}

export const TURNSTILE_SCRIPT_URL = 'https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit';

let scriptPromise: Promise<TurnstileApi> | null = null;

function loadScript(): Promise<TurnstileApi> {
  if (window.turnstile) return Promise.resolve(window.turnstile);
  if (scriptPromise) return scriptPromise;
  scriptPromise = new Promise((resolve, reject) => {
    const script = document.createElement('script');
    script.src = TURNSTILE_SCRIPT_URL;
    script.async = true;
    script.onload = () => (window.turnstile ? resolve(window.turnstile) : reject(new Error('turnstile missing')));
    script.onerror = () => {
      scriptPromise = null;
      reject(new Error('turnstile script failed to load'));
    };
    document.head.appendChild(script);
  });
  return scriptPromise;
}

export type TurnstileState = 'idle' | 'loading' | 'ready' | 'failed';

export function useTurnstile(siteKey: string | undefined, enabled: boolean) {
  const container = useRef<HTMLDivElement | null>(null);
  const widgetId = useRef<string | null>(null);
  const [token, setToken] = useState<string | null>(null);
  const [state, setState] = useState<TurnstileState>('idle');

  useEffect(() => {
    if (!enabled || !siteKey || !container.current) return;
    let cancelled = false;
    setState('loading');
    loadScript()
      .then((api) => {
        if (cancelled || !container.current) return;
        widgetId.current = api.render(container.current, {
          sitekey: siteKey,
          action: 'statement-convert',
          callback: (value: string) => {
            setToken(value);
            setState('ready');
          },
          'expired-callback': () => setToken(null),
          'error-callback': () => {
            setToken(null);
            setState('failed');
          },
        });
      })
      .catch(() => {
        if (!cancelled) setState('failed');
      });
    return () => {
      cancelled = true;
      if (widgetId.current && window.turnstile) window.turnstile.remove(widgetId.current);
      widgetId.current = null;
    };
  }, [siteKey, enabled]);

  const reset = useCallback(() => {
    setToken(null);
    if (widgetId.current && window.turnstile) window.turnstile.reset(widgetId.current);
  }, []);

  return { container, token, state, reset };
}
