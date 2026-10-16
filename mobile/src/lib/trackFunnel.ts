import { activationApi, type FunnelEvent } from '../api/endpoints';

export type { FunnelEvent };

/**
 * Records one import-funnel moment (Gate 2a spec §3) for the signed-in user. Port of
 * frontend/src/lib/trackFunnel.ts, with the same rule: a failed call is never retried, never shown
 * and never delays the screen. The server keeps at most one row per user, event and day, so callers
 * only need to avoid sending the same event twice in one visit.
 */
export function trackFunnel(event: FunnelEvent): void {
  try {
    void activationApi.funnelEvent(event).catch(() => {});
  } catch {
    // A synchronous throw must not break the screen either.
  }
}
