import { useEffect, useRef, useState } from 'react';
import { ArrowRight } from 'lucide-react';
import { Nav } from './landing/Nav';
import { MagneticLink } from './landing/MagneticLink';
import { Hero } from './landing/Hero';
import { Problem } from './landing/Problem';
import { ImportSection } from './landing/ImportSection';
import { LearningSection } from './landing/LearningSection';
import { BeforeAfter } from './landing/BeforeAfter';
import { Trust } from './landing/Trust';
import { Security } from './landing/Security';
import { Capabilities } from './landing/Capabilities';
import { AskFyn } from './landing/AskFyn';
import { TrustStrip } from './landing/TrustStrip';
import { Everywhere } from './landing/Everywhere';
import { UseCases } from './landing/UseCases';
import { Pricing } from './landing/Pricing';
import { Faq } from './landing/Faq';
import { FinalCta } from './landing/FinalCta';
import { SiteFooter } from './landing/SiteFooter';
import { Transition } from './landing/primitives';
import { useCanonical } from '../hooks/useCanonical';

/**
 * The landing page, as composition only.
 *
 * Every section is its own file under ./landing so copy, layout and animation can be changed --
 * or A/B tested, or replaced entirely -- without opening a thousand-line component. This file is
 * the running order and nothing else; if you are here to edit words, you are in the wrong file.
 *
 * THE RUNNING ORDER IS THE ARGUMENT. It is not a list of things the product does, it is one
 * continuous claim, and each section only makes sense in its position:
 *
 *   Hero .............. upload a statement, see where every rupee went
 *   Trust strip ....... is it safe to upload it here?         <- the anxiety, answered first
 *   Problem ........... reading it is the hard part          <- their month, named
 *   Read correctly .... so hand it over once, and we check   <- the lead story and its proof
 *   Learning .......... and it remembers your corrections
 *   Before / After .... this is what changes                 <- the payoff, stated plainly
 *   Capabilities ...... everything the statement can tell you
 *   Ask Fyn ........... and you can ask about it
 *   Trust ............. here is why we won't abuse it        <- the objection, met
 *   Security .......... and here is how it is protected
 *   Everywhere ........ wherever you are
 *   Use cases ......... whoever you are
 *   Pricing ........... free to start, Plus for more
 *   FAQ ............... the last few doubts
 *   Final CTA ......... your next statement is already coming
 *
 * Reordering sections breaks the argument even though nothing will error. Before moving one, work
 * out which question it answers and whether that question has been raised yet.
 *
 * The <Transition> bands are why this reads as one page rather than fourteen. Each interpolates
 * the surface it leaves into the surface it enters, so no boundary lands as a hard edge -- most
 * of all where the page falls into a dark band, which without the bleed reads as a slide change.
 * Their colours must match the adjoining sections' `tone`; a mismatch shows as a visible seam.
 *
 * Claim discipline, carried forward and non-negotiable on a financial product: no invented
 * testimonials, no fabricated counters, no customer logos, and nothing described as available
 * that isn't. Where a capability is real but unreleased -- the native apps, the paid tiers -- the
 * copy says so. Several sections carry a comment recording exactly what was verified; keep those
 * up to date rather than deleting them.
 */

const WHITE = '#FFFFFF';
const ALT = 'var(--m-surface-alt)'; // what `tone="alt"` paints (index.css); a hex copy drifted from it
const DEEP = '#15171C'; // --color-deep-surface -- see index.css's comment on that token

// Nav.tsx's own h-16 (64px) header height -- the rootMargin below shrinks the observer's
// effective viewport by exactly this much, so overHero flips the moment Hero's bottom edge
// scrolls up behind the navbar, not the literal top of the viewport.
const NAV_HEIGHT_PX = 64;

export default function Landing() {
  useCanonical('/');
  const heroRef = useRef<HTMLDivElement | null>(null);
  // Hero fills (or exceeds) the viewport at page load -- see the global chrome design spec's note
  // on why observing the Hero element itself (rather than a 1px sentinel at its trailing edge)
  // makes isIntersecting already correct without extra boundingClientRect math -- so `true` is
  // the honest initial value before the observer's first callback fires.
  const [overHero, setOverHero] = useState(true);

  useEffect(() => {
    const node = heroRef.current;
    if (!node) return;
    const observer = new IntersectionObserver(
      ([entry]) => setOverHero(entry.isIntersecting),
      { rootMargin: `-${NAV_HEIGHT_PX}px 0px 0px 0px`, threshold: 0 }
    );
    observer.observe(node);
    return () => observer.disconnect();
  }, []);

  return (
    <div className="marketing">
      {/* Keyboard/screen-reader users otherwise have to tab through the entire nav (5 anchors +
          2 CTAs) before reaching any page content, on a page with 15 sections below it. Visually
          hidden until focused, per the standard sr-only/focus:not-sr-only pattern. */}
      <a
        href="#main-content"
        className="sr-only focus:not-sr-only focus:fixed focus:top-3 focus:left-3 focus:z-50 focus:rounded-lg focus:bg-[var(--m-brand)] focus:px-4 focus:py-2.5 focus:text-sm focus:font-semibold focus:text-white"
      >
        Skip to content
      </a>
      <Nav overHero={overHero} />

      <main id="main-content">
        <div ref={heroRef}>
          <Hero />
        </div>
        {/* Hero's dark radial-gradient background bottoms out at #15171C (see Hero.tsx) -- this
            bridges that into white, the same way every other section boundary on this page does.
            Hero does not own any of its own exit fade; this band is the single place that does. */}
        <Transition from="#15171C" to={WHITE} height={80} />

        <TrustStrip />
        <Problem />
        <Transition from={WHITE} to={WHITE} height={0} />

        <ImportSection />
        <Transition from={WHITE} to={DEEP} height={112} />

        <LearningSection />
        <Transition from={DEEP} to={ALT} height={112} />

        <BeforeAfter />
        <Transition from={ALT} to={WHITE} />

        <Capabilities />
        <Transition from={WHITE} to={ALT} />

        <AskFyn />
        <Transition from={ALT} to={DEEP} height={112} />

        <Trust />
        <Transition from={DEEP} to={ALT} height={112} />

        <Security />
        <Transition from={ALT} to={WHITE} />

        <Everywhere />
        <Transition from={WHITE} to={ALT} />

        {/* UseCases, Pricing and Faq are all the alt tone, so they meet without a band. A white
            band between Pricing and Faq showed as a pale stripe between two identical surfaces. */}
        <UseCases />
        <Pricing />
        <Faq />
        <Transition from={ALT} to="var(--m-brand)" height={72} />

        {/* No <Transition> needed here: FinalCta's gradient ends at #15171C, the same value
            --color-deep-surface now resolves to, so SiteFooter's background already picks up
            exactly where FinalCta leaves off. (This boundary used to need a bridge -- see PR
            #1419 -- back when --color-deep-surface was still the old cool-navy hex.) */}
        <FinalCta />
      </main>
      <SiteFooter />

      {/* Sticky mobile action bar. Phone-only: at md+ the hero CTAs and the nav button are both
          still in reach, so a permanent bar would only cover content. */}
      <div className="m-mobile-cta md:hidden">
        <MagneticLink to="/auth" className="m-btn m-btn-primary w-full">
          Import your first statement <ArrowRight size={16} />
        </MagneticLink>
      </div>
      {/* Reserves the space the fixed bar covers so the footer's last line stays reachable. */}
      <div className="h-20 md:hidden" aria-hidden="true" />
    </div>
  );
}
