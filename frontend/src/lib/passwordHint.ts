/**
 * Shown under every field where a password is chosen. It replaced the strength meters (audit
 * F-08, 2026-11-21): a meter that counts capitals, digits and symbols called "Password1!" strong,
 * and the server refuses it. What the server refuses cannot be worked out in the browser, because
 * it is a list of 46,000 passwords, so this says what the rule is and the server's own sentence
 * says which part of it a refused password broke.
 *
 * Keep the wording in step with WeakPasswordGuard on the backend, and with the same constant in
 * admin-portal/src/lib/passwordHint.ts and mobile/src/lib/validation.ts.
 */
export const PASSWORD_HINT =
  'At least 8 characters. Several words together work well. Common passwords, a single short '
  + 'word with numbers, and your own name, email or phone number are not accepted.';
