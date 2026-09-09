import { useState } from 'react';
import { Check, Minus } from 'lucide-react';
import { Reveal, Section, SectionHeading } from './primitives';
import { AVAILABILITY_LABEL, AVAILABILITY_STYLE, COMPARISON, PRICING_CARDS, type Plan } from './plans';
import { MagneticLink } from './MagneticLink';

/**
 * Pricing, with a paid tier advertised but not pretended into existence.
 *
 * The rule this section is built on: it is fine to advertise a future paid plan, and not fine to
 * imply it is already available. So Plus and Premium carry a status badge WHERE THE PRICE WOULD
 * GO, rather than a number. That placement is deliberate -- a price with a small
 * "coming soon" tag beside it still reads as a price, and the earlier version of this page
 * displayed ₹149 and ₹249 that nobody had actually decided on. Inventing a number you later have
 * to change is its own kind of dishonesty, and the first people to notice are the ones who
 * screenshotted it.
 *
 * Plus and Premium are now real, purchasable plans (subscription billing V1/V2, PRs #1008/#1016)
 * -- checkout itself happens inside the app's Billing Portal (frontend/src/pages/Billing.tsx),
 * not on this public page. This page's job stays the same as before: state what a plan costs and
 * whether it can be bought, accurately, and get a visitor into the app to actually buy it.
 *
 * THERE IS NO WAITLIST CTA, and that is a decision rather than an omission. This carried a
 * "Join the waitlist" mailto, which did deliver -- but nothing STORES the interest, nobody is
 * queued, and no notification fires when Premium launches. A button that cannot keep the promise
 * in its own label is the same failure as the newsletter box this page used to carry, which
 * thanked you and discarded the address. Unavailable tiers state their status and stop there.
 * Add the CTA the day an endpoint exists to receive it.
 *
 * The cards lead with what a plan is FOR rather than what it contains. Feature-by-feature belongs
 * in the comparison table below, where someone deliberately comparing can find it.
 *
 * The Monthly/Yearly toggle below is new, but the invariant it must respect isn't: every rupee
 * figure it can ever show is still exactly `plan.price` or a number pulled straight out of
 * `plan.secondaryPriceNote` -- the same two sources landing-claims.test.tsx already audits. No
 * new price is invented here; the toggle only decides which of those two already-real numbers is
 * shown first.
 */

type Cycle = 'monthly' | 'yearly';

/** Pulls the leading ₹ amount out of a price string ("₹3,500/year" -> 3500). Returns null rather
 *  than throwing on a shape it doesn't recognize, since a null just falls back to the plain
 *  monthly price below -- there's no scenario where a parse miss should break the page. */
function parseRupees(text: string | null | undefined): number | null {
  const match = text?.match(/₹([\d,]+)/);
  return match ? Number(match[1].replace(/,/g, '')) : null;
}

/** What to show as the primary price/cadence and the smaller note beneath it, for the selected
 *  billing cycle. Free (no secondaryPriceNote) is unaffected by the toggle -- it only has one
 *  price to show either way. */
function priceForCycle(plan: Plan, cycle: Cycle): { amount: string; cadence: string; note?: string } {
  const monthly = { amount: plan.price ?? '', cadence: plan.cadence ?? '', note: plan.secondaryPriceNote };
  if (cycle === 'monthly' || !plan.secondaryPriceNote) return monthly;

  const yearlyAmount = parseRupees(plan.secondaryPriceNote);
  if (yearlyAmount == null) return monthly;

  return {
    amount: `₹${yearlyAmount.toLocaleString('en-IN')}`,
    cadence: '/year',
    note: `or ${plan.price}${plan.cadence ?? ''}`,
  };
}

/** Percentage saved by paying yearly instead of 12x the monthly price -- computed from the same
 *  two real numbers above, never a hardcoded figure. Plus and Premium save different amounts
 *  (27% vs 17%), so this is per-plan rather than one banner claim covering both. */
function yearlySavingsPct(plan: Plan): number | null {
  if (plan.cadence !== '/month' || !plan.secondaryPriceNote) return null;
  const monthly = parseRupees(plan.price);
  const yearly = parseRupees(plan.secondaryPriceNote);
  if (!monthly || !yearly) return null;
  const pct = Math.round((1 - yearly / (monthly * 12)) * 100);
  return pct > 0 ? pct : null;
}

