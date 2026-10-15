import { FinoraCard, SectionHeader } from '../../design-system';
import type { Observation } from './observationText';

/**
 * What is worth noticing about the month: one lead sentence in larger type and up to two more
 * beneath it, as an editorial list rather than a grid of cards. Drawn only when there is something
 * to say; an empty section would be filler.
 */
export function Observations({ items }: { items: Observation[] }) {
  if (items.length === 0) return null;
  const [lead, ...rest] = items;
  return (
    <FinoraCard>
      <SectionHeader title="Worth noticing" size="sm" />
      <p data-testid="observation-lead" className="text-base font-medium text-ink">{lead.text}</p>
      {rest.length > 0 && (
        <ul className="mt-3 divide-y divide-border border-t border-border" data-testid="observation-rest">
          {rest.map((o) => <li key={o.key} className="py-2 text-sm text-muted">{o.text}</li>)}
        </ul>
      )}
    </FinoraCard>
  );
}
