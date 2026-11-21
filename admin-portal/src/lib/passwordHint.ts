/**
 * Shown under every field where a password is chosen. It replaced the strength meter (audit
 * F-08, 2026-11-21), which called "Password1!" strong while the server refuses it.
 *
 * Keep the wording in step with WeakPasswordGuard on the backend, and with the same constant in
 * frontend/src/lib/passwordHint.ts and mobile/src/lib/validation.ts.
 */
export const PASSWORD_HINT =
  'At least 8 characters. Several words together work well. Common passwords, a single short '
  + 'word with numbers, and your own name, email or phone number are not accepted.';
