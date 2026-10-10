import { useEffect } from 'react';
import { HOME_TITLE, SITE_DESCRIPTION } from '../lib/siteUrl';

function setContent(selector: string, content: string): void {
  // Only a tag that is already there: index.html has all three, and a head without them (a test
  // that never loaded index.html) is left alone rather than given tags the real page does not add.
  document.head.querySelector<HTMLMetaElement>(selector)?.setAttribute('content', content);
}

/**
 * Makes the description and the social-preview text describe the page that is mounted:
 * `<meta name="description">`, `og:title` and `og:description`. On unmount they go back to the
 * SITE's defaults (the homepage's, see siteUrl.ts), not to what was there before.
 *
 * "What was there before" is the first document's text, and it outlives that document's page.
 * Measured in Chrome on production, 2026-10-10: open /terms, go to the homepage inside the app, and
 * the homepage's description, og:title and og:description were all still the terms page's. The
 * homepage set none of its own, and every page restored whatever it had found.
 *
 * A page with no description of its own (`null`) gets the site's.
 *
 * Link-preview crawlers do not run JavaScript, so for them these tags only matter in the
 * prerendered files (scripts/prerender.mjs). This is for everything that reads the live page.
 */
export function usePageDescription(title: string, description: string | null): void {
  useEffect(() => {
    const text = description?.trim() || SITE_DESCRIPTION;
    setContent('meta[name="description"]', text);
    setContent('meta[property="og:title"]', title);
    setContent('meta[property="og:description"]', text);
    return () => {
      setContent('meta[name="description"]', SITE_DESCRIPTION);
      setContent('meta[property="og:title"]', HOME_TITLE);
      setContent('meta[property="og:description"]', SITE_DESCRIPTION);
    };
  }, [title, description]);
}
