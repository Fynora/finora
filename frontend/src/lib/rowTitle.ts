/**
 * The title a transaction row shows -- the mobile app's rule, copied from
 * mobile/src/lib/ledgerRows.ts so both apps title a row the same way. The server's `merchant` is
 * lower case and cut to four words (hand-entered transactions too), so it cannot be shown as it is:
 * the web Ledger showed "sample landlord", and "dinner after balance entry" for a row typed
 * "Dinner after balance entry". Keep this file and the mobile one in step.
 */
type TitleSource = { description?: string | null; merchant?: string | null };

// A word is a run of the letters A to Z; an apostrophe between letters stays inside it, so
// "DOMINO'S" is one word. Letters outside A to Z are left alone: statements print these names in
// plain capitals, and a pattern that names other alphabets is one more thing to get wrong.
const WORD = /[A-Za-z]+(?:'[A-Za-z]+)*/g;

// Payment rails and banking terms that stay in capitals (the owner, 2026-10-16): "Upi" and "Neft"
// read as typos. A fixed list, not a guess at what is an abbreviation; a whole word only, so
// "POSTAL" is still softened.
const KEPT_IN_CAPITALS: ReadonlySet<string> = new Set([
  'UPI', 'NEFT', 'IMPS', 'RTGS', 'ATM', 'POS', 'EMI', 'ECS', 'NACH', 'GST', 'TDS', 'SIP',
]);

/**
 * The owner's choice for row titles (design, 2026-10-12): a word printed entirely in capitals is
 * shown with only its first letter capital. A word that already has a small letter, a single
 * letter, digits and punctuation are left exactly as printed. An abbreviation therefore reads as a
 * word ("IRCTC" as "Irctc"); that cost was accepted with the choice, except for the banking terms
 * in KEPT_IN_CAPITALS above.
 */
export function softenCapitals(text: string): string {
  return text.replace(WORD, (word) => {
    const letters = word.replace(/'/g, '');
    if (letters.length < 2 || word !== word.toUpperCase() || KEPT_IN_CAPITALS.has(word)) return word;
    return word[0] + word.slice(1).toLowerCase();
  });
}

/** Lower case, with every run of anything that is not a letter or digit as one space: the same
 *  reduction the server's merchant label has already been through. */
function plain(text: string): string {
  return text.toLowerCase().replace(/[^a-z0-9]+/g, ' ').trim();
}

function capitaliseIfLower(text: string): string {
  if (text !== text.toLowerCase()) return text;
  return text.replace(/(^|\s)([a-z])/g, (_, gap: string, letter: string) => gap + letter.toUpperCase())
    // The casing here is made up, so a banking term gets its capitals back (KEPT_IN_CAPITALS).
    .replace(WORD, (word) => (KEPT_IN_CAPITALS.has(word.toUpperCase()) ? word.toUpperCase() : word));
}

function printedTitle(description: string, merchant: string): string {
  const words = plain(merchant).split(' ').filter(Boolean);
  if (words.length === 0) return description || 'Transaction';
  const key = words.join(' ');

  // A description without a slash that begins with the merchant is plain words already (a
  // hand-entered transaction, an unstructured bank line): it is the title, in full. The slash
  // test keeps out the layouts that print the payee ahead of a structured narration.
  if (description && !description.includes('/')) {
    const reduced = plain(description);
    if (reduced === key || reduced.startsWith(`${key} `)) return description;
  }

  // Otherwise the merchant's words are looked for inside the description, whatever punctuation
  // sits between them, as whole words. `words` holds only a-z and 0-9, so nothing in it can act
  // as a pattern character.
  const found = new RegExp(`(?:^|[^A-Za-z0-9])(${words.join('[^A-Za-z0-9]+')})(?![A-Za-z0-9])`, 'i').exec(description);
  if (found) return found[1];

  return capitaliseIfLower(merchant);
}

/**
 * Who a transaction was with, as the row shows it. The server's `merchant` cannot be shown as it
 * is: it is lower case, stripped of punctuation and cut to four words, for hand-entered
 * transactions too. See the design document for the order of the rules and what was measured
 * (docs/superpowers/specs/2026-10-11-mobile-transactions-redesign-design.md, "Row title").
 */
export function rowTitle(t: TitleSource): string {
  const description = (t.description ?? '').trim();
  const merchant = (t.merchant ?? '').trim();
  return softenCapitals(merchant ? printedTitle(description, merchant) : description || 'Transaction');
}
