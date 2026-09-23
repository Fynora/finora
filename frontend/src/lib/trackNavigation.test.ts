import { describe, it, expect, vi, beforeEach } from 'vitest';
import { trackNavigation, trackNavSearch, __flushNavQueueForTest } from './trackNavigation';
import { api } from '../api/client';

vi.mock('../api/client', () => ({ api: { post: vi.fn().mockResolvedValue({}) } }));

// `api` is the mock factory's object literal above, not a real class instance, so there is no
// `this` to lose by pulling the spy out of it. Same pattern as src/api/stagePdf.test.ts.
// eslint-disable-next-line @typescript-eslint/unbound-method
const post = vi.mocked(api.post);

describe('trackNavigation', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    post.mockResolvedValue({} as never);
  });

  it('derives the group from the taxonomy so callers never pass one', async () => {
    trackNavigation('budgets', 'group');
    await __flushNavQueueForTest();

    expect(post).toHaveBeenCalledWith('/nav-events', {
      events: [{ destination: 'budgets', group: 'planning', entry: 'group' }],
    });
  });

  it('drops a destination that is not in the taxonomy, without calling the API', async () => {
    trackNavigation('not-a-destination', 'group');
    await __flushNavQueueForTest();

    expect(post).not.toHaveBeenCalled();
  });

  it('never rejects when the request fails, so navigation is never blocked', async () => {
    post.mockRejectedValueOnce(new Error('offline'));
    trackNavigation('goals', 'tab');

    await expect(__flushNavQueueForTest()).resolves.toBeUndefined();
  });

  it('batches several events into one request', async () => {
    trackNavigation('goals', 'group');
    trackNavigation('budgets', 'group');
    await __flushNavQueueForTest();

    expect(post).toHaveBeenCalledTimes(1);
    expect(post.mock.calls[0][1]).toMatchObject({
      events: [
        { destination: 'goals', group: 'planning', entry: 'group' },
        { destination: 'budgets', group: 'planning', entry: 'group' },
      ],
    });
  });

  it('reports a search as a count, never as a term', async () => {
    trackNavSearch();

    expect(post).toHaveBeenCalledWith('/nav-events', { events: [], searches: 1 });
    // The ledger search term is named in observability.md §3 as the sharpest case of free text
    // that must never leave the platform. Assert the payload has no field that could carry one.
    const body = JSON.stringify(post.mock.calls[0][1]);
    expect(body).not.toMatch(/query|term|text|keyword/i);
  });
});
