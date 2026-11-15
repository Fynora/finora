/**
 * The line shown after a Quick sort answer (spec docs/superpowers/specs/2026-11-15-quick-sort-answer-payoff-design.md).
 * Mirrored in mobile/src/lib/quickSortPayoff.ts.
 */

/** What the server says one answer did. amount and remembered are absent from an older server. */
export type QuickSortAnswerResult = { filed: number; amount?: number; remembered?: boolean };

/**
 * "✓ Food · 3 payments filed (₹2,400). Fynora will remember this for Swiggy." when the answer was
 * saved for the payee; otherwise, or when the server did not say, only "✓ Filed as Food." -- never a
 * promise the server did not make.
 */
export function payoffLine(result: QuickSortAnswerResult, category: string, payee: string,
                           money: (n: number) => string): string {
  if (result.remembered !== true || typeof result.amount !== 'number' || !Number.isFinite(result.amount)) {
    return `✓ Filed as ${category}.`;
  }
  const payments = result.filed === 1 ? 'payment' : 'payments';
  return `✓ ${category} · ${result.filed} ${payments} filed (${money(result.amount)}). `
    + `Fynora will remember this for ${payee}.`;
}
