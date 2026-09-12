// Shared by IdentifyStep, PasswordStep, and RegisterStep -- all three need to know what a
// well-formed email or Indian mobile number looks like, and RegisterStep's own local copy of
// EMAIL_PATTERN (pre-existing) had already started drifting into a second definition of the same
// rule before this file existed.
export const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

// Mirrors the backend's own AuthDtos.PHONE_REGEXP shape (`^(\+91)?[6-9][0-9]{9}$`) -- the app is
// India-only (see PhoneNumbers.normalize's doc comment), so a real phone identifier is always a
// bare 10-digit or +91-prefixed Indian mobile number, never anything else.
export const PHONE_LIKE_PATTERN = /^(\+91)?[6-9][0-9]{9}$/;

/**
 * Whether `raw` looks like *something* worth sending to /auth/identify or /auth/login -- an
 * email address or an Indian mobile number, with or without the +91 prefix. This is the check
 * IdentifyStep and PasswordStep were missing entirely (only requiring non-empty), which let
 * obvious garbage like "123@" reach the backend with no client-side feedback at all.
 */
export function looksLikeValidIdentifier(raw: string): boolean {
  const trimmed = raw.trim();
  return EMAIL_PATTERN.test(trimmed) || PHONE_LIKE_PATTERN.test(trimmed);
}
