import { useMemo } from 'react';
import { Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { ArrowDownLeft, ChevronRight } from 'lucide-react';
import { peopleApi, type PersonBalance } from '../../api/endpoints';
import { FinoraCard, SectionHeader } from '../../design-system';
import { expectedSharesIn, rupees, withPeople } from '../../lib/people';

/** The person's page. The key is the server's payee key ("vpa:..."), so it is encoded in the path. */
export function personPath(personKey: string): string {
  return `/app/people/${encodeURIComponent(personKey)}`;
}

/**
 * "With people" (spec §7): one balance per person, both directions, each a link to their page; and
 * the shares expected in from people with an every-month rule, marked late once past their usual
 * day. Family is never listed. Absent with nothing to show, or on a server without it.
 */
export function WithPeopleCard() {
  const peopleQ = useQuery({ queryKey: ['people'], queryFn: () => peopleApi.list(), retry: false });
  const suggestionsQ = useQuery({ queryKey: ['people', 'suggestions'], queryFn: () => peopleApi.suggestions(), retry: false });
  const { owedToYou, youOwe } = useMemo(() => withPeople(peopleQ.data ?? []), [peopleQ.data]);
  const expected = useMemo(() => expectedSharesIn(suggestionsQ.data?.expected ?? []), [suggestionsQ.data]);

  if (owedToYou.length === 0 && youOwe.length === 0 && expected.length === 0) return null;

  function side(title: string, people: PersonBalance[], theyOwe: boolean) {
    if (people.length === 0) return null;
    return (
      <div>
        <p className="text-2xs uppercase tracking-wide text-muted mb-1">{title}</p>
        <ul>
          {people.map((p) => {
            const amount = rupees(Math.abs(p.owedToUser));
            return (
              <li key={p.personKey}>
                <Link
                  to={personPath(p.personKey)}
                  aria-label={theyOwe ? `${p.label} owes you ${amount}` : `You owe ${p.label} ${amount}`}
                  className="flex items-center gap-3 text-sm border-b border-dashed py-2 hover:bg-black/[0.02]"
                >
                  <span className="flex-1 min-w-0 truncate text-ink">{p.label}</span>
                  <span className={`font-medium ${theyOwe ? 'text-success' : 'text-danger'}`}>{amount}</span>
                  <ChevronRight size={14} className="text-muted" aria-hidden />
                </Link>
              </li>
            );
          })}
        </ul>
      </div>
    );
  }

  return (
    <FinoraCard>
      <SectionHeader title="With people" />
      <div className="space-y-4">
        {side('Owed to you', owedToYou, true)}
        {side('You owe', youOwe, false)}
        {expected.length > 0 && (
          <div>
            <p className="text-2xs uppercase tracking-wide text-muted mb-1">Expected in</p>
            <ul>
              {expected.map((e) => (
                <li key={e.key} className="flex items-center gap-3 text-sm border-b border-dashed py-2">
                  <ArrowDownLeft size={14} className={e.late ? 'text-warning' : 'text-primary'} aria-hidden />
                  <span className="flex-1 min-w-0">
                    <span className="block text-ink truncate">{e.title}</span>
                    <span className={`block text-2xs ${e.late ? 'text-warning' : 'text-muted'}`}>{e.when}</span>
                  </span>
                  <span className="font-medium text-success">{`+${rupees(e.amount)}`}</span>
                </li>
              ))}
            </ul>
          </div>
        )}
      </div>
    </FinoraCard>
  );
}
