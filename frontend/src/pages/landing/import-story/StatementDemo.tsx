import { useEffect, useRef, useState } from 'react';
import { Check, RotateCcw } from 'lucide-react';

/**
 * The same four example transactions used elsewhere on this page (ImportSection's old
 * StatementCard, IntelligencePanel) -- naming a real merchant a sample statement line item is
 * descriptive, not an endorsement claim. Category names match what the real product's default
 * category set actually calls these (Shopping, Food & Dining -> shortened to "Food" for space
 * here as the old components already did, Income, Subscriptions).
 *
 * Amounts carry the ₹ symbol (−₹2,450.00, not −2,450.00) to match how sample amounts are shown
 * everywhere else on this same page -- DashboardMock's "RECENT TRANSACTIONS" list a few sections
 * down uses the identical "−₹480" / "+₹1,24,500" shape, including this exact salary figure. The
 * old StatementCard/IntelligencePanel this replaces omitted the symbol; that was never caught
 * because that scene was aria-hidden decoration, not real, readable content.
 */
const ROWS = [
  { label: 'Amazon Pay', amount: '−₹2,450.00', category: 'Shopping', tone: 'neutral' as const },
  { label: 'Swiggy Bangalore', amount: '−₹860.00', category: 'Food', tone: 'neutral' as const },
  { label: 'Salary credit', amount: '+₹1,24,500.00', category: 'Income', tone: 'success' as const },
  { label: 'Netflix subscription', amount: '−₹649.00', category: 'Subscriptions', tone: 'neutral' as const },
];

const SCAN_MS = 900;

type Stage = 'idle' | 'scanning' | 'result';

/**
 * ImportSection's real demo: a sample statement the visitor clicks to run, not a scripted
 * animation that plays itself. Replaces the old ImportRevealSequence (StatementCard +
 * IntelligencePanel), which auto-played on scroll and never showed real-looking categorized
 * output at any size worth reading.
 *
 * No upload, no backend call -- ROWS above is fixed sample data, always the same four
 * transactions. Because this is a real interactive control with real result content (not
 * decoration), it is NOT aria-hidden the way the old scene was: the button has a real accessible
 * name and the result list is real DOM content, present for assistive tech once revealed.
 *
 * Two things a manual review caught that the tests alone didn't:
 *
 * 1. Focus and announcement. Each stage swap unmounts the previous stage's button (idle's "See it
 *    work", result's "Watch again"), which drops focus to <body> with nothing telling a screen
 *    reader anything happened -- confirmed by driving this in a real browser, not assumed. Fixed
 *    two ways: an aria-live region announces the result, and focus is moved onto "Watch again"
 *    once it exists, so a keyboard user's tab position isn't lost to the top of the page. Nothing
 *    to focus during the brief "scanning" stage (no control exists then), which is fine -- it's
 *    ~900ms and skipped entirely under prefers-reduced-motion.
 * 2. Layout shift. The result stage (4 rows) is taller than idle/scanning (a short placeholder),
 *    and this card sits in a `items-center` grid row next to the section's real copy -- so
 *    letting the card's height change moved that copy up and down by ~19px on every click
 *    (measured in a real browser). minHeight below is the measured result-stage height (396px at
 *    this card's width), so the shorter stages no longer change the row's height.
 *
 * prefers-reduced-motion skips the scan sweep and jumps straight to the result on click -- the
 * click itself is still required, since starting a demo mid-scroll without one would be a bigger
 * motion surprise than the sweep it's replacing.
 */
export function StatementDemo() {
  const [stage, setStage] = useState<Stage>('idle');
  const watchAgainRef = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    if (stage === 'result') watchAgainRef.current?.focus();
  }, [stage]);

  function run() {
    const reduced = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches;
    if (reduced) {
      setStage('result');
      return;
    }
    setStage('scanning');
    setTimeout(() => setStage('result'), SCAN_MS);
  }

  return (
    <div
      className="relative w-[300px] sm:w-[340px] rounded-xl bg-white p-5 mx-auto flex flex-col justify-center"
      style={{ border: '1px solid #E6EAF2', boxShadow: '0 24px 48px -20px rgba(15,23,42,.3)', minHeight: 396 }}
    >
      <p className="text-[10px] uppercase tracking-wide text-slate-400 mb-1">Sample statement</p>
      <p className="text-sm font-semibold text-slate-900 mb-3.5">Aug 2026 · 4-page PDF</p>

      {stage === 'idle' && (
        <>
          <div className="h-20 rounded-lg border-[1.5px] border-dashed border-slate-300 flex items-center justify-center text-xs text-slate-500">
            Password-protected, multiple accounts
          </div>
          <button type="button" onClick={run} className="m-btn m-btn-primary w-full mt-3.5 text-sm">
            See it work
          </button>
        </>
      )}

      {stage === 'scanning' && (
        <div className="relative h-20 rounded-lg overflow-hidden flex items-center justify-center text-xs font-semibold" style={{ border: '1.5px solid var(--m-success)', color: 'var(--m-success)' }}>
          Reading transactions…
          <div
            aria-hidden="true"
            className="absolute left-0 right-0 h-6 pointer-events-none"
            style={{
              background: 'linear-gradient(180deg, rgba(22,163,74,0) 0%, rgba(22,163,74,.16) 50%, rgba(22,163,74,0) 100%)',
              borderTop: '1px solid rgba(22,163,74,.55)',
              animation: `statement-scan ${SCAN_MS}ms ease-in-out forwards`,
            }}
          />
        </div>
      )}

      {stage === 'result' && (
        <>
          <div className="flex items-center justify-between mb-2.5">
            <p className="text-xs font-semibold text-slate-900" aria-live="polite">
              {ROWS.length} transactions found
            </p>
            <span className="flex items-center gap-1 text-[10px] font-semibold" style={{ color: 'var(--m-success)' }}>
              <Check size={11} aria-hidden="true" /> Categorized
            </span>
          </div>
          <div className="flex flex-col gap-2">
            {ROWS.map((row) => (
              <div key={row.label} className="flex items-center justify-between border-b border-slate-100 pb-2 last:border-b-0 last:pb-0">
                <div>
                  <p className="text-[11px] font-medium text-slate-900">{row.label}</p>
                  <span
                    className="text-[9px] font-medium px-2 py-0.5 rounded-full inline-block mt-0.5"
                    style={{ background: 'var(--m-brand-wash)', color: 'var(--m-brand-deep)' }}
                  >
                    {row.category}
                  </span>
                </div>
                <span className="text-[11px] font-semibold" style={row.tone === 'success' ? { color: 'var(--m-success)' } : { color: '#0f172a' }}>
                  {row.amount}
                </span>
              </div>
            ))}
          </div>
          <button
            ref={watchAgainRef}
            type="button"
            onClick={run}
            className="m-btn m-btn-ghost w-full mt-3.5 text-sm flex items-center justify-center gap-1.5"
          >
            <RotateCcw size={13} aria-hidden="true" /> Watch again
          </button>
        </>
      )}
    </div>
  );
}
