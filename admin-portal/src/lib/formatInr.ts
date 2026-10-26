/**
 * A rupee amount for display: "−₹1,00,396.53", not "₹-1,00,396.53". Writing `₹${n.toLocaleString()}`
 * puts the minus after the rupee sign. Indian digit grouping; paise as two digits when there are any.
 */
export function formatInr(n: number): string {
  const abs = Math.abs(n);
  const fractional = Math.round(abs * 100) % 100 !== 0;
  const digits = abs.toLocaleString('en-IN', {
    minimumFractionDigits: fractional ? 2 : 0,
    maximumFractionDigits: 2,
  });
  return `${n < 0 ? '−' : ''}₹${digits}`;
}
