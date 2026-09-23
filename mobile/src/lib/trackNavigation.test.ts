import { trackNavigation, trackNavSearch, __flushNavQueueForTest } from './trackNavigation';
import { telemetryApi } from '../api/client';

jest.mock('../api/client', () => ({ telemetryApi: { post: jest.fn() } }));

const post = telemetryApi.post as jest.Mock;

describe('trackNavigation', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    post.mockResolvedValue({});
  });

  it('derives the group from the taxonomy so callers never pass one', async () => {
    trackNavigation('budgets', 'group');
    await __flushNavQueueForTest();

    expect(post).toHaveBeenCalledWith('/nav-events', {
      events: [{ destination: 'budgets', group: 'planning', entry: 'group' }],
    });
  });

  it('drops a destination that is not in the taxonomy, without calling the API', async () => {
    trackNavigation('nope', 'tab');
    await __flushNavQueueForTest();

    expect(post).not.toHaveBeenCalled();
  });

  it('never rejects when the request fails, so navigation is never blocked', async () => {
    post.mockRejectedValueOnce(new Error('offline'));
    trackNavigation('goals', 'tab');

    await expect(__flushNavQueueForTest()).resolves.toBeUndefined();
  });

  it('reports a search as a count, never as a term', () => {
    trackNavSearch();

    expect(post).toHaveBeenCalledWith('/nav-events', { events: [], searches: 1 });
  });

  it('names destinations identically to the web client', async () => {
    // The whole point of the shared taxonomy: the same destination must report the same id and
    // group from both clients, or the platform split in the counters compares two different
    // things. This payload is asserted byte-for-byte identical in the web suite's own test.
    trackNavigation('statement-history', 'group');
    await __flushNavQueueForTest();

    expect(post).toHaveBeenCalledWith('/nav-events', {
      events: [{ destination: 'statement-history', group: 'statements', entry: 'group' }],
    });
  });
});

afterEach(async () => {
  // Drains the pending flush timer. Without this a test that queues without flushing leaves a
  // 2s timer alive and Jest reports "did not exit one second after the test run" -- observed,
  // not hypothetical.
  await __flushNavQueueForTest();
});
