import { FinoraCard, SectionHeader } from '../../design-system';
import type { CategoryChange } from './changeVsMonth';
import { fmt } from './reportFormat';

/** "+₹2,000" / "-₹1,200": the sign is part of the text, so direction never rests on colour alone. */
const signed = (n: number) => (n > 0 ? '+' : '') + fmt(n);

function detail(c: CategoryChange, priorName: string): string {
  switch (c.kind) {
    case 'new': return `New this month, nothing in ${priorName}`;
    case 'gone': return `Nothing this month, ${fmt(c.before)} in ${priorName}`;
    default: return `${fmt(c.now)} now, ${fmt(c.before)} in ${priorName}`;
  }
}

/**
 * Where the month's spending moved against the month before, by category, in rupees. Drawn only when
 * the server trusted the comparison between the two months and there is a change to show. More spent
 * reads in the danger colour and less in the success colour, but the sign says it too.
 */
export function CategoryChanges({ changes, priorName }: { changes: CategoryChange[]; priorName: string }) {
  if (changes.length === 0) return null;
  return (
    <FinoraCard>
      <SectionHeader title={`Biggest changes since ${priorName}`} size="sm" />
      <ul className="divide-y divide-border" data-testid="category-changes">
        {changes.map((c) => (
          <li key={c.category} className="flex items-center justify-between gap-3 py-2">
            <span className="min-w-0">
              <span className="block truncate text-sm text-ink">{c.category}</span>
              <span className="block truncate text-xs text-muted">{detail(c, priorName)}</span>
            </span>
            <span className={`flex-shrink-0 text-sm font-medium tabular-nums ${c.delta > 0 ? 'text-danger' : 'text-success'}`}>
              {signed(c.delta)}
            </span>
          </li>
        ))}
      </ul>
    </FinoraCard>
  );
}
