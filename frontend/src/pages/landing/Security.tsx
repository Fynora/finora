import { Link } from 'react-router-dom';
import { Database, Fingerprint, Lock, MonitorSmartphone, Server } from 'lucide-react';
import { Reveal, Section, SectionHeading } from './primitives';
import { security } from './landing-config';

/**
 * Security, drawn as a path rather than asserted as an adjective.
 *
 * The labels are deliberately plain -- "You", "HTTPS", "Private storage". Nobody outside this
 * team has an intuition for bcrypt or JWT verification, and a wall of cryptographic nouns reads
 * as intimidation rather than reassurance. The mechanism is real either way; the picture is what
 * gets understood.
 *
 * Every step was checked against the code before it was written:
 *
 *  - TLS in transit is true.
 *  - bcrypt at cost 12 is literal (SecurityConfig).
 *  - Per-request JWT verification and per-user scoping are how every protected route works.
 *  - Statement files are content-addressed and their digest is re-derived on read, so a swapped
 *    or corrupted object is detected rather than served (ContentAddress / StatementStorage).
 *
 * What is NOT claimed, on purpose: "bank-level encryption" or "end-to-end encryption". Fynora
 * does encrypt statement files itself (AES-256-GCM, StatementContentService since V107, and staged
 * uploads since V304), but not transactions or the rest of the database, so this page does not say
 * "your data is encrypted". If it ever mentions encryption at rest, it must name statement files
 * only, as the Privacy Policy does.
 */
const ICONS = [
  <MonitorSmartphone size={17} />,
  <Lock size={17} />,
  <Fingerprint size={17} />,
  <Database size={17} />,
  <Server size={17} />,
];

export function Security() {
  return (
    <Section id="security" tone="alt">
      <SectionHeading eyebrow={security.eyebrow} title={security.title} blurb={security.blurb} />

      <div className="grid sm:grid-cols-2 lg:grid-cols-5 gap-3">
        {security.chain.map((s, i) => (
          <Reveal key={s.title} delayMs={i * 80}>
            <div className="m-card m-card-hover p-5 h-full relative">
              {/* Connector, drawn only where a next card exists on the same row. */}
              {i < security.chain.length - 1 ? (
                <span
                  aria-hidden="true"
                  className="hidden lg:block absolute top-1/2 -right-3 w-3 h-[2px]"
                  style={{ background: '#CBD5E1' }}
                />
              ) : null}
              <span className="w-9 h-9 rounded-xl grid place-items-center mb-3" style={{ background: 'var(--m-brand-wash)', color: 'var(--m-brand)' }}>
                {ICONS[i]}
              </span>
              <p className="text-[13px] font-semibold mb-1" style={{ color: 'var(--m-ink)' }}>{s.title}</p>
              <p className="text-xs leading-relaxed" style={{ color: 'var(--m-ink-2)' }}>{s.body}</p>
            </div>
          </Reveal>
        ))}
      </div>

      <Reveal delayMs={220}>
        <p className="text-center text-sm mt-8" style={{ color: 'var(--m-ink-3)' }}>{security.footnote}</p>
        <p className="text-center text-sm mt-2">
          <Link to="/trust" className="hover:underline" style={{ color: 'var(--m-brand)' }}>
            Backups, Ask Fyn's limitations, and more &rarr;
          </Link>
        </p>
      </Reveal>

      {/* The section ends on ownership rather than on cryptography, and it is set large on
          purpose: everything above is a mechanism, this is what the mechanisms are FOR, and it is
          the one sentence worth remembering once the details have faded. */}
      <Reveal delayMs={300}>
        <p
          className="max-w-3xl mx-auto text-center mt-16 text-2xl sm:text-3xl lg:text-4xl leading-tight"
          style={{ fontFamily: "'Manrope', Inter, sans-serif", fontWeight: 800, letterSpacing: '-.025em', color: 'var(--m-ink)' }}
        >
          {security.ownership}
          <br />
          <span style={{ color: 'var(--m-brand)' }}>{security.ownershipAccent}</span>
        </p>
      </Reveal>
    </Section>
  );
}
