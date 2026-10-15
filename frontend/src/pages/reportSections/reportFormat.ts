/** YYYY-MM-DD of a YYYY-MM month's last day. */
export function lastDayOf(month: string): string {
  const [y, m] = month.split('-').map(Number);
  return `${month}-${String(new Date(y, m, 0).getDate()).padStart(2, '0')}`;
}

export function fmt(n: number): string {
  // Negative amounts (e.g. a month where spend exceeded income) must render as "-₹500",
  // not "₹-500" -- string concatenation put the currency symbol before the sign.
  return (n < 0 ? '-₹' : '₹') + Math.round(Math.abs(n)).toLocaleString('en-IN');
}

// "2026-08" -> "August 2026", for naming a month inside a sentence. Explicit y/m/1 construction,
// not `new Date(monthStr)` -- a bare "YYYY-MM" parses as UTC midnight, which renders as the prior
// month in any timezone behind UTC. Long form rather than a short "Jul 26", which reads fine on a
// chart axis but is ambiguous with a day-of-month inside prose.
export function monthLabelLong(monthStr: string): string {
  const [y, m] = monthStr.split('-').map(Number);
  return new Date(y, m - 1, 1).toLocaleDateString('en-US', { month: 'long', year: 'numeric' });
}
