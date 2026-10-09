import { AppBanner, BANNER_DURATION_MS, getCurrentAppBanner, subscribeAppBanner, __resetAppBannerForTests } from './appBanner';

beforeEach(() => {
  jest.useFakeTimers();
  __resetAppBannerForTests();
});

afterEach(() => {
  __resetAppBannerForTests();
  jest.useRealTimers();
});

describe('AppBanner', () => {
  it('has nothing to show until something raises a banner', () => {
    expect(getCurrentAppBanner()).toBeUndefined();
  });

  it('shows the title and message, and tells subscribers', () => {
    const listener = jest.fn();
    subscribeAppBanner(listener);

    AppBanner.show('Welcome to Fynora', 'Welcome to Fynora.');

    expect(getCurrentAppBanner()).toMatchObject({ title: 'Welcome to Fynora', message: 'Welcome to Fynora.' });
    expect(listener).toHaveBeenCalledTimes(1);
  });

  it('clears itself after its duration, and not a moment sooner', () => {
    AppBanner.show('Hello', 'World');

    jest.advanceTimersByTime(BANNER_DURATION_MS - 1);
    expect(getCurrentAppBanner()).toBeDefined();

    jest.advanceTimersByTime(1);
    expect(getCurrentAppBanner()).toBeUndefined();
  });

  it('replaces a banner already showing, and the newer one gets its own full duration', () => {
    AppBanner.show('First', 'one');
    jest.advanceTimersByTime(BANNER_DURATION_MS - 1000);

    AppBanner.show('Second', 'two');
    expect(getCurrentAppBanner()?.title).toBe('Second');

    // The first banner's timer must not cut the second short.
    jest.advanceTimersByTime(1000);
    expect(getCurrentAppBanner()?.title).toBe('Second');

    jest.advanceTimersByTime(BANNER_DURATION_MS - 1000);
    expect(getCurrentAppBanner()).toBeUndefined();
  });

  it('can be dismissed early, and dismissing nothing is harmless', () => {
    const listener = jest.fn();
    AppBanner.dismiss();
    subscribeAppBanner(listener);
    AppBanner.dismiss();
    expect(listener).not.toHaveBeenCalled();

    AppBanner.show('Hello', 'World');
    listener.mockClear();
    AppBanner.dismiss();

    expect(getCurrentAppBanner()).toBeUndefined();
    expect(listener).toHaveBeenCalledTimes(1);
  });

  it('stops notifying a subscriber that unsubscribed', () => {
    const listener = jest.fn();
    const unsubscribe = subscribeAppBanner(listener);
    unsubscribe();

    AppBanner.show('Hello', 'World');

    expect(listener).not.toHaveBeenCalled();
  });
});
