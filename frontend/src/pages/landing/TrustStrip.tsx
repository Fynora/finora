import { ShieldCheck } from 'lucide-react';
import { Reveal } from './primitives';
import { trustStrip } from './landing-config';

/**
 * The answer to "is it safe to upload my statement here?", directly under the hero. Its own
 * white band rather than a Section: it is a strip, not a full-height beat, so it skips Section's
 * vertical padding.
 */
export function TrustStrip() {
  return (
    <section aria-label="Trust" style={{ background: '#FFFFFF', borderBottom: '1px solid var(--m-line)' }}>
      <Reveal>
        <ul className="max-w-6xl mx-auto px-5 sm:px-6 py-5 grid sm:grid-cols-3 gap-3 sm:gap-6">
          {trustStrip.map((line) => (
            <li key={line} className="flex items-center justify-center gap-2 text-sm font-medium text-center" style={{ color: 'var(--m-ink-2)' }}>
              <ShieldCheck size={16} className="shrink-0" style={{ color: 'var(--m-brand)' }} aria-hidden="true" />
              {line}
            </li>
          ))}
        </ul>
      </Reveal>
    </section>
  );
}
