import { askFyn, capabilities, faq, hero, importSection, problem } from './landing/landing-config';
import { PublicLayout, PublicSection } from '../components/PublicLayout';

/**
 * Server-rendered stand-in for the homepage's initial paint. Landing.tsx (the real homepage) pulls
 * in several @react-three/fiber components for its ambient WebGL/canvas visuals, which need a real
 * browser GL context and throw under plain Node SSR -- so this component, not Landing itself, is
 * what the prerender step (scripts/prerender.mjs) renders into dist/index.html.
 *
 * main.tsx mounts via ReactDOM.createRoot(...).render(...), not hydrateRoot, so there is no
 * hydration-mismatch risk: a JS-enabled browser replaces this content with the real animated
 * Landing page the instant the bundle runs. This markup only matters to whoever/whatever reads the
 * page before that -- a crawler (including Google's OAuth-branding verification bot, which flagged
 * the previous blank-shell homepage as "does not explain the purpose of your app"), an AI crawler
 * that does not run JavaScript, or a no-JS visitor. It used to hold three short paragraphs, so the
 * proof, the capabilities, Ask Fyn and the FAQ were invisible to anything that did not run the
 * bundle.
 *
 * Every sentence below is copied verbatim from landing-config.ts, the landing page's own reviewed
 * copy (see that file's "every sentence is a public claim" rule) -- nothing new is asserted here.
 */
export function HomeCrawlerFallback() {
  return (
    <PublicLayout title={hero.headline} subtitle={hero.headlineAccent}>
      <PublicSection title="What Fynora does">
        <p>{hero.blurb}</p>
      </PublicSection>
      <PublicSection title={problem.title}>
        <p>
          {problem.closer} {problem.closerMuted}
        </p>
      </PublicSection>
      <PublicSection title={importSection.title + ' ' + importSection.titleLine2}>
        <p>{importSection.blurb}</p>
        {importSection.proofs.map((p) => (
          <p key={p.title}>
            <strong>{p.title}.</strong> {p.body}
          </p>
        ))}
        <p>{importSection.cardNote}</p>
      </PublicSection>
      <PublicSection title={capabilities.title + ' ' + capabilities.titleLine2}>
        <p>{capabilities.blurb}</p>
        <ul>
          {capabilities.items.map((i) => (
            <li key={i.title}>
              <strong>{i.title}{i.plan ? ` (${i.plan})` : ''}.</strong> {i.body}
            </li>
          ))}
        </ul>
      </PublicSection>
      <PublicSection title={askFyn.title}>
        <p>{askFyn.blurb}</p>
        <p>{askFyn.disclosure}</p>
      </PublicSection>
      <PublicSection title={faq.title}>
        {faq.items.map(([q, a]) => (
          <p key={q}>
            <strong>{q}</strong> {a}
          </p>
        ))}
      </PublicSection>
    </PublicLayout>
  );
}
