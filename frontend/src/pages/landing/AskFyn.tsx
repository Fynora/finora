import { MessageCircle } from 'lucide-react';
import { Reveal, Section, SectionHeading } from './primitives';
import { askFyn } from './landing-config';

/**
 * Ask Fyn: live, read-only, and honest about where a question goes. The example prompts are the
 * four real ones in FynWidget; no answers are mocked, so no invented figure appears here.
 */
export function AskFyn() {
  return (
    <Section id="ask-fyn" tone="alt">
      <SectionHeading eyebrow={askFyn.eyebrow} title={askFyn.title} blurb={askFyn.blurb} />
      <div className="grid lg:grid-cols-2 gap-10 items-center max-w-4xl mx-auto">
        <Reveal>
          <p className="m-eyebrow mb-3">Try asking</p>
          <ul className="space-y-3">
            {askFyn.examples.map((e) => (
              <li key={e} className="flex items-center gap-3 rounded-2xl px-4 py-3 text-[15px]" style={{ background: '#fff', border: '1px solid var(--m-line)', color: 'var(--m-ink)' }}>
                <MessageCircle size={16} className="shrink-0" style={{ color: 'var(--m-brand)' }} aria-hidden="true" />
                {e}
              </li>
            ))}
          </ul>
        </Reveal>
        <Reveal delayMs={120}>
          <ul className="space-y-3 text-[15px] leading-relaxed" style={{ color: 'var(--m-ink-2)' }}>
            {askFyn.points.map((p) => <li key={p}>{p}</li>)}
          </ul>
          <p className="text-sm leading-relaxed mt-6 rounded-xl px-4 py-3" style={{ background: 'var(--m-brand-wash)', color: 'var(--m-ink-2)' }}>
            {askFyn.disclosure}
          </p>
        </Reveal>
      </div>
    </Section>
  );
}
