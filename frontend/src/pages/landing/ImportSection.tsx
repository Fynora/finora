import { CheckCircle2, FileCheck2, PauseCircle, ShieldCheck } from 'lucide-react';
import { Eyebrow, Reveal, Section } from './primitives';
import { importSection } from './landing-config';
import { StatementDemo } from './import-story/StatementDemo';

// One icon per proof card, in the order of importSection.proofs.
const PROOF_ICONS = [ShieldCheck, PauseCircle, CheckCircle2, FileCheck2];

/**
 * Import, shown as a mechanism rather than described as one -- and this is the page's lead story,
 * "statements read correctly", so the four cards under the scene are the proof (each traced to code
 * in landing-config.ts).
 *
 * The mechanism is StatementDemo: a sample statement the visitor clicks to run, showing real
 * categorized sample output -- not a scripted animation that plays itself on scroll. Owner
 * decision, 2026-09-22, replacing the earlier ImportRevealSequence (a reveal-once scan animation
 * with no real interaction; see git history). StatementDemo is a real interactive control with
 * real result content, so unlike its predecessor it is not aria-hidden -- the real information is
 * this section's own copy either way, always in normal document flow regardless of demo state.
 *
 * This used to be a pinned, GSAP-ScrollTrigger-scrubbed sequence on desktop (mobile/reduced-motion
 * already used a reveal-once fallback -- see git history). Dropped after real user feedback that
 * pinning the page for ~2.5 screen-heights of scroll to watch three beats felt bad regardless of
 * how short the distance was tuned to -- not a length problem to tune away, a mechanic problem.
 *
 * Every format listed is genuinely supported today -- password-protected PDFs and multi-account
 * composite statements included. Nothing aspirational in this list. Scanned (image-only) PDFs are
 * deliberately not listed: OCR exists but its accuracy on real scans has not been measured.
 *
 * id="how" because this is the "How it works" the nav and the hero button point at.
 */
export function ImportSection() {
  return (
    <Section id="how">
      <div className="grid lg:grid-cols-2 gap-14 items-center">
        <Reveal>
          <Eyebrow>{importSection.eyebrow}</Eyebrow>
          <h2 className="m-h2 mb-4">{importSection.title}<br />{importSection.titleLine2}</h2>
          <p className="m-lead mb-6">{importSection.blurb}</p>
          <div className="flex flex-wrap gap-2">
            {importSection.supported.map((s) => (
              <span key={s} className="text-xs font-medium px-3 py-1.5 rounded-full" style={{ background: 'var(--m-brand-wash)', color: 'var(--m-brand-deep)' }}>
                {s}
              </span>
            ))}
          </div>
        </Reveal>

        <Reveal delayMs={120}>
          <StatementDemo />
        </Reveal>
      </div>

      <div className="grid sm:grid-cols-2 lg:grid-cols-4 gap-4 mt-14">
        {importSection.proofs.map((p, i) => {
          const Icon = PROOF_ICONS[i];
          return (
            <Reveal key={p.title} delayMs={i * 70}>
              <div className="m-card p-6 h-full">
                <span className="w-10 h-10 rounded-xl grid place-items-center mb-4" style={{ background: 'var(--m-brand-wash)', color: 'var(--m-brand)' }}>
                  <Icon size={18} aria-hidden="true" />
                </span>
                <h3 className="m-h3 text-[15px] mb-1.5">{p.title}</h3>
                <p className="text-sm leading-relaxed" style={{ color: 'var(--m-ink-2)' }}>{p.body}</p>
              </div>
            </Reveal>
          );
        })}
      </div>
    </Section>
  );
}
