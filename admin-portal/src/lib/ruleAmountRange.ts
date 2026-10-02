/**
 * A category rule's optional amount bounds (backend V248), as admins read them: "₹8,000 – ₹12,000",
 * "at least ₹8,000", "at most ₹12,000", or null when the rule has none. Rules made by the
 * recurring-payment question always carry both.
 */
export function ruleAmountRange(min: number | null | undefined, max: number | null | undefined): string | null {
  const hasMin = min !== null && min !== undefined;
  const hasMax = max !== null && max !== undefined;
  if (hasMin && hasMax) return `${rupees(min)} – ${rupees(max)}`;
  if (hasMin) return `at least ${rupees(min)}`;
  if (hasMax) return `at most ${rupees(max)}`;
  return null;
}

function rupees(amount: number): string {
  const paise = Math.round(amount * 100) % 100 !== 0;
  return `₹${amount.toLocaleString('en-IN', {
    minimumFractionDigits: paise ? 2 : 0,
    maximumFractionDigits: 2,
  })}`;
}
