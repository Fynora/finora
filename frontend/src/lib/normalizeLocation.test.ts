import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it, vi } from 'vitest';
import { collapsedPath, normalizeLocation } from './normalizeLocation';

describe('collapsedPath', () => {
  it('collapses every run of slashes to one', () => {
    expect(collapsedPath('//terms')).toBe('/terms');
    expect(collapsedPath('///')).toBe('/');
    expect(collapsedPath('//')).toBe('/');
    expect(collapsedPath('/app//transactions')).toBe('/app/transactions');
    expect(collapsedPath('//app///imports//abc')).toBe('/app/imports/abc');
  });

  it('returns null for a path with nothing to collapse, trailing slash included', () => {
    for (const fine of ['/', '/terms', '/terms/', '/app/imports/abc', '']) {
      expect(collapsedPath(fine), fine).toBeNull();
    }
  });

  it('never returns a path a browser would read as another host', () => {
    // "//evil.example/x" as a URL means host evil.example. As this page's PATH it is just a path,
    // and collapsed it starts with exactly one slash.
    expect(collapsedPath('//evil.example/x')).toBe('/evil.example/x');
    expect(collapsedPath('////evil.example//x')).toBe('/evil.example/x');
  });
});

describe('normalizeLocation', () => {
  const fakeWindow = (pathname: string, search = '', hash = '', state: unknown = null) => {
    const replaceState = vi.fn();
    return { win: { location: { pathname, search, hash }, history: { state, replaceState } } as unknown as Window, replaceState };
  };

  it('rewrites the address in place, keeping the query, the fragment and the history state', () => {
    const { win, replaceState } = fakeWindow('//terms', '?next=https://app.fynora.net//x', '#billing', { k: 1 });
    normalizeLocation(win);
    expect(replaceState).toHaveBeenCalledTimes(1);
    // The query holds a URL with "//" of its own; it is not this function's to touch.
    expect(replaceState).toHaveBeenCalledWith({ k: 1 }, '', '/terms?next=https://app.fynora.net//x#billing');
  });

  it('does nothing to an address that is already clean, so no page pays for it', () => {
    for (const fine of ['/', '/terms', '/app/transactions']) {
      const { win, replaceState } = fakeWindow(fine, '?a=//b');
      normalizeLocation(win);
      expect(replaceState, fine).not.toHaveBeenCalled();
    }
  });

  it('works on the real window: the router then sees the clean path', () => {
    window.history.pushState({}, '', '/x//y?q=1#h');
    expect(window.location.pathname).toBe('/x//y');
    normalizeLocation(window);
    expect(window.location.pathname).toBe('/x/y');
    expect(window.location.search).toBe('?q=1');
    expect(window.location.hash).toBe('#h');
    window.history.replaceState({}, '', '/');
  });
});

describe('main.tsx', () => {
  it('imports the normaliser before anything else, so no module reads the raw path as it loads', () => {
    // pages/landing/hero/firstFrame.ts asks whether the path is "/" while it is being imported.
    // Run after App's import, the rewrite would be too late for it.
    const here = path.dirname(fileURLToPath(import.meta.url));
    const main = fs.readFileSync(path.join(here, '../main.tsx'), 'utf-8');
    const imports = [...main.matchAll(/^import\s[^\n]*$/gm)].map((m) => m[0]);
    expect(imports[0]).toBe("import './lib/normalizeLocation';");
    expect(imports.findIndex((line) => line.includes("'./App'"))).toBeGreaterThan(0);
  });
});