const CYCLES: { code: Cycle; label: string }[] = [
  { code: 'monthly', label: 'Monthly' },
  { code: 'yearly', label: 'Yearly' },
];

export function Pricing() {
  const [cycle, setCycle] = useState<Cycle>('monthly');

  return (
    <Section id="pricing" tone="alt">
      <SectionHeading eyebrow="Simple pricing" title="Simple pricing. No hidden costs." />

      <Reveal className="flex justify-center mb-8">
        <div
          role="group"
          aria-label="Billing cycle"
          className="inline-flex items-center gap-1 rounded-lg p-1"
          style={{ background: '#F1F5F9', border: '1px solid var(--m-line)' }}
        >
          {CYCLES.map((c) => (
            <button
              key={c.code}
              type="button"
              onClick={() => setCycle(c.code)}
              aria-pressed={cycle === c.code}
              className="text-xs font-semibold px-3.5 py-1.5 rounded-md transition-colors"
              style={cycle === c.code
                ? { background: '#fff', color: 'var(--m-ink)', boxShadow: '0 1px 2px rgba(15,23,42,.08)' }
                : { color: 'var(--m-ink-3)' }}
            >
              {c.label}
            </button>
          ))}
        </div>
      </Reveal>

      <div className="grid md:grid-cols-3 gap-5 max-w-5xl mx-auto">
        {PRICING_CARDS.map((plan, i) => {
          const isPopular = plan.id === 'premium' && plan.availability === 'available';
          const price = priceForCycle(plan, cycle);
          const savings = cycle === 'yearly' ? yearlySavingsPct(plan) : null;
          return (
            <Reveal key={plan.name} delayMs={i * 80}>
              <div
                className={`m-card m-card-hover p-6 h-full flex flex-col relative ${plan.availability === 'available' ? 'ring-2 ring-[var(--m-brand)]' : ''}`}
              >
                {isPopular && (
                  <span
                    className="absolute -top-3 right-5 text-[9px] uppercase tracking-wide font-semibold px-2.5 py-1 rounded-full"
                    style={{ background: 'var(--m-brand)', color: '#fff' }}
                  >
                    Most popular
                  </span>
                )}
                <div className="flex items-center justify-between mb-2">
                  <p className="text-sm font-semibold" style={{ color: 'var(--m-ink)' }}>{plan.name}</p>
                  <span className="text-[9px] uppercase tracking-wide font-semibold px-2 py-1 rounded-full" style={AVAILABILITY_STYLE[plan.availability]}>
                    {AVAILABILITY_LABEL[plan.availability]}
                  </span>
                </div>

                {/* A status where the price goes, for anything that cannot be bought. */}
                {plan.price ? (
                  <>
                    <p
                      className="text-3xl font-extrabold mb-1"
                      style={{ fontFamily: "'Manrope', Inter, sans-serif", color: 'var(--m-ink)' }}
                    >
                      {price.amount}
                      <span className="text-sm font-medium" style={{ color: 'var(--m-ink-3)' }}>{price.cadence}</span>
                    </p>
                    <p className="text-xs mb-1 flex items-center gap-1.5" style={{ color: 'var(--m-ink-3)' }}>
                      {price.note}
                      {savings && (
                        <span
                          className="text-[10px] font-semibold px-1.5 py-0.5 rounded"
                          style={{ background: '#DCFCE7', color: '#166534' }}
                        >
                          Save {savings}%
                        </span>
                      )}
                    </p>
                    {plan.priceExcludesGst && (
                      <p className="text-xs mb-1" style={{ color: 'var(--m-ink-3)' }}>+ 18% GST</p>
                    )}
                  </>
                ) : (
                  <p className="text-2xl font-extrabold mb-1" style={{ fontFamily: "'Manrope', Inter, sans-serif", color: 'var(--m-ink-3)' }}>
                    Pricing TBD
                  </p>
                )}

                <p className="text-[15px] font-medium mb-5" style={{ color: 'var(--m-ink)' }}>{plan.promise}</p>

                <ul className="space-y-2 mb-6 flex-1">
                  {plan.features.map((f) => (
                    <li key={f} className="flex items-start gap-2 text-sm" style={{ color: 'var(--m-ink-2)' }}>
                      <Check size={14} className={plan.availability === 'available' ? 'text-[#16A34A] shrink-0 mt-1' : 'text-slate-300 shrink-0 mt-1'} />
                      {f}
                    </li>
                  ))}
                </ul>

                {plan.availability === 'available' ? (
                  <MagneticLink to="/auth" className="m-btn m-btn-primary w-full">
                    {plan.id === 'free' ? 'Start free' : 'Get started'}
                  </MagneticLink>
                ) : (
                  // A statement, not a control. See the note at the top of this file.
                  <p className="text-center text-sm py-3" style={{ color: 'var(--m-ink-3)' }}>
                    {plan.availability === 'coming-soon' ? 'Launching soon.' : 'Not yet scheduled.'}
                  </p>
                )}
              </div>
            </Reveal>
          );
        })}
      </div>

      {/* ---- Comparison ---- */}
      <Reveal delayMs={160}>
        <div className="max-w-2xl mx-auto mt-14 m-card overflow-hidden">
          <table className="w-full text-sm">
            <caption className="sr-only">Feature comparison between the Free, Plus and Premium plans</caption>
            <thead>
              <tr style={{ background: '#F8FAFC' }}>
                <th scope="col" className="text-left font-semibold px-5 py-3" style={{ color: 'var(--m-ink)' }}>Feature</th>
                <th scope="col" className="px-4 py-3 font-semibold w-24" style={{ color: 'var(--m-ink)' }}>Free</th>
                <th scope="col" className="px-4 py-3 font-semibold w-28" style={{ color: 'var(--m-ink)' }}>
                  Plus
                </th>
                <th scope="col" className="px-4 py-3 font-semibold w-28" style={{ color: 'var(--m-ink)' }}>
                  Premium
                </th>
              </tr>
            </thead>
            <tbody>
              {COMPARISON.map(({ label, free, plus, premium }) => (
                <tr key={label} className="border-t" style={{ borderColor: 'var(--m-line)' }}>
                  <th scope="row" className="text-left font-normal px-5 py-3" style={{ color: 'var(--m-ink-2)' }}>{label}</th>
                  <td className="text-center px-4 py-3">
                    {free
                      ? <Check size={16} className="inline text-[#16A34A]" aria-label="Included" />
                      : <Minus size={16} className="inline text-slate-300" aria-label="Not included" />}
                  </td>
                  <td className="text-center px-4 py-3">
                    {plus
                      ? <Check size={16} className="inline text-[var(--m-brand)]" aria-label="Included" />
                      : <Minus size={16} className="inline text-slate-300" aria-label="Not included" />}
                  </td>
                  <td className="text-center px-4 py-3">
                    {premium
                      ? <Check size={16} className="inline text-[var(--m-brand)]" aria-label="Included" />
                      : <Minus size={16} className="inline text-slate-300" aria-label="Not included" />}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </Reveal>

      {/* ---- Why a subscription at all ---- */}
      <Reveal delayMs={220}>
        <div className="max-w-2xl mx-auto mt-8 rounded-2xl p-6 sm:p-7" style={{ background: '#fff', border: '1px solid var(--m-line)' }}>
          <p className="m-eyebrow mb-2">Why a subscription?</p>
          <p className="text-[15px] leading-relaxed" style={{ color: 'var(--m-ink-2)' }}>
            Everyone should be able to understand their own money, so the core experience stays
            free. Subscriptions are how the rest gets funded — new features, security work, and the
            infrastructure behind them.
          </p>
          <p className="text-[15px] leading-relaxed mt-3" style={{ color: 'var(--m-ink-2)' }}>
            It is also the only funding model that keeps the incentives straight. A product paid
            for by advertisers or lenders eventually works for them.{' '}
            <span style={{ color: 'var(--m-ink)', fontWeight: 600 }}>
              One paid for by its users only has to be worth paying for.
            </span>
          </p>
        </div>
      </Reveal>
    </Section>
  );
}
