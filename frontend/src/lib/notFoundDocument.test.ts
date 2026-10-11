import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { afterEach, describe, expect, it } from 'vitest';
import { dropNotFoundDocumentText } from './notFoundDocument';
import { HOME_TITLE, NOT_FOUND_DOCUMENT_TITLE, SITE_DESCRIPTION } from './siteUrl';

const TAGS: [string, string][] = [['name', 'description'], ['property', 'og:title'], ['property', 'og:description']];
const content = (attr: string, key: string) => document.head.querySelector(`meta[${attr}="${key}"]`)?.getAttribute('content') ?? null;
const arriveWith = (title: string, description: string) => {
  for (const [attr, key] of TAGS) {
    const tag = document.createElement('meta');
    tag.setAttribute(attr, key);
    tag.setAttribute('content', key === 'og:title' ? title : description);
    document.head.appendChild(tag);
  }
  document.title = title;
};

describe('dropNotFoundDocumentText', () => {
  afterEach(() => {
    for (const [attr, key] of TAGS) document.head.querySelectorAll(`meta[${attr}="${key}"]`).forEach((m) => m.remove());
    document.title = '';
  });

  it("replaces 404.html's description and social text with the site's, and leaves the title", () => {
    arriveWith(NOT_FOUND_DOCUMENT_TITLE, 'There is no page at this address.');
    dropNotFoundDocumentText();
    expect(content('name', 'description')).toBe(SITE_DESCRIPTION);
    expect(content('property', 'og:title')).toBe(HOME_TITLE);
    expect(content('property', 'og:description')).toBe(SITE_DESCRIPTION);
    // Every route sets its own title; resetting it here would flash the homepage's in the tab.
    expect(document.title).toBe(NOT_FOUND_DOCUMENT_TITLE);
  });

  it("does not touch any other page's file", () => {
    arriveWith('Terms & Conditions — Fynora', 'The terms page description.');
    dropNotFoundDocumentText();
    expect(content('name', 'description')).toBe('The terms page description.');
    expect(content('property', 'og:title')).toBe('Terms & Conditions — Fynora');
    expect(content('property', 'og:description')).toBe('The terms page description.');
  });

  it('does nothing in a head with no og:title, and adds no tags', () => {
    dropNotFoundDocumentText();
    for (const [attr, key] of TAGS) expect(content(attr, key)).toBeNull();
  });

  it('is called by main.tsx before the first render', () => {
    const here = path.dirname(fileURLToPath(import.meta.url));
    const main = fs.readFileSync(path.join(here, '../main.tsx'), 'utf-8');
    const call = main.indexOf('dropNotFoundDocumentText();');
    expect(call).toBeGreaterThan(-1);
    expect(call).toBeLessThan(main.indexOf('ReactDOM.createRoot('));
  });
});
