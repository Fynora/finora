import { QueryClient, QueryObserver } from '@tanstack/react-query';
import { changesApi, type ChangeStamp } from '../api/endpoints';
import {
  GatedQueryClient,
  SECTIONS,
  __resetChangeSyncForTests,
  applyPollAnswer,
  fetchStamp,
  refreshChanged,
  resetChangeSync,
  setChangeWatchActive,
  whenChangeGateIdle,
  withBypass,
  type Section,
} from './changeSync';

jest.mock('../api/endpoints', () => ({ changesApi: { stamp: jest.fn() } }));

const mockedStamp = changesApi.stamp as jest.MockedFunction<typeof changesApi.stamp>;

/**
 * The stamp is one value per kind of data. What this module owes the app:
 *  - a change made elsewhere refreshes exactly what it names (a rename refreshes the profile, not the ledger);
 *  - the app's OWN edit is never mistaken for a change made elsewhere;
 *  - and that is achieved without ever hiding a change made elsewhere: a section's baseline moves
 *    only on the strength of a reading taken BEFORE the refetch of the data that section describes.
 */

function stamp(value: string, overrides: Partial<ChangeStamp> = {}): ChangeStamp {
  return {
    transactions: value, accounts: value, statementImports: value, budgets: value,
    goals: value, categories: value, profile: value, ...overrides,
  };
}

/** Answers each stamp request from the queue (the last answer repeats), recording call order. */
function answers(...list: (ChangeStamp | Error)[]) {
  let i = 0;
  mockedStamp.mockImplementation(async () => {
    const next = list[Math.min(i++, list.length - 1)];
    if (next instanceof Error) throw next;
    return next;
  });
}

beforeEach(() => {
  mockedStamp.mockReset();
  __resetChangeSyncForTests();
});

const flush = () => new Promise((resolve) => setTimeout(resolve, 0));

async function poll(value: ChangeStamp | string): Promise<Section[]> {
  answers(typeof value === 'string' ? stamp(value) : value);
  return applyPollAnswer(await fetchStamp());
}

describe('applyPollAnswer', () => {
  it('treats the first answer as a baseline: nothing changed', async () => {
    expect(await poll('a')).toEqual([]);
  });

  it('reports nothing while the answer stays the same', async () => {
    await poll('a');
    expect(await poll('a')).toEqual([]);
  });

  it('names exactly the sections that moved', async () => {
    await poll('a');
    expect(await poll(stamp('a', { profile: 'b' }))).toEqual(['profile']);
    expect(await poll(stamp('a', { profile: 'b', categories: 'c', goals: 'c' }))).toEqual(['goals', 'categories']);
  });

  it('reports a change once, not on every later poll', async () => {
    await poll('a');
    expect(await poll('b')).toHaveLength(SECTIONS.length);
    expect(await poll('b')).toEqual([]);
  });

  it('ignores an answer older than the one already applied', async () => {
    answers(stamp('a'), stamp('b'), stamp('a'));
    const first = await fetchStamp(); // seq 1
    const second = await fetchStamp(); // seq 2
    const third = await fetchStamp(); // seq 3, a stale-looking value
    applyPollAnswer(first);
    applyPollAnswer(third); // newest applied: baseline 'a' at seq 3, change vs. 'a' none
    // A slow answer sent before it arrives last and must not move the baseline backwards.
    expect(applyPollAnswer(second)).toEqual([]);
    expect(applyPollAnswer(third)).toEqual([]);
  });

  it('starts again after resetChangeSync (a different account)', async () => {
    await poll('a');
    resetChangeSync();
    expect(await poll('b')).toEqual([]);
  });
});

