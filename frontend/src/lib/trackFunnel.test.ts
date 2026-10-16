import { describe, it, expect, vi, beforeEach } from 'vitest';

vi.mock('../api/endpoints', () => ({
  activationApi: { funnelEvent: vi.fn() },
}));

import { activationApi } from '../api/endpoints';
import { trackFunnel } from './trackFunnel';

describe('trackFunnel', () => {
  beforeEach(() => {
    vi.mocked(activationApi.funnelEvent).mockReset().mockResolvedValue(undefined);
  });

  it('postsTheEventName', () => {
    trackFunnel('FILE_CHOSEN');

    expect(activationApi.funnelEvent).toHaveBeenCalledTimes(1);
    expect(activationApi.funnelEvent).toHaveBeenCalledWith('FILE_CHOSEN');
  });

  // Vitest fails the run on an unhandled rejection, so a rejection that escaped would fail this test.
  it('swallowsFailure', async () => {
    vi.mocked(activationApi.funnelEvent).mockRejectedValue(new Error('network down'));

    expect(() => trackFunnel('REVIEW_OPENED')).not.toThrow();
    await new Promise((resolve) => setTimeout(resolve, 0));
  });

  // A synchronous throw (a module mocked without activationApi, say) must not break the screen either.
  it('swallowsASynchronousThrow', () => {
    vi.mocked(activationApi.funnelEvent).mockImplementation(() => {
      throw new TypeError('not a function');
    });

    expect(() => trackFunnel('IMPORT_SCREEN_OPENED')).not.toThrow();
  });
});
