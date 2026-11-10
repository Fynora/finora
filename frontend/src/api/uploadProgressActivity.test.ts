import { beforeEach, describe, expect, it, vi } from 'vitest';
import { toUploadProgressConfig } from './endpoints';
import { LAST_ACTIVITY_KEY } from '../lib/idleSignOut';

/** A long upload is the user waiting on Fynora, not the user gone: it must not end in a sign-out. */
describe('upload progress counts as activity for the idle sign-out', () => {
  beforeEach(() => {
    localStorage.clear();
    vi.setSystemTime(new Date('2026-11-10T10:00:00Z'));
  });

  it('marks activity on every progress event, with or without a progress callback', () => {
    const config = toUploadProgressConfig();
    config.onUploadProgress({ loaded: 10, total: 100 });
    expect(localStorage.getItem(LAST_ACTIVITY_KEY)).toBe(String(Date.now()));
  });

  it('still reports the percentage to the caller', () => {
    const onProgress = vi.fn();
    toUploadProgressConfig(onProgress).onUploadProgress({ loaded: 25, total: 100 });
    expect(onProgress).toHaveBeenCalledWith(25);
  });
});