describe('refreshChanged', () => {
  function refreshFor(changed: Section[]) {
    const client = new QueryClient();
    const spy = jest.spyOn(client, 'invalidateQueries');
    refreshChanged(client, changed);
    return spy.mock.calls.map((c) => (c[0] as { queryKey: string[] }).queryKey[0]);
  }

  it('refreshes only the profile for a profile change', () => {
    expect(refreshFor(['profile'])).toEqual(['user-settings']);
  });

  it('refreshes the financial queries for a change to any financial section', () => {
    for (const section of ['transactions', 'accounts', 'statementImports', 'budgets', 'goals'] as const) {
      const keys = refreshFor([section]);
      expect(keys).toContain('dashboard-summary');
      expect(keys).toContain('transactions');
      expect(keys).not.toContain('user-settings');
      expect(keys).not.toContain('categories');
    }
  });

  it('refreshes categories and the financial queries (whose rows carry category names) for a category change', () => {
    const keys = refreshFor(['categories']);
    expect(keys).toContain('categories');
    expect(keys).toContain('transactions');
    expect(keys).not.toContain('user-settings');
  });

  it('refreshes each thing once when several sections moved', () => {
    const keys = refreshFor(['profile', 'categories', 'transactions']);
    expect(keys.filter((k) => k === 'user-settings')).toHaveLength(1);
    expect(keys.filter((k) => k === 'categories')).toHaveLength(1);
    expect(keys.filter((k) => k === 'transactions')).toHaveLength(1);
  });
});

