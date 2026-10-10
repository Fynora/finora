import { afterEach, describe, expect, it, vi } from 'vitest';

// firstFrame.ts reads the DOM once, when the module is evaluated, so each case sets the page up
// first and then imports a fresh copy of the module.
async function loadWith(path: string, markup: string) {
  window.history.replaceState(null, '', path);
  document.body.innerHTML = markup;
  vi.resetModules();
  return import('./firstFrame');
}

const FRAME = '<div id="root"><section data-hero-first-frame=""></section></div>';

describe('heroFirstFrameWasShown', () => {
  afterEach(() => {
    document.body.innerHTML = '';
    window.history.replaceState(null, '', '/');
  });

  it('is true on "/" when the prerendered first frame is in the page, until the hero marks it replaced', async () => {
    const mod = await loadWith('/', FRAME);
    expect(mod.heroFirstFrameWasShown()).toBe(true);
    mod.markHeroFirstFrameReplaced();
    expect(mod.heroFirstFrameWasShown()).toBe(false);
  });

  it('is false when the page carries no first frame (a prerendered policy page, or a test render)', async () => {
    const mod = await loadWith('/', '<div id="root"></div>');
    expect(mod.heroFirstFrameWasShown()).toBe(false);
  });

  it('is false off the homepage, where index.html is only the SPA fallback and the frame was never seen', async () => {
    const mod = await loadWith('/app/transactions', FRAME);
    expect(mod.heroFirstFrameWasShown()).toBe(false);
  });
});
