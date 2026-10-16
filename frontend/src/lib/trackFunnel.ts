import { activationApi, type FunnelEvent } from '../api/endpoints';

export type { FunnelEvent };

/**
 * Records one import-funnel moment (Gate 2a spec §3) for the signed-in user. Fire and forget, the
 * same rule trackNavigation follows: a failed call is never retried, never shown and never delays
 * the screen. The server keeps at most one row per user, event and day, so callers only need to
 * avoid sending the same event twice in one visit.
 */
export function trackFunnel(event: FunnelEvent): void {
  try {
    void activationApi.funnelEvent(event).catch(() => {});
  } catch {
    // A synchronous throw must not break the screen either.
  }
}
