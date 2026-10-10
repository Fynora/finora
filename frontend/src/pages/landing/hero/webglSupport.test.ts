import { afterEach, describe, expect, it, vi } from 'vitest';
import { isWebglAvailable } from './webglSupport';

describe('isWebglAvailable', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('returns false in jsdom, which implements no WebGL context', () => {
    // jsdom's canvas.getContext('webgl') always returns null -- this test documents that
    // environment fact and pins the function's real, unmocked behavior in this suite. Tests that
    // need the "WebGL present" branch mock this module directly (see AmbientCanvas.test.tsx).
    expect(isWebglAvailable()).toBe(false);
  });

  // A probe context that is only dropped stays live until garbage collection and counts toward
  // Chrome's cap of 16, past which Chrome evicts the oldest live context -- possibly the scene's.
  // AmbientCanvas re-probes each time a narrow window widens back to desktop, so an unreleased probe
  // accumulates one live context per resize.
  function fakeContext(loseContext: () => void, hasExtension = true) {
    return {
      getExtension: vi.fn((name: string) =>
        hasExtension && name === 'WEBGL_lose_context' ? { loseContext } : null
      ),
    } as unknown as WebGL2RenderingContext;
  }

  it('releases the context it created, and still reports WebGL as available', () => {
    const loseContext = vi.fn();
    vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue(fakeContext(loseContext));
    expect(isWebglAvailable()).toBe(true);
    expect(loseContext).toHaveBeenCalledTimes(1);
  });

  it('reports WebGL as available when the context cannot be released', () => {
    const noExtension = fakeContext(vi.fn(), false);
    vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue(noExtension);
    expect(isWebglAvailable()).toBe(true);
  });

  it('reports WebGL as available when releasing the context throws', () => {
    const throwing = fakeContext(() => {
      throw new Error('cannot lose');
    });
    vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue(throwing);
    expect(isWebglAvailable()).toBe(true);
  });

  it('does not throw if canvas.getContext throws', () => {
    vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockImplementation(() => {
      throw new Error('no context for you');
    });
    expect(() => isWebglAvailable()).not.toThrow();
    expect(isWebglAvailable()).toBe(false);
  });
});
