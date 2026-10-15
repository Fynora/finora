export interface CategoryRow {
  category: string;
  amount: number;
  /** Percent of the rows' own total, 0-100. Never of the report's Expense figure: the report builds
   *  Expense from a narrower set than its categories (an investment outflow is a category row but
   *  not expense), so dividing by Expense can push a share past 100%. */
  share: number;
}

export interface CategoryTotals {
  rows: CategoryRow[];
  /** The sum of the rows -- the donut's total, so the donut, the list and the shares all agree. */
  total: number;
}

/** The report's categories as rows with their share of the rows' own sum, largest first. A row that
 *  is not a positive amount is dropped: it has no share to show and would draw a zero-width slice. */
export function categoryRows(categories: { category: string; amount: number }[]): CategoryTotals {
  const positive = categories.filter((c) => Number.isFinite(c.amount) && c.amount > 0);
  const total = positive.reduce((sum, c) => sum + c.amount, 0);
  const rows = positive
    .map((c) => ({ category: c.category, amount: c.amount, share: total > 0 ? (c.amount / total) * 100 : 0 }))
    .sort((a, b) => b.amount - a.amount);
  return { rows, total };
}

export interface DonutSlice {
  /** The category name, or null for the grouped "Other" slice. */
  category: string | null;
  amount: number;
  share: number;
}

/** The donut's slices: the biggest `maxSlices - 1` categories and everything smaller grouped into one
 *  "Other" slice, so the ring stays legible. The slices still add up to the rows' total. */
export function donutSlices(rows: CategoryRow[], maxSlices = 6): DonutSlice[] {
  if (rows.length <= maxSlices) return rows.map((r) => ({ category: r.category, amount: r.amount, share: r.share }));
  const head = rows.slice(0, maxSlices - 1);
  const rest = rows.slice(maxSlices - 1);
  const amount = rest.reduce((sum, r) => sum + r.amount, 0);
  const share = rest.reduce((sum, r) => sum + r.share, 0);
  return [...head.map((r) => ({ category: r.category, amount: r.amount, share: r.share })), { category: null, amount, share }];
}

/** "▲ 25%" for a category's change against the user's recent average, or null when there is nothing
 *  to compare with (a new category) or the server sent no figure. A move that rounds to 0% reads as
 *  in line rather than "▲ 0%". Spending up is the bad direction. */
export function changeVsAverage(pctChange: number | null | undefined):
  { text: string; tone: 'up' | 'down' | 'flat' } | null {
  if (pctChange == null || !Number.isFinite(pctChange)) return null;
  const rounded = Math.round(Math.abs(pctChange));
  if (rounded === 0) return { text: 'In line', tone: 'flat' };
  return pctChange > 0
    ? { text: `▲ ${rounded}%`, tone: 'up' }
    : { text: `▼ ${rounded}%`, tone: 'down' };
}
