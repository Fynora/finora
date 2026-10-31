/**
 * Every sentence the free statement converter page says, in one place: the page renders it, and
 * src/lib/structuredData.ts reads the same FAQ for the page's FAQPage markup, so the two cannot
 * drift. Design 2026-10-31, approved by Sid.
 *
 * Every line is a public claim. Each one is true of the backend as built
 * (StatementConversionService): nothing is kept, the virus check, Fynora's own software, no AI,
 * the limits, the amounts check. Scanned statements are NOT claimed for the app either: the
 * landing page deliberately makes no scanned-statement claim (landing-claims.test.tsx), so this page
 * only says the free tool can't read one. No bank password formats, ever (Sid, 2026-10-16).
 */

export const CONVERTER_ROUTE = '/bank-statement-to-excel';

export const CONVERTER_TITLE = 'Convert a bank statement PDF to Excel or CSV';

/** The meta description: 70 to 160 characters (pageMeta.test.tsx). */
export const CONVERTER_DESCRIPTION =
  'Turn a bank or credit card statement PDF into an Excel or CSV file. Free, no sign-up, amounts checked, and we don’t keep your file.';

/** The answer-first paragraph under the heading, before the tool. */
export const CONVERTER_INTRO =
  'Drop your bank statement PDF below and download its transactions as an Excel or CSV file. It’s free and needs no account. Fynora checks the amounts against your statement’s own totals before giving you the file, and deletes your statement as soon as the file is ready.';

export const MAX_PAGES = 20;
export const MAX_MEGABYTES = 5;

/** The notice beside the consent box (DPDP Act s.5): what happens to the file, in plain words. */
export const FILE_NOTICE =
  'Your file is checked for viruses, read by Fynora’s own software on servers located outside India (in the United States), and sent back to you as a spreadsheet. We don’t save it, we don’t save the transactions, and no person at Fynora sees it. Nothing is sent to an AI service. We keep your network address for up to 24 hours only to stop misuse.';

export const CONSENT_LABEL =
  'I’m 18 or older, this is my own statement, and I agree that Fynora reads it to make this file. Fynora doesn’t keep the statement or the transactions: both are deleted as soon as the file is ready, within 60 seconds.';

/** Shown with every converted file: what the check covers, and what it does not. */
export const CHECKED_NOTE =
  'Amounts and balances were checked against your statement’s own totals. Descriptions are copied as they were read, so check them before relying on them.';

export const STEPS: ReadonlyArray<readonly [string, string]> = [
  ['Drop your statement', 'A PDF downloaded from your bank’s website or app, up to 20 pages and 5 MB.'],
  ['We read and check it', 'Fynora reads the transactions and checks the amounts against the totals printed on the statement.'],
  ['Download your file', 'Save the transactions as an Excel or CSV file. Your statement is deleted straight away.'],
];

/**
 * Banks whose real statements converted in the PR 0 measurement (2026-10-31): a within-limits
 * statement from each was read and its amounts confirmed. Others may work; these were tested.
 * Bank names on the public site are allowed (Sid, 2026-10-31), with the not-affiliated line.
 */
export const TESTED_BANKS: readonly string[] = [
  'HDFC Bank', 'ICICI Bank', 'State Bank of India', 'Axis Bank', 'Kotak Mahindra Bank', 'IndusInd Bank',
  'AU Small Finance Bank', 'HSBC', 'Bank of Baroda', 'Punjab National Bank', 'Canara Bank', 'Union Bank of India',
  'Central Bank of India', 'Indian Overseas Bank', 'Bandhan Bank', 'slice',
];

export const NOT_AFFILIATED =
  'Fynora is not affiliated with, endorsed by or partnered with any of these banks. Bank names are used only to say whose statements we have tested.';

export const CONVERTER_FAQ: ReadonlyArray<readonly [string, string]> = [
  [
    'Is this bank statement converter free?',
    'Yes. It’s free and needs no account. To keep it free for everyone, each network can convert 3 statements an hour and 10 a day, and each statement can be up to 20 pages and 5 MB.',
  ],
  [
    'Do you keep my bank statement?',
    'No. Your statement and its transactions exist only while your file is being made, and are deleted as soon as it’s ready, or within 60 seconds if something goes wrong. We keep your network address for up to 24 hours to limit misuse, and a daily count of how many conversions worked, which contains nothing about you or your statement.',
  ],
  [
    'Can it convert a password-protected PDF?',
    'Yes. If your statement is locked, type the password you use to open it. It’s used once to open the file and is never saved or logged.',
  ],
  [
    'Can it convert a scanned statement?',
    'No. The free converter reads statements downloaded from your bank’s website or app, which contain text. A scanned or photographed statement can’t be converted here.',
  ],
  [
    'Which banks does it work with?',
    'It reads text PDF statements from Indian banks and card issuers. We have tested it on real statements from HDFC Bank, ICICI Bank, State Bank of India, Axis Bank, Kotak Mahindra Bank and others listed on this page. Other banks may work too.',
  ],
  [
    'Why did it refuse my statement?',
    'Before giving you a file, Fynora checks that the transactions add up to the totals printed on the statement. If they don’t, we won’t give you a spreadsheet that might be wrong. A free Fynora account can still import the statement: statements we can’t check automatically are checked by our team.',
  ],
  [
    'Should I download Excel or CSV?',
    'Choose Excel to open the file straight away with dates and amounts ready to sort and add up. Choose CSV if you want a plain file that any spreadsheet or accounting tool can import.',
  ],
  [
    'Is it safe to upload my bank statement?',
    'Your file travels over an encrypted connection, is checked for viruses, and is read by Fynora’s own software, never by a person or an AI service. Nothing from it is saved.',
  ],
];
