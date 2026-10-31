/**
 * Fynora's grievance contact, named in the consent notice (V302). Same values as the web app's
 * frontend/src/lib/contact.ts, which the Privacy Policy's Grievance Redressal section also uses.
 */
// The mobile app cannot import frontend/src/lib/contact.ts (separate package), so the address is
// repeated here, kept equal to that file's GRIEVANCE_EMAIL.
export const GRIEVANCE_EMAIL = 'privacy@fynora.net'; // synthetic-ok: Finora's own grievance-officer mailbox, not customer PII; check-contact-addresses: allow
export const GRIEVANCE_MAILTO = `mailto:${GRIEVANCE_EMAIL}`;
export const GRIEVANCE_OFFICER_NAME = 'Vishnu Narayan Tiwari';
