import { useEffect, useState } from 'react';
import { ArrowRight, Check } from 'lucide-react';
import { motion, useReducedMotion } from 'framer-motion';
import { hero } from './landing-config';
import { AmbientCanvas } from './hero/AmbientCanvas';
import { FloatingDashboardCard } from './hero/FloatingDashboardCard';
import { AnalysisSequence } from './hero/AnalysisSequence';
import { FloatingBadges } from './hero/FloatingBadges';
import { heroFirstFrameWasShown, markHeroFirstFrameReplaced } from './hero/firstFrame';
import { MagneticLink, MagneticAnchor } from './MagneticLink';

const EASE = [0.16, 1, 0.3, 1] as const;

/**
 * Cinematic reveal for the dark hero band. See
 * docs/superpowers/specs/2026-08-22-hero-cinematic-reveal-design.md -- the mount sequence
 * ("background/particles -> content -> dashboard -> score/insights") is staggered via a fixed
 * per-section `delay` on each motion.div's own `animate`, not via Framer Motion's
 * staggerChildren/variant-propagation mechanism: that mechanism relies on child motion
 * components inheriting their parent's `animate` label through React context, which -- verified
 * against a real browser during implementation, not just jsdom -- got stuck permanently at each
 * child's `initial` state under this app's React.StrictMode in main.tsx (children never reached
 * "show"). Explicit per-instance `initial`/`animate`/`transition` sidesteps that failure mode
 * entirely and is what every sub-component below (FloatingDashboardCard, FloatingBadges) already
 * does for the same reason.
 *
 * The ambient WebGL layer, the score ring and the intelligence-scan checklist are separate
 * components reusing (not replacing) the existing Reveal/CountUp/useStagedReveal primitives. The
 * dashboard preview itself is always real DOM -- never rendered inside WebGL -- so it stays crisp,
 * accessible and never depends on animation state to be understood.
 *
 * Copy lives in ./landing-config, unchanged from before this rewrite -- this file decides how the
 * hero looks, not what it says. See landing-config.ts's own note on the claim-review discipline
 * that applies to every sentence here.
 *
 * The fade from this section's dark background into white belongs to Landing.tsx's <Transition>
 * band immediately after <Hero />, like every other section boundary on this page -- Hero does
 * NOT own its own exit fade.
 *
 * `firstFrame` renders the static first frame that scripts/prerender.mjs bakes into
 * dist/index.html (through HomeCrawlerFallback): the same section, container, grid and copy
 * column, with no motion, no WebGL and no dashboard column. It exists for Largest Contentful
 * Paint. The headline is the largest thing above the fold, and before this the prerendered page
 * was a different, plain layout, so the headline only painted once the main bundle had downloaded
 * and React had rendered: about 1.7 s after first paint on a throttled mobile load. With the
 * headline in the HTML at its final size, the paint that counts happens at first paint. Its copy
 * column must stay identical to the real one. A larger headline painted later by React counts as a
 * new, later LCP. When the real hero replaces this frame, the copy column skips its entrance (see
 * hero/firstFrame.ts), so the headline the visitor is already reading does not blink out and back.
 */
export function Hero({ firstFrame = false }: { firstFrame?: boolean }) {
  const prefersReducedMotion = useReducedMotion();
  const [copyAlreadyShown] = useState(heroFirstFrameWasShown);
  useEffect(() => {
    markHeroFirstFrameReplaced();
  }, []);

  function reveal(delay: number, alreadyShown = false) {
    return prefersReducedMotion || alreadyShown
      ? { initial: false as const, animate: { opacity: 1, y: 0 }, transition: { duration: 0 } }
      : {
          initial: { opacity: 0, y: 24 },
          animate: { opacity: 1, y: 0 },
          transition: { duration: 0.6, ease: EASE, delay },
        };
  }

  // The copy column, shared by the real hero and its prerendered first frame so the two cannot drift.
  const copy = (
    <>
      <h1 className="m-display mb-5" style={{ color: '#F8FAFC' }}>
        {hero.headline}
        <br />
        <span style={{ color: 'var(--m-success)' }}>{hero.headlineAccent}</span>
      </h1>
      <p className="m-lead mb-8 max-w-lg" style={{ color: '#94A3B8' }}>
        {hero.blurb}
      </p>
      <div className="flex flex-col sm:flex-row gap-3 mb-8">
        <MagneticLink to="/auth" className="m-btn m-btn-primary w-full sm:w-auto">
          {hero.primaryCta} <ArrowRight size={16} />
        </MagneticLink>
        <MagneticAnchor
          href="#how"
          className="m-btn m-btn-ghost w-full sm:w-auto"
          style={{ background: 'transparent', color: '#F8FAFC', borderColor: 'rgba(255,255,255,0.25)' }}
        >
          {hero.secondaryCta}
        </MagneticAnchor>
      </div>
      <ul className="grid sm:grid-cols-2 gap-x-6 gap-y-2.5">
        {hero.assurances.map((t) => (
          <li key={t} className="flex items-center gap-2 text-sm" style={{ color: '#94A3B8' }}>
            <Check size={15} className="shrink-0" style={{ color: 'var(--m-success)' }} />
            {t}
          </li>
        ))}
      </ul>
    </>
  );

  return (
    <section
      data-hero-first-frame={firstFrame ? '' : undefined}
      className="relative overflow-hidden"
      style={{
        // Warm-graphite family, matching the rest of the app's Phase 4 palette (--m-brand
        // #262A33 at the 55% stop, --m-brand-deep #15171C at 100%) instead of the cool navy
        // this used to be -- see index.css's comment on --color-deep-surface for why that navy
        // was never actually part of any token system. #414757 (0% stop, the glow's brightest
        // point) is #262A33 scaled ~1.7x per channel, the same proportion the old gradient used
        // between its own 0%/55% stops -- not a new hue, just lightened.
        background:
          'radial-gradient(120% 100% at 50% -10%, #414757 0%, #262A33 55%, #15171C 100%)',
      }}
    >
      {firstFrame ? null : <AmbientCanvas />}

      <div className="relative z-10 max-w-6xl mx-auto px-5 sm:px-6 pt-28 pb-24 lg:pt-36 lg:pb-32">
        {/* Text column widened from 1fr (was narrower than the dashboard mock's 1.15fr) to 1.1fr:
            at the .m-display clamp's own max size, "Money tells a story." and "Fynora helps you
            read it." each wrapped to 2 lines (4 total) inside the old, narrower column -- more
            than the 2-line hero-headline budget. Paired with the smaller clamp max below. */}
        <div className="grid lg:grid-cols-[minmax(0,1.1fr)_minmax(0,1fr)] gap-14 items-start lg:items-center">
          {firstFrame ? (
            <div>{copy}</div>
          ) : (
            <motion.div {...reveal(0, copyAlreadyShown)}>{copy}</motion.div>
          )}

          {firstFrame ? null : (
            <motion.div {...reveal(0.25)} className="relative">
              <FloatingDashboardCard />
              <FloatingBadges />
            </motion.div>
          )}
        </div>

        {/* Health score + intelligence scan get their own full-width row after the two-column
            grid, not squeezed into the dashboard's column -- they're a claim ABOUT the product
            shown above, and read as disconnected filler crammed under one side of it. Still part
            of this same dark section (not a new <Transition>-bounded band): the claim belongs to
            Hero's story, just after the visual it's describing rather than boxed inside it. */}
        {firstFrame ? null : (
          <motion.div {...reveal(0.5)} className="mt-14">
            <AnalysisSequence />
          </motion.div>
        )}
      </div>
    </section>
  );
}
