/**
 * The original-currency amount a statement printed beside the rupee amount of an international
 * transaction ("USD 12.50"), or null when it printed none -- which includes the GST and FX-markup
 * rows of an international table. Both halves must be present: the backend stores them together
 * or not at all (V222's CHECK constraint), so one without the other is not something to render.
 */
export function formatForeignAmount(
  currency: string | null | undefined,
  amount: number | null | undefined,
): string | null {
  if (!currency || amount == null) return null;
  return `${currency} ${amount.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
}
