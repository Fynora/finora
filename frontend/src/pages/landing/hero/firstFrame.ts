/**
 * Whether the hero's copy was already on screen, prerendered, when the real landing page mounted.
 *
 * dist/index.html carries a static first frame of the hero (HomeCrawlerFallback renders
 * <Hero firstFrame />, see scripts/prerender.mjs). main.tsx mounts with createRoot, not
 * hydrateRoot, so React replaces that markup with new nodes. If the real hero then ran its usual
 * entrance, the headline a visitor was already reading would vanish to opacity 0 and fade back in.
 * Hero reads this once to skip that entrance for the copy column only.
 *
 * Read at module evaluation, which happens after the HTML has been parsed (the bundle is a module
 * script) and before React's first render replaces anything. Only the homepage path counts. A host
 * without the rewrite rules and the 404 page answers every unmatched path with index.html
 * (`wrangler dev` does, see scripts/prerender.mjs). There the first frame is long gone by the time
 * a visitor reaches "/" and this hero mounts, so its usual entrance is right.
 */
const FIRST_FRAME_SELECTOR = '[data-hero-first-frame]';

let shownBeforeMount =
  typeof document !== 'undefined' &&
  window.location.pathname === '/' &&
  document.querySelector(FIRST_FRAME_SELECTOR) !== null;

export function heroFirstFrameWasShown(): boolean {
  return shownBeforeMount;
}

/** Called once the real hero has mounted, so a later client-side visit to "/" animates as usual. */
export function markHeroFirstFrameReplaced(): void {
  shownBeforeMount = false;
}
