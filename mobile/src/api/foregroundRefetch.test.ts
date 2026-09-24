import { AppState } from 'react-native';
import { QueryClient, QueryObserver, focusManager } from '@tanstack/react-query';
import { startForegroundRefetch } from './queryClient';

/**
 * A phone has no "window focus", so React Query never learned when the app came back to the front
 * and every mounted screen kept showing whatever it fetched before the app was backgrounded -- a
 * statement imported on the web stayed invisible until the user signed out and in again.
 *
 * startForegroundRefetch() tells focusManager that "focused" means AppState === 'active'.
 */

// Jest's AppState cannot emit on its own; capture the listener and fire it by hand.
let emit: ((state: string) => void) | undefined;
const removeListener = jest.fn();

beforeEach(() => {
  emit = undefined;
  removeListener.mockClear();
  jest.spyOn(AppState, 'addEventListener').mockImplementation(((_: string, listener: (s: string) => void) => {
    emit = listener;
    return { remove: removeListener };
  }) as never);
});

afterEach(() => {
  jest.restoreAllMocks();
  focusManager.setEventListener(() => () => {});
  focusManager.setFocused(undefined);
});

const tick = () => new Promise((resolve) => setTimeout(resolve, 0));

/** A mounted screen: one active observer on a query that is always stale. */
async function mountScreen(queryFn: () => Promise<string>) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  // QueryClientProvider does this in the app; it is what connects focusManager to the cache.
  client.mount();
  const observer = new QueryObserver(client, { queryKey: ['dashboard-summary'], queryFn, staleTime: 0 });
  const unsubscribe = observer.subscribe(() => {});
  await tick();
  return {
    dispose: () => {
      unsubscribe();
      client.unmount();
      client.clear();
    },
  };
}

describe('startForegroundRefetch', () => {
  it('refetches a mounted screen when the app returns to the foreground', async () => {
    const stop = startForegroundRefetch();
    const queryFn = jest.fn(async () => 'data');
    const screen = await mountScreen(queryFn);
    const callsAfterMount = queryFn.mock.calls.length;

    emit!('background');
    emit!('active');
    await tick();

    expect(queryFn.mock.calls.length).toBe(callsAfterMount + 1);
    screen.dispose();
    stop();
  });

  it('does not refetch on the way to the background', async () => {
    const stop = startForegroundRefetch();
    const queryFn = jest.fn(async () => 'data');
    const screen = await mountScreen(queryFn);
    const callsAfterMount = queryFn.mock.calls.length;

    emit!('background');
    await tick();

    expect(queryFn.mock.calls.length).toBe(callsAfterMount);
    screen.dispose();
    stop();
  });

  it('stops listening to AppState when stopped', () => {
    const stop = startForegroundRefetch();
    // focusManager attaches its listener lazily, on the first subscriber.
    focusManager.subscribe(() => {})();
    stop();
    expect(removeListener).toHaveBeenCalled();
  });
});
