import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import { useMemoryReinforcement } from './useMemoryReinforcement';

describe('useMemoryReinforcement', () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('starts with no message shown', () => {
    const { result } = renderHook(() => useMemoryReinforcement());

    expect(result.current.message).toBeNull();
  });

  it('shows the message immediately when show() is called', () => {
    const { result } = renderHook(() => useMemoryReinforcement());

    act(() => {
      result.current.show('Fynora will remember this.');
    });

    expect(result.current.message).toBe('Fynora will remember this.');
  });

  it('clears the message on its own after the display duration', () => {
    const { result } = renderHook(() => useMemoryReinforcement());

    act(() => {
      result.current.show('Fynora will remember this.');
    });
    expect(result.current.message).toBe('Fynora will remember this.');

    act(() => {
      vi.advanceTimersByTime(2999);
    });
    expect(result.current.message).toBe('Fynora will remember this.');

    act(() => {
      vi.advanceTimersByTime(1);
    });
    expect(result.current.message).toBeNull();
  });

  it('a second show() replaces the message and restarts the timer', () => {
    const { result } = renderHook(() => useMemoryReinforcement());

    act(() => {
      result.current.show('First.');
    });
    act(() => {
      vi.advanceTimersByTime(2000);
    });
    act(() => {
      result.current.show('Second.');
    });

    // Only 2000ms since the ORIGINAL show, but the second show restarts the clock -- if it
    // didn't, this would already be past the 3000ms mark from the first call and read null.
    act(() => {
      vi.advanceTimersByTime(2000);
    });
    expect(result.current.message).toBe('Second.');

    act(() => {
      vi.advanceTimersByTime(1000);
    });
    expect(result.current.message).toBeNull();
  });
});
