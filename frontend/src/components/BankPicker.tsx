import { useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { banksApi } from '../api/endpoints';
import type { BankInfo } from '../types';
import { OTHER_BANK_ID, bankDisplayName } from '../lib/importBank';

/**
 * A searchable bank field: a search box that narrows a select of every bank `GET /banks` returns,
 * with "Other bank" always last. Used by the import review's new-account form and by Edit account.
 *
 * The value is a bank id, `OTHER` for "Other bank", or '' for nothing chosen yet. "Other bank" is an
 * explicit answer, which is why it is a real option and not the empty value: the import review
 * holds the import while the value is '' and lets it through once it is `OTHER`.
 */
export function BankPicker({
  id,
  value,
  onChange,
  includeBanks = [],
  disabled = false,
  describedBy,
}: {
  id: string;
  value: string;
  onChange: (bankId: string, bank: BankInfo | null) => void;
  /** Banks to offer even before, or without, the list loading -- e.g. the bank the statement named,
   *  so a pre-filled value always has its option. */
  includeBanks?: BankInfo[];
  disabled?: boolean;
  describedBy?: string;
}) {
  // One shared cache entry: the review renders one picker per account section.
  const banksQ = useQuery({ queryKey: ['banks'], queryFn: () => banksApi.list(), staleTime: Infinity, retry: false });
  const [search, setSearch] = useState('');

  const banks = useMemo(() => {
    const byId = new Map<string, BankInfo>();
    for (const b of [...(banksQ.data ?? []), ...includeBanks]) {
      if (b?.id && b.id !== OTHER_BANK_ID && !byId.has(b.id)) byId.set(b.id, b);
    }
    return [...byId.values()].sort((a, b) => bankDisplayName(a).localeCompare(bankDisplayName(b)));
  }, [banksQ.data, includeBanks]);

  const q = search.trim().toLowerCase();
  // The chosen bank stays listed while a search hides it, so the select never shows a value it has
  // no option for.
  const shown = banks.filter((b) =>
    b.id === value || !q
    || bankDisplayName(b).toLowerCase().includes(q)
    || b.shortName.toLowerCase().includes(q));

  return (
    <div>
      <input
        type="search"
        aria-label="Search banks"
        value={search}
        onChange={(e) => setSearch(e.target.value)}
        placeholder="Search banks"
        disabled={disabled}
        className="bg-card text-ink border border-border rounded-lg px-3 py-2 text-sm w-full mb-1 disabled:bg-bg disabled:text-muted"
      />
      <select
        id={id}
        value={value}
        disabled={disabled}
        aria-describedby={describedBy}
        onChange={(e) => {
          const next = e.target.value;
          onChange(next, banks.find((b) => b.id === next) ?? null);
        }}
        className="bg-card text-ink border border-border rounded-lg px-3 py-2 text-sm w-full disabled:bg-bg disabled:text-muted"
      >
        {value === '' && <option value="" disabled>Choose a bank</option>}
        {shown.map((b) => <option key={b.id} value={b.id}>{bankDisplayName(b)}</option>)}
        <option value={OTHER_BANK_ID}>Other bank</option>
      </select>
    </div>
  );
}
