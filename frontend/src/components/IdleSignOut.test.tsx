import { act, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { IdleSignOut } from './IdleSignOut';
import { IDLE_LIMIT_MS, LAST_ACTIVITY_KEY, WARNING_MS, __resetIdleClockForTests, markActivity } from '../lib/idleSignOut';
import { SESSION_ENDED_REASON_KEY } from '../api/client';

const auth = { token: 'access-token' as string | null, logout: vi.fn() };
vi.mock('../context/AuthContext', () => ({ useAuth: () => auth }));

const MINUTE = 60_000;
const START = new Date('2026-11-10T10:00:00Z').getTime();

function renderIdle() {
  return render(
    <IdleSignOut>
      <p>protected content</p>
    </IdleSignOut>
  );
}

/** Moves the clock and lets the component's periodic check run. */
function advance(ms: number) {
  act(() => {
    vi.advanceTimersByTime(ms);
  });
}

beforeEach(() => {
  vi.useFakeTimers();
  vi.setSystemTime(START);
  localStorage.clear();
  __resetIdleClockForTests();
  auth.token = 'access-token';
  auth.logout = vi.fn(() => {
    auth.token = null;
  });
});

afterEach(() => {
  vi.useRealTimers();
});

describe('IdleSignOut', () => {
  it('shows the warning at 14 minutes without activity', () => {
    renderIdle();
    advance(13 * MINUTE);
    expect(screen.queryByRole('dialog')).toBeNull();

    advance(1 * MINUTE + 5_000);

    expect(screen.getByRole('dialog')).toBeTruthy();
    expect(screen.getByText("You'll be signed out in 60 seconds")).toBeTruthy();
    expect(auth.logout).not.toHaveBeenCalled();
  });

  it('signs out at 15 minutes, with the inactivity reason for the sign-in page', () => {
    renderIdle();
    advance(15 * MINUTE + 5_000);

    expect(auth.logout).toHaveBeenCalledTimes(1);
    expect(localStorage.getItem(SESSION_ENDED_REASON_KEY)).toBe(
      'You were signed out after 15 minutes of inactivity.'
    );
    expect(IDLE_LIMIT_MS).toBe(15 * MINUTE);
    expect(WARNING_MS).toBe(60_000);
  });

  it('Stay signed in resets the timer', () => {
    renderIdle();
    advance(14 * MINUTE + 5_000);

    fireEvent.click(screen.getByRole('button', { name: 'Stay signed in' }));
    expect(screen.queryByRole('dialog')).toBeNull();
    advance(10 * MINUTE);

    expect(auth.logout).not.toHaveBeenCalled();
  });

  it('mouse, keyboard, scroll and touch all count as activity', () => {
    renderIdle();
    for (const type of ['mousemove', 'mousedown', 'keydown', 'scroll', 'touchstart', 'wheel']) {
      advance(10 * MINUTE);
      act(() => {
        window.dispatchEvent(new Event(type));
      });
    }
    advance(10 * MINUTE);

    expect(auth.logout).not.toHaveBeenCalled();
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  // A scroll event does not bubble: scrolling a list inside a scrollable panel never reaches window
  // unless the listener captures it.
  it('scrolling inside a panel counts as activity', () => {
    renderIdle();
    const panel = screen.getByText('protected content');
    for (let i = 0; i < 3; i++) {
      advance(10 * MINUTE);
      act(() => {
        panel.dispatchEvent(new Event('scroll', { bubbles: false }));
      });
    }
    advance(10 * MINUTE);

    expect(auth.logout).not.toHaveBeenCalled();
  });

  it('activity in another tab resets the timer', () => {
    renderIdle();
    advance(14 * MINUTE + 5_000);
    expect(screen.getByRole('dialog')).toBeTruthy();

    // Another tab writes the shared timestamp; this tab hears it as a storage event.
    act(() => {
      localStorage.setItem(LAST_ACTIVITY_KEY, String(Date.now()));
      // A 'storage' event carrying the key another tab wrote, built without the init-dict constructor.
      window.dispatchEvent(Object.assign(new Event('storage'), { key: LAST_ACTIVITY_KEY }));
    });

    expect(screen.queryByRole('dialog')).toBeNull();
    advance(10 * MINUTE);
    expect(auth.logout).not.toHaveBeenCalled();
  });

  it('signs out on return to the tab past the limit (a laptop waking from sleep)', () => {
    renderIdle();
    // Sleep: the clock jumps, no timers fire in between.
    vi.setSystemTime(START + 2 * 60 * MINUTE);
    act(() => {
      document.dispatchEvent(new Event('visibilitychange'));
    });

    expect(auth.logout).toHaveBeenCalledTimes(1);
  });

  it('signs out on load past the limit, without showing protected content', () => {
    localStorage.setItem(LAST_ACTIVITY_KEY, String(START - 3 * 60 * MINUTE));
    renderIdle();

    expect(screen.queryByText('protected content')).toBeNull();
    expect(auth.logout).toHaveBeenCalledTimes(1);
  });

  it('does not sign anyone out on load when no activity was ever recorded (first visit after this ships)', () => {
    renderIdle();
    expect(screen.getByText('protected content')).toBeTruthy();
    expect(auth.logout).not.toHaveBeenCalled();
  });

  it('a recent sign-in is not signed out because of a stale timestamp from an older session', () => {
    localStorage.setItem(LAST_ACTIVITY_KEY, String(START - 3 * 60 * MINUTE));
    auth.token = null;
    const { rerender } = renderIdle();
    // Typing the password on the sign-in page is activity.
    act(() => {
      window.dispatchEvent(new Event('keydown'));
    });
    auth.token = 'new-session';
    rerender(
      <IdleSignOut>
        <p>protected content</p>
      </IdleSignOut>
    );

    expect(screen.getByText('protected content')).toBeTruthy();
    expect(auth.logout).not.toHaveBeenCalled();
  });

  it('does nothing while signed out', () => {
    auth.token = null;
    renderIdle();
    advance(60 * MINUTE);

    expect(auth.logout).not.toHaveBeenCalled();
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(screen.getByText('protected content')).toBeTruthy();
  });

  // Some browsers and privacy extensions block localStorage. The tabs then cannot share a clock, but
  // this tab must still sign out: blocked storage must not switch the rule off.
  it('still signs out when the browser blocks storage', () => {
    const blocked = () => {
      throw new DOMException('blocked', 'SecurityError');
    };
    const get = vi.spyOn(Storage.prototype, 'getItem').mockImplementation(blocked);
    const set = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(blocked);
    try {
      renderIdle();
      advance(10 * MINUTE);
      act(() => {
        window.dispatchEvent(new Event('keydown'));
      });
      advance(14 * MINUTE);
      expect(auth.logout).not.toHaveBeenCalled();

      advance(1 * MINUTE + 5_000);
      expect(auth.logout).toHaveBeenCalledTimes(1);
    } finally {
      get.mockRestore();
      set.mockRestore();
    }
  });

  it('markActivity writes at most once every 5 seconds', () => {
    markActivity();
    const first = localStorage.getItem(LAST_ACTIVITY_KEY);
    vi.setSystemTime(START + 2_000);
    markActivity();
    expect(localStorage.getItem(LAST_ACTIVITY_KEY)).toBe(first);
    vi.setSystemTime(START + 6_000);
    markActivity();
    expect(localStorage.getItem(LAST_ACTIVITY_KEY)).toBe(String(START + 6_000));
  });
});
