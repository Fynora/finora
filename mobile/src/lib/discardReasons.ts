/**
 * The optional one-tap reasons a customer may give when discarding a staged statement (Sid,
 * 2026-11-17). Skipping is allowed. Mirrors the backend's `DiscardReason` (the server refuses any
 * other value) and the web app's own copy of this list.
 */
export type DiscardReason = 'WRONG_FILE' | 'TOO_MANY_MONTHS' | 'LOOKS_WRONG' | 'LATER' | 'OTHER';

export const DISCARD_REASONS: { value: DiscardReason; label: string }[] = [
  { value: 'WRONG_FILE', label: 'Wrong file' },
  { value: 'TOO_MANY_MONTHS', label: 'Covers too many months' },
  { value: 'LOOKS_WRONG', label: 'Something looks wrong' },
  { value: 'LATER', label: 'I’ll upload it later' },
  { value: 'OTHER', label: 'Other' },
];
