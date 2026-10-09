import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { mockMatchMedia } from '../../test/mockMatchMedia';

vi.mock('framer-motion', async (importOriginal) => {
  const actual = await importOriginal<typeof import('framer-motion')>();
  return { ...actual, useReducedMotion: vi.fn() };
});

vi.mock('./hero/firstFrame', () => ({
  heroFirstFrameWasShown: vi.fn(() => false),
  markHeroFirstFrameReplaced: vi.fn(),
}));

import { useReducedMotion } from 'framer-motion';
import { heroFirstFrameWasShown, markHeroFirstFrameReplaced } from './hero/firstFrame';
import { hero, heroScore, heroIntelligence } from './landing-config';
import { Hero } from './Hero';

function renderHero(props: { firstFrame?: boolean } = {}) {
  return render(
    <MemoryRouter>
      <Hero {...props} />
    </MemoryRouter>
  );
}

// The element wrapping the headline: the copy column, motion.div on the real hero.
function copyColumn(): HTMLElement {
  return screen.getByRole('heading', { level: 1 }).parentElement as HTMLElement;
}

describe('Hero', () => {
  let restoreMatchMedia: (() => void) | undefined;

  afterEach(() => {
    vi.clearAllMocks();
    restoreMatchMedia?.();
    restoreMatchMedia = undefined;
  });

  it('renders the existing headline, blurb and CTAs unchanged', () => {
    vi.mocked(useReducedMotion).mockReturnValue(false);
    renderHero();
    expect(screen.getByText(hero.headline)).toBeInTheDocument();
    expect(screen.getByText(hero.headlineAccent)).toBeInTheDocument();
    expect(screen.getByText(hero.blurb)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: new RegExp(hero.primaryCta) })).toBeInTheDocument();
  });

  it('renders the score ring and intelligence scan', () => {
    vi.mocked(useReducedMotion).mockReturnValue(false);
    renderHero();
    // AnalysisSequence gates the ring's drawn value and the checklist's animated content behind
    // its own useStagedReveal, which -- correctly -- doesn't reach 84/all-checked until the real
    // IntersectionObserver fires (jsdom's is a defined no-op that never does, so asserting the
    // animated end-state here would test IntersectionObserver, not this component). The ring's
    // aria-label carries the real score unconditionally (see HealthScoreRing.test.tsx), and the
    // checklist heading and every step's label are always in the DOM regardless of reveal
    // progress (see IntelligenceScan.test.tsx) -- those are what this test can honestly assert.
    expect(
      screen.getByRole('img', { name: `Financial health score ${heroScore.value} out of 100` })
    ).toBeInTheDocument();
    expect(screen.getByText(heroIntelligence.heading)).toBeInTheDocument();
    heroIntelligence.steps.forEach((step) => {
      expect(screen.getByText(step)).toBeInTheDocument();
    });
  });

  it('under prefers-reduced-motion, renders the same final content and never mounts AmbientCanvas', () => {
    vi.mocked(useReducedMotion).mockReturnValue(true);
    // useStagedReveal reads prefers-reduced-motion via window.matchMedia directly (not via
    // framer-motion's useReducedMotion, mocked above for Hero's own reveal wrappers) -- without
    // this, AnalysisSequence's shared step counter stays at 0 in jsdom the same way it would for
    // any visitor whose IntersectionObserver never fires, and the ring/checklist would never
    // reach their real values here even though this test is specifically about reduced motion.
    restoreMatchMedia = mockMatchMedia({ '(prefers-reduced-motion: reduce)': true });
    const { container } = renderHero();

    // Content is still all there -- reduced motion changes HOW it appears, never WHAT appears.
    expect(screen.getByText(hero.headline)).toBeInTheDocument();
    expect(screen.getByText(String(heroScore.value))).toBeInTheDocument();

    // AmbientCanvas's own gate returns null under reduced motion (see AmbientCanvas.test.tsx);
    // this asserts that behavior actually reaches the page, not just the unit in isolation --
    // jsdom has no real WebGL either way, but the canvas element itself would still appear if the
    // gate were bypassed.
    expect(container.querySelector('canvas')).not.toBeInTheDocument();
  });

  it('starts the copy column hidden for its entrance on an ordinary mount', () => {
    vi.mocked(useReducedMotion).mockReturnValue(false);
    renderHero();
    expect(copyColumn().style.opacity).toBe('0');
    expect(markHeroFirstFrameReplaced).toHaveBeenCalledTimes(1);
  });

  it('skips the copy column entrance when it replaces the prerendered first frame', () => {
    // The visitor is already reading this headline; hiding it to fade it back in would blink it.
    vi.mocked(useReducedMotion).mockReturnValue(false);
    vi.mocked(heroFirstFrameWasShown).mockReturnValueOnce(true);
    const { container } = renderHero();
    expect(copyColumn().style.opacity).toBe('1');
    // Only the copy column: the dashboard preview was not in the first frame, so it still enters.
    const dashboardColumn = container.querySelector<HTMLElement>('.grid > .relative');
    expect(dashboardColumn?.style.opacity).toBe('0');
  });

  it('firstFrame renders the copy column only: no motion, no dashboard, no score, no canvas', () => {
    vi.mocked(useReducedMotion).mockReturnValue(false);
    const { container } = renderHero({ firstFrame: true });
    expect(container.querySelector('section')).toHaveAttribute('data-hero-first-frame', '');
    expect(screen.getByText(hero.headline)).toBeInTheDocument();
    expect(screen.getByText(hero.blurb)).toBeInTheDocument();
    expect(copyColumn().getAttribute('style')).toBeNull();
    expect(screen.queryByRole('img', { name: /Financial health score/ })).not.toBeInTheDocument();
    expect(screen.queryByText(heroIntelligence.heading)).not.toBeInTheDocument();
    expect(container.querySelector('canvas')).not.toBeInTheDocument();
  });

  it('the real hero does not carry the first-frame marker', () => {
    vi.mocked(useReducedMotion).mockReturnValue(false);
    const { container } = renderHero();
    expect(container.querySelector('[data-hero-first-frame]')).not.toBeInTheDocument();
  });
});
