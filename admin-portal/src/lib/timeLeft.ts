/**
 * Time left on a hold's 48-hour promise, as "6 h left", "45 min left" or "3 h past the promise".
 * Exactly at the promise is not yet past -- the backend's `ImportJob.isHoldOverdue` rule. Whole
 * units, rounded down; past 48 hours late it counts days. An unreadable time says so rather than
 * showing a wrong one.
 */
export function timeLeftLabel(promiseAt: string, now: number = Date.now()): { text: string; past: boolean } {
  const promise = Date.parse(promiseAt);
  if (Number.isNaN(promise)) return { text: 'Time unknown', past: false };
  const past = now > promise;
  const ms = Math.abs(promise - now);
  const hours = Math.floor(ms / 3_600_000);
  let amount: string;
  if (hours >= 48) {
    const days = Math.floor(hours / 24);
    amount = `${days} days`;
  } else if (hours >= 1) {
    amount = `${hours} h`;
  } else {
    amount = `${Math.floor(ms / 60_000)} min`;
  }
  return { text: past ? `${amount} past the promise` : `${amount} left`, past };
}
