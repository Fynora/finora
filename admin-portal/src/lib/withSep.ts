/**
 * "Sep", never "Sept". Intl's en-GB (and en-IN, which a browser can report as its own locale)
 * writes September as "Sept", where the server's emails and every other month in the app use three
 * letters. Every date here that names its month outside en-US goes through this; withSep.test.ts
 * checks that none skips it.
 */
export function withSep(text: string): string {
  return text.replace(/\bSept\b/g, 'Sep');
}
