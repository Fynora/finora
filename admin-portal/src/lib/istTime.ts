/**
 * India Standard Time helpers for the push campaign screen. IST is a fixed UTC+05:30 with no
 * daylight saving, so a plain offset is exact -- there is no zone database involved. The browser's
 * own time zone is deliberately never used: a campaign is scheduled in IST whatever the admin's
 * laptop says, and the server's window (07:00-21:59) is an IST rule.
 */
const IST_OFFSET_MINUTES = 5 * 60 + 30;
const IST_OFFSET_MS = IST_OFFSET_MINUTES * 60_000;

/** The first and last minute a scheduled push may go out, inclusive (the server's window). */
export const WINDOW_START = '07:00';
export const WINDOW_END = '21:59';

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

function pad(n: number): string {
  return String(n).padStart(2, '0');
}

/** The instant as IST wall-clock parts. */
function istParts(iso: string) {
  const shifted = new Date(new Date(iso).getTime() + IST_OFFSET_MS);
  return {
    year: shifted.getUTCFullYear(),
    month: shifted.getUTCMonth(),
    day: shifted.getUTCDate(),
    hour: shifted.getUTCHours(),
    minute: shifted.getUTCMinutes(),
  };
}

/** A `datetime-local` value ("2026-10-06T19:30"), read as IST, to the instant the API takes. */
export function istInputToIso(local: string): string {
  return new Date(`${local}:00+05:30`).toISOString();
}

/** The reverse: an instant to a `datetime-local` value showing its IST wall-clock time. */
export function isoToIstInput(iso: string): string {
  const p = istParts(iso);
  return `${p.year}-${pad(p.month + 1)}-${pad(p.day)}T${pad(p.hour)}:${pad(p.minute)}`;
}

/** "06 Oct 2026, 19:30 IST", or a dash when there is no instant. */
export function formatIst(iso: string | null | undefined): string {
  if (!iso) return '—';
  const p = istParts(iso);
  return `${pad(p.day)} ${MONTHS[p.month]} ${p.year}, ${pad(p.hour)}:${pad(p.minute)} IST`;
}

/** An IST calendar date from the API ("2026-10-06") for display. */
export function formatIstDate(date: string | null | undefined): string {
  if (!date) return '—';
  const [y, m, d] = date.split('-').map(Number);
  return `${pad(d)} ${MONTHS[m - 1]} ${y}`;
}

/** The API sends a time of day as "07:30:00"; an `<input type="time">` wants "07:30". */
export function toTimeInput(time: string | null | undefined): string {
  return time ? time.slice(0, 5) : '';
}

/** Whether "HH:mm" is inside the server's 07:00-21:59 window. Zero-padded, so string order is time order. */
export function isInsideWindow(hhmm: string): boolean {
  return hhmm >= WINDOW_START && hhmm <= WINDOW_END;
}

/** Whether the IST wall-clock part of a `datetime-local` value is inside the window. */
export function isInputInsideWindow(local: string): boolean {
  return isInsideWindow(local.slice(11, 16));
}

/** Today's date in IST as "YYYY-MM-DD", for `min` on a date input. */
export function todayIst(now: Date = new Date()): string {
  const p = istParts(now.toISOString());
  return `${p.year}-${pad(p.month + 1)}-${pad(p.day)}`;
}

/**
 * Rough minutes to deliver `people` pushes at the dispatcher's configured pace (50 per pass, every
 * 30 seconds, so about 100 a minute). Read from the code, not measured against real FCM -- the
 * screen says so wherever it shows this.
 */
export const PEOPLE_PER_MINUTE = 100;
export function estimateMinutes(people: number): number {
  return Math.max(1, Math.ceil(people / PEOPLE_PER_MINUTE));
}
