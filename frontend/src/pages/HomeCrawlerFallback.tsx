import { askFyn, capabilities, faq, hero, importSection, problem } from './landing/landing-config';
import { PublicFooter, PublicSection } from '../components/PublicLayout';
import { Nav } from './landing/Nav';
import { Hero } from './landing/Hero';
import { Transition } from './landing/primitives';
import { MagneticLink } from './landing/MagneticLink';
import { ArrowRight } from 'lucide-react';

/**
 * The homepage as dist/index.html carries it, rendered by the prerender step (scripts/prerender.mjs).
 * Landing.tsx (the real homepage) is not rendered there. Its hero probes for WebGL and mounts the
 * @react-three/fiber layer, which need a browser (the probe reads `document` during render, so plain
 * Node throws), and its entrance starts every hero column at opacity 0, which would serve invisible
 * text to anything that does not run the bundle.
 *
 * This page has two readers.
 *
 * 1. Visitors, before the bundle runs. The top of this page is the real Nav and the real hero's
 *    first frame (<Hero firstFrame />), so the first paint already looks like the landing page,
 *    and the headline, the largest thing above the fold, paints at its final size. That is the
 *    Largest Contentful Paint. It used to be a different, plain layout, so LCP waited for the main
 *    bundle and React's first render, then jumped to the real headline. main.tsx mounts with
 *    ReactDOM.createRoot(...).render(...), not hydrateRoot, so React still replaces all of this
 *    with the real Landing. Keep the hero's copy column identical to the real one, because a
 *    larger headline painted later by React would count as a new LCP. Hero.tsx explains this, and
 *    so does the font preload that scripts/prerender.mjs adds for the headline face.
 *
 * 2. Anything that never runs the bundle: a crawler (including Google's OAuth-branding
 *    verification bot, which flagged the old blank-shell homepage as "does not explain the
 *    purpose of your app"), an AI crawler, or a no-JS visitor. Below the hero, this page keeps
 *    the proof, the capabilities, Ask Fyn and the FAQ as plain prose, and the footer keeps every
 *    policy link that verification looks for.
 *
 * Every sentence below is copied verbatim from landing-config.ts, the landing page's own reviewed
 * copy (see that file's "every sentence is a public claim" rule) -- nothing new is asserted here.
 */
export function HomeCrawlerFallback() {
  return (
    <div className="marketing">
      <Nav overHero />
      <main id="main-content">
        {/* The same wrapper Landing gives Hero (its IntersectionObserver target), so the first
            frame lays out exactly where the real hero will. */}
        <div>
          <Hero firstFrame />
        </div>
        <Transition from="#15171C" to="#FFFFFF" height={80} />
        <div className="bg-bg text-ink">
          <div className="max-w-4xl mx-auto px-6 py-14">
            <PublicSection title={problem.title}>
              <p>
                {problem.closer} {problem.closerMuted}
              </p>
            </PublicSection>
            {/* The ids are the real sections' ids (ImportSection, Capabilities, AskFyn, Faq), so the
                Nav links and the hero's "See how it works" button above already go somewhere
                before the real page mounts, and for a visitor without JavaScript. Landing scrolls
                to the same fragment again once it has replaced this page. "#trust" and "#pricing"
                have no section here. */}
            <PublicSection id="how" title={importSection.title + ' ' + importSection.titleLine2}>
              <p>{importSection.blurb}</p>
              {importSection.proofs.map((p) => (
                <p key={p.title}>
                  <strong>{p.title}.</strong> {p.body}
                </p>
              ))}
            </PublicSection>
            <PublicSection id="features" title={capabilities.title + ' ' + capabilities.titleLine2}>
              <p>{capabilities.blurb}</p>
              <ul>
                {capabilities.items.map((i) => (
                  <li key={i.title}>
                    <strong>{i.title}{i.plan ? ` (${i.plan})` : ''}.</strong> {i.body}
                  </li>
                ))}
              </ul>
            </PublicSection>
            <PublicSection id="ask-fyn" title={askFyn.title}>
              <p>{askFyn.blurb}</p>
            </PublicSection>
            <PublicSection id="faq" title={faq.title}>
              {faq.items.map(([q, a]) => (
                <p key={q}>
                  <strong>{q}</strong> {a}
                </p>
              ))}
            </PublicSection>
          </div>
        </div>
      </main>
      <div className="bg-bg">
        <PublicFooter />
      </div>

      {/* Landing's sticky mobile action bar and the spacer that keeps the footer clear of it, so
          the phone's first frame has the bar the real page will have. */}
      <div className="m-mobile-cta md:hidden">
        <MagneticLink to="/auth" className="m-btn m-btn-primary w-full">
          {hero.primaryCta} <ArrowRight size={16} />
        </MagneticLink>
      </div>
      <div className="h-20 md:hidden" aria-hidden="true" />
    </div>
  );
}
