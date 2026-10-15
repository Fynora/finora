import type { ReactNode } from 'react';
import type { HeldClaudeFix } from '../types';

const FINORA_PR = 'https://github.com/Fynora/finora/pull/';
const PRIVATE_PR = 'https://github.com/Fynora/finora-statement-traces/pull/';

const NOT_TRIED: Partial<Record<NonNullable<HeldClaudeFix['reason']>, string>> = {
  DIFFERS: 'Needs a person: the blanked copy does not match',
  NOT_CHECKED: 'Needs a person: the blanked copy was not checked',
  REFUSED: 'Needs a person: the blanked copy was refused',
  CSV: 'Needs a person: the statement is a CSV',
};

function PrLink({ href, children }: { href: string; children: string }) {
  return (
    <a href={href} target="_blank" rel="noreferrer" className="text-primary underline">
      {children}
    </a>
  );
}

/** The words for one status: plain text, or text with the PR links. */
function describe(fix: HeldClaudeFix): ReactNode {
  if (fix.state === 'OFF') return 'Claude fix off';
  switch (fix.state) {
    case 'NOT_STARTED':
      if (fix.reason === 'OFF') return 'Needs a person: the Claude fix was off when this was held';
      if (fix.reason === 'CAP_REACHED') return `Needs a person: this month's ${fix.monthlyCap} Claude runs are used`;
      return (fix.reason && NOT_TRIED[fix.reason]) ?? 'Needs a person: the Claude fix could not start';
    case 'SENT':
      return `Claude is working on it (attempt ${fix.attempt} of 2)`;
    case 'PR_OPEN':
      return (
        <>
          Claude opened a fix: <PrLink href={FINORA_PR + fix.publicPr}>{`PR #${fix.publicPr}`}</PrLink> (Finora),{' '}
          <PrLink href={PRIVATE_PR + fix.privatePr}>{`#${fix.privatePr}`}</PrLink> (private)
        </>
      );
    case 'REREADING':
      return 'Fix deployed, re-reading the statement';
    case 'FIXED':
      return (
        <>
          Fixed by Claude&apos;s <PrLink href={FINORA_PR + fix.publicPr}>{`PR #${fix.publicPr}`}</PrLink>
        </>
      );
    case 'GAVE_UP':
      if (fix.reason === 'STILL_HELD') {
        return "Needs a person: Claude's fix was deployed but the statement is still held";
      }
      if (fix.reason === 'DECIDED') return 'Claude stopped: a person decided this hold';
      return `Needs a person: Claude gave up after ${fix.attempt} ${fix.attempt === 1 ? 'attempt' : 'attempts'}`;
    default:
      return null;
  }
}

/**
 * What the automatic Claude fix did for this held statement (docs/superpowers/specs/
 * 2026-10-15-claude-fix-job-design.md): one line, with links to the two PRs it opened. The private
 * repository's link opens only for people with access to it. Nothing while the switch is on and no
 * decision is recorded yet, or against an older backend that does not send it.
 */
export default function ClaudeFixLine({ fix }: { fix: HeldClaudeFix | null | undefined }) {
  if (!fix) return null;
  const text = describe(fix);
  if (text === null) return null;
  return (
    <div className="rounded-lg border border-border p-3" data-testid="claude-fix-line">
      <p className="text-xs text-muted font-medium">Claude fix</p>
      <p className="text-sm text-ink mt-1">{text}</p>
    </div>
  );
}