describe('GatedQueryClient: the app\'s own edits', () => {
  let client: GatedQueryClient;
  let order: string[];

  beforeEach(() => {
    client = new GatedQueryClient();
    order = [];
    setChangeWatchActive(true);
  });
  afterEach(() => client.clear());

  /** A screen for a key, recording when its query function runs. */
  function mount(key: string) {
    client.setQueryDefaults([key], { queryFn: async () => { order.push(`refetch:${key}`); return key; } });
    client.setQueryData([key], 'old');
    return client.getQueryCache().find({ queryKey: [key] })!;
  }

  async function withBaseline(value = 'a') {
    answers(stamp(value));
    applyPollAnswer(await fetchStamp());
    mockedStamp.mockClear();
  }

  it('does not delay or gate anything while nothing is watching the stamp', async () => {
    setChangeWatchActive(false);
    mount('accounts');
    await client.invalidateQueries({ queryKey: ['accounts'] });
    expect(mockedStamp).not.toHaveBeenCalled();
  });

  it('does not gate before a baseline exists', async () => {
    mount('accounts');
    answers(stamp('a'));
    await client.invalidateQueries({ queryKey: ['accounts'] });
    expect(mockedStamp).not.toHaveBeenCalled();
  });

  it('does not gate keys that are not a section\'s own data', async () => {
    await withBaseline();
    await client.invalidateQueries({ queryKey: ['dashboard-summary'] });
    await client.invalidateQueries({ queryKey: ['gmail-status'] });
    expect(mockedStamp).not.toHaveBeenCalled();
  });

  it('does not gate a predicate invalidation (the cold-start restore)', async () => {
    await withBaseline();
    await client.invalidateQueries({ predicate: () => true });
    expect(mockedStamp).not.toHaveBeenCalled();
  });

  it('reads the stamp BEFORE the refetch it belongs to, so an edit made elsewhere in between is never hidden', async () => {
    await withBaseline();
    mount('accounts');
    // An active observer, so the invalidation really refetches.
    const unsubscribe = new QueryObserver(client, { queryKey: ['accounts'], staleTime: Infinity }).subscribe(() => {});
    await flush();
    order.length = 0;
    mockedStamp.mockImplementation(async () => {
      order.push('stamp');
      return stamp('b');
    });

    await client.invalidateQueries({ queryKey: ['accounts'] });

    expect(order).toEqual(['stamp', 'refetch:accounts']);
    unsubscribe();
  });

  it('makes the reading the new baseline, so the next poll of the same answer is not a change', async () => {
    await withBaseline('a');
    mount('accounts');
    answers(stamp('a', { accounts: 'mine' }));

    await client.invalidateQueries({ queryKey: ['accounts'] });

    // The poll now returns what the reading returned: the phone's own edit, not someone else's.
    answers(stamp('a', { accounts: 'mine' }));
    expect(applyPollAnswer(await fetchStamp())).toEqual([]);
  });

  it('still reports a change made elsewhere afterwards', async () => {
    await withBaseline('a');
    mount('accounts');
    answers(stamp('a', { accounts: 'mine' }));
    await client.invalidateQueries({ queryKey: ['accounts'] });

    answers(stamp('a', { accounts: 'mine', profile: 'renamed' }));
    expect(applyPollAnswer(await fetchStamp())).toEqual(['profile']);
  });

  it('rebaselines only the sections whose own data is refetched, never one it did not touch', async () => {
    await withBaseline('a');
    mount('accounts');
    // The reading shows accounts moved (our edit) AND profile moved (someone else, a moment ago).
    answers(stamp('a', { accounts: 'mine', profile: 'renamed' }));

    await client.invalidateQueries({ queryKey: ['accounts'] });

    answers(stamp('a', { accounts: 'mine', profile: 'renamed' }));
    expect(applyPollAnswer(await fetchStamp())).toEqual(['profile']);
  });

  it('needs every one of a section\'s queries refreshed together before it moves that baseline', async () => {
    await withBaseline('a');
    mount('transactions');
    answers(stamp('a', { transactions: 'mine' }));

    await client.invalidateQueries({ queryKey: ['transactions'] }); // recent-transactions NOT refreshed

    answers(stamp('a', { transactions: 'mine' }));
    expect(applyPollAnswer(await fetchStamp())).toEqual(['transactions']);
  });

  it('moves a section that has two queries when both are refreshed in the same breath', async () => {
    await withBaseline('a');
    mount('transactions');
    mount('recent-transactions');
    answers(stamp('a', { transactions: 'mine' }));

    void client.invalidateQueries({ queryKey: ['transactions'] });
    await client.invalidateQueries({ queryKey: ['recent-transactions'] });

    answers(stamp('a', { transactions: 'mine' }));
    expect(applyPollAnswer(await fetchStamp())).toEqual([]);
  });

  it('asks the backend once for a whole batch of invalidations', async () => {
    await withBaseline('a');
    for (const key of ['transactions', 'recent-transactions', 'accounts', 'budgets', 'goals', 'statement-imports']) mount(key);
    answers(stamp('b'));

    await Promise.all(
      ['transactions', 'recent-transactions', 'accounts', 'budgets', 'goals', 'statement-imports'].map((key) =>
        client.invalidateQueries({ queryKey: [key] })
      )
    );

    expect(mockedStamp).toHaveBeenCalledTimes(1);
  });

  it('proceeds without rebaselining when the reading fails, so the next poll refreshes once more but nothing is hidden', async () => {
    await withBaseline('a');
    mount('accounts');
    answers(new Error('offline'));

    await client.invalidateQueries({ queryKey: ['accounts'] });

    answers(stamp('a', { accounts: 'mine' }));
    expect(applyPollAnswer(await fetchStamp())).toEqual(['accounts']);
  });

  it('gives up waiting for a slow reading, proceeds, and discards the late answer', async () => {
    jest.useFakeTimers();
    try {
      await withBaseline('a');
      mount('accounts');
      let release!: (s: ChangeStamp) => void;
      mockedStamp.mockImplementation(() => new Promise<ChangeStamp>((resolve) => { release = resolve; }));

      const done = client.invalidateQueries({ queryKey: ['accounts'] });
      await jest.advanceTimersByTimeAsync(1_000);
      await done; // proceeded without the reading
      release(stamp('a', { accounts: 'late' }));
      await jest.advanceTimersByTimeAsync(0);

      answers(stamp('a', { accounts: 'late' }));
      expect(applyPollAnswer(await fetchStamp())).toEqual(['accounts']);
    } finally {
      jest.useRealTimers();
    }
  });

  it('lets a caller wait for the held-back refreshes to be released, not for them to finish', async () => {
    await withBaseline('a');
    client.setQueryData(['accounts'], 'old');
    let finishRefetch!: () => void;
    const slowFetch = () => {
      order.push('refetch:accounts');
      return new Promise<string>((resolve) => { finishRefetch = () => resolve('new'); });
    };
    const unsubscribe = new QueryObserver(client, {
      queryKey: ['accounts'], queryFn: slowFetch, staleTime: Infinity,
    }).subscribe(() => {});
    await flush();
    answers(stamp('b'));

    void client.invalidateQueries({ queryKey: ['accounts'] });
    expect(order).toEqual([]); // held back while the reading is taken
    await whenChangeGateIdle();

    expect(order).toEqual(['refetch:accounts']); // started, though it has not finished
    finishRefetch();
    unsubscribe();
  });

  it('has nothing to wait for when no refresh is held back', async () => {
    await expect(whenChangeGateIdle()).resolves.toBeUndefined();
  });

  it('is not gated when the app itself refreshes because of a change made elsewhere (withBypass)', async () => {
    await withBaseline('a');
    mount('accounts');
    await withBypass(() => client.invalidateQueries({ queryKey: ['accounts'] }));
    expect(mockedStamp).not.toHaveBeenCalled();
    await flush();
  });
});
