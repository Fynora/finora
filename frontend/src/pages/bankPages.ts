import { PLANS } from './landing/plans';
import { faq as landingFaq } from './landing/landing-config';

/**
 * One public page per bank statement Fynora has been measured on, at /banks/<slug>, plus the /banks
 * hub. Each page answers what someone searching for their bank's statement wants: how to download
 * it from the bank's own site or app, and what Fynora does with it once uploaded.
 *
 * THE RULES FOR THIS FILE. Every sentence here is a public claim, the same as landing-config.ts, and
 * bankPages.test.tsx enforces what can be checked mechanically:
 *
 * - A bank gets a page only after a real statement from it was measured clean on the local corpus:
 *   its answer key passes, the trust gate does not hold it, and at least one amount check (the
 *   running balance, the statement totals, or a card's total due) is VERIFIED. `measuredOn` is the
 *   day that was last done. Re-measure before adding a bank or after a parser change (the method is
 *   in the 2026-10-31 bank-pages plan, scripts/run-corpus-ground-truth.py plus the trust gate).
 * - Every download step is paraphrased from the bank's own page, named in `source` with the day it
 *   was read. Where a bank publishes no steps that could be checked, the page links to the bank and
 *   lists none (Canara). Never write a step from memory.
 * - No bank password rules, anywhere (owner decision, 2026-10-31). A page may say a statement is
 *   password-protected and that the password is typed at upload; never how a bank builds it.
 * - Every page says Fynora is not affiliated with the bank and never implies a partnership.
 * - Promise checking, never perfection: no "works perfectly with" a bank. The checking claim is the
 *   landing page's own: where the statement prints balances or totals, Fynora compares its reading
 *   with them, and holds the statement when they disagree.
 * - `reads` lists only details that were extracted on every measured statement of that bank.
 *
 * Once published, NEVER change a slug: search results and AI answers link to it.
 */

export type BankProduct = 'savings' | 'credit-card';

export interface DownloadSteps {
  /** Where the steps happen, e.g. "In the HDFC Bank app". */
  heading: string;
  steps: string[];
}

export interface StepSource {
  /** The bank page the steps are paraphrased from. */
  url: string;
  /** How the page is named in the link, e.g. "HDFC Bank: download your statement on the app". */
  label: string;
  /** The day the page was read, ISO yyyy-mm-dd. */
  checked: string;
}

export interface BankPage {
  /** /banks/<slug>. Mirrors what people search for ("hdfc bank statement"). */
  slug: string;
  /** The bank's own name, used in the not-affiliated line. */
  bankName: string;
  product: BankProduct;
  /** The page's <h1> and so its <title>. */
  title: string;
  /** Meta description, 70 to 160 characters (pageMeta.test.tsx). */
  description: string;
  /** The opening paragraph: the direct answer first, so it can be quoted on its own. */
  answer: string;
  steps: DownloadSteps[];
  sources: StepSource[];
  /** Shown instead of steps when the bank publishes none that could be checked. */
  noStepsNote?: string;
  /** What Fynora reads from this bank's statement (extracted on every measured statement). */
  reads: string[];
  /** Anything specific to this bank's statement a reader should know. */
  notes?: string[];
  /** The day this bank's statements were last measured on the real corpus, ISO yyyy-mm-dd. */
  measuredOn: string;
}

const READ_SAVINGS_CORE = [
  'Every transaction: date, description, amount, and whether it is a debit or a credit',
  'The opening and closing balance',
  'The statement period',
  'The masked account number and the account holder',
];

const READ_CARD = [
  'Every transaction: date, description, amount, and whether it is a charge or a credit',
  'The total amount due',
  'The statement period and the payment due date',
  'The masked card number, the credit limit and the cardholder name',
];

const MEASURED = '2026-10-31';
const READ_ON = '2026-10-31';

export const BANK_PAGES: BankPage[] = [
  {
    slug: 'hdfc-bank-statement',
    bankName: 'HDFC Bank',
    product: 'savings',
    title: 'Upload your HDFC Bank statement',
    description:
      'Download your HDFC Bank statement as a PDF from NetBanking or the HDFC Bank app, then upload it to Fynora to sort every transaction.',
    answer:
      'Fynora reads HDFC Bank savings account statements in PDF, including password-protected ones. Download the statement from NetBanking or the HDFC Bank app and upload it: Fynora lists every transaction, compares its reading with the running balance and totals the statement prints, and sorts each transaction into a category. You see every row and confirm before anything is saved.',
    steps: [
      {
        heading: 'In the HDFC Bank app',
        steps: [
          'Log in and tap Statement on the home screen.',
          'Tap Get Statement.',
          'Choose a preset period or set a custom date range, then tap Download.',
          'The statement downloads as a password-protected PDF.',
        ],
      },
      {
        heading: 'In NetBanking',
        steps: [
          'Log in to NetBanking and go to Account Statements.',
          'Click Get Statement and select the account.',
          'Choose a preset period or a custom date range, then click Download.',
        ],
      },
    ],
    sources: [
      { url: 'https://www.hdfc.bank.in/blogs/digital-banking/view-download-account-statement-hdfc-bank-app', label: 'HDFC Bank: download your account statement on the HDFC Bank app', checked: READ_ON },
      { url: 'https://www.hdfc.bank.in/blogs/digital-banking-1/download-account-statement-via-netbanking', label: 'HDFC Bank: download your account statement via NetBanking', checked: READ_ON },
    ],
    reads: [...READ_SAVINGS_CORE, 'The branch and IFSC'],
    notes: [
      'A combined HDFC Bank statement that also lists fixed or recurring deposit schedules is held for a person to check, because the deposit tables are not read automatically yet. Its savings transactions are read the same way as any other HDFC Bank statement.',
    ],
    measuredOn: MEASURED,
  },
  {
    slug: 'sbi-bank-statement',
    bankName: 'State Bank of India',
    product: 'savings',
    title: 'Upload your SBI bank statement',
    description:
      'Download your State Bank of India account statement from SBI internet banking, then upload the PDF to Fynora to sort every transaction.',
    answer:
      'Fynora reads State Bank of India savings account statements in PDF. Download the statement from SBI internet banking and upload it: Fynora lists every transaction, compares its reading with the running balance and totals the statement prints, and sorts each transaction into a category. You see every row and confirm before anything is saved.',
    steps: [
      {
        heading: 'In SBI internet banking',
        steps: [
          'Log in to SBI internet banking.',
          'Go to My Accounts & Profile, then Account Statement.',
          'Select the account, enter the period you need and submit.',
        ],
      },
    ],
    sources: [
      { url: 'https://sbi.bank.in/web/faq-s/retail-internet-banking', label: 'SBI: retail internet banking FAQ', checked: READ_ON },
    ],
    reads: [...READ_SAVINGS_CORE, 'The branch and IFSC'],
    notes: ["SBI's website does not publish YONO app steps that could be checked, so only the internet banking steps are given here."],
    measuredOn: MEASURED,
  },
  {
    slug: 'bank-of-baroda-statement',
    bankName: 'Bank of Baroda',
    product: 'savings',
    title: 'Upload your Bank of Baroda statement',
    description:
      'Download your Bank of Baroda statement as a PDF from bob World internet banking or the bob World app, then upload it to Fynora.',
    answer:
      'Fynora reads Bank of Baroda savings account statements in PDF. Download a PDF statement from bob World internet banking, or request one in the bob World app, and upload it: Fynora lists every transaction, compares its reading with the running balance and totals the statement prints, and sorts each transaction into a category.',
    steps: [
      {
        heading: 'In bob World internet banking',
        steps: [
          'Log in and open Operative Accounts under the Accounts tab.',
          'Click the three dots next to the account and choose Generate Account Statement.',
          'Click Search Transactions, enter the date range and click Search.',
          'Download the statement as a PDF. Excel and TXT are offered too, but Fynora reads PDF and CSV files.',
        ],
      },
      {
        heading: 'In the bob World app',
        steps: [
          'Log in and open Request Services from the More tab or the My bob tab.',
          'Choose Certificate & Statement, then Account Statement.',
          'Select the account and the From and To dates, then tap Submit.',
          'Bank of Baroda emails you the statement as a password-protected PDF.',
        ],
      },
    ],
    sources: [
      { url: 'https://bankofbaroda.bank.in/banking-mantra/digital/articles/how-to-get-a-bank-statement-online', label: 'Bank of Baroda: how to get a bank statement online', checked: READ_ON },
    ],
    reads: READ_SAVINGS_CORE,
    measuredOn: MEASURED,
  },
  {
    slug: 'canara-bank-statement',
    bankName: 'Canara Bank',
    product: 'savings',
    title: 'Upload your Canara Bank statement',
    description:
      'Upload your Canara Bank savings account statement as a PDF. Fynora sorts every transaction and compares it with the balances the statement prints.',
    answer:
      'Fynora reads Canara Bank savings account statements in PDF. Download the PDF statement from Canara Bank net banking or the Canara ai1 app and upload it: Fynora lists every transaction, compares its reading with the running balance and totals the statement prints, and sorts each transaction into a category.',
    steps: [],
    sources: [
      { url: 'https://www.canarabank.bank.in/pages/net-banking', label: 'Canara Bank: net banking', checked: READ_ON },
      { url: 'https://www.canarabank.bank.in/pages/mobile-banking', label: 'Canara Bank: mobile banking (Canara ai1)', checked: READ_ON },
    ],
    noStepsNote:
      "Canara Bank's net banking and mobile banking pages do not give step-by-step instructions for downloading a full account statement, so none are given here. Log in to Canara Bank net banking or the Canara ai1 app to download it.",
    reads: [...READ_SAVINGS_CORE, 'The branch and IFSC'],
    measuredOn: MEASURED,
  },
  {
    slug: 'hdfc-credit-card-statement',
    bankName: 'HDFC Bank',
    product: 'credit-card',
    title: 'Upload your HDFC credit card statement',
    description:
      'Upload the PDF credit card statement HDFC Bank sends you. Fynora sorts every transaction and checks it against the total amount due.',
    answer:
      "Fynora reads HDFC Bank credit card statements in PDF, including co-branded cards. Upload the statement HDFC Bank emails you, or one from NetBanking: Fynora lists every transaction, adds them up and compares the result with the statement's total amount due, and sorts each transaction into a category before you confirm.",
    steps: [
      {
        heading: 'Where to find it',
        steps: [
          'HDFC Bank sends your credit card statement by email or courier each month.',
          'You can also view it online through HDFC Bank NetBanking, or sign in to HDFC Bank MyCards.',
        ],
      },
    ],
    sources: [
      { url: 'https://www.hdfc.bank.in/blogs/credit-cards/how-to-understand-credit-card-statement', label: 'HDFC Bank: how to check your credit card bill', checked: READ_ON },
      { url: 'https://mycards.hdfc.bank.in/', label: 'HDFC Bank MyCards', checked: READ_ON },
    ],
    reads: READ_CARD,
    notes: ["HDFC Bank's pages do not give click-by-click steps for downloading a card statement, so none are given here."],
    measuredOn: MEASURED,
  },
  {
    slug: 'icici-bank-statement',
    bankName: 'ICICI Bank',
    product: 'savings',
    title: 'Upload your ICICI Bank statement',
    description:
      'Download your ICICI Bank savings account statement from Net Banking, then upload the PDF to Fynora to sort and check every transaction.',
    answer:
      'Fynora reads ICICI Bank savings account statements in PDF. Download the statement from ICICI Bank Net Banking and upload it: Fynora lists every transaction, compares its reading with the running balance and totals the statement prints, and sorts each transaction into a category.',
    steps: [
      {
        heading: 'In ICICI Bank Net Banking',
        steps: [
          'Log in to Net Banking.',
          'Go to Bank Accounts, then e-Statements, then View Detailed Statement.',
          'Select the date range and download. ICICI Bank shows statements for up to the last 4 years.',
        ],
      },
    ],
    sources: [
      { url: 'https://www.icici.bank.in/personal-banking/accounts/savings-account/statements', label: 'ICICI Bank: download your savings account statement online', checked: READ_ON },
    ],
    reads: [...READ_SAVINGS_CORE, 'The branch'],
    measuredOn: MEASURED,
  },
  {
    slug: 'icici-credit-card-statement',
    bankName: 'ICICI Bank',
    product: 'credit-card',
    title: 'Upload your ICICI credit card statement',
    description:
      'Download your ICICI Bank credit card statement from Net Banking or iMobile, then upload the PDF to Fynora to check it against the total due.',
    answer:
      "Fynora reads ICICI Bank credit card statements in PDF. Download the statement from Net Banking or the iMobile app and upload it: Fynora lists every transaction, adds them up and compares the result with the statement's total amount due, and sorts each transaction into a category.",
    steps: [
      {
        heading: 'In ICICI Bank Net Banking',
        steps: [
          'Log in to Net Banking.',
          'Go to Credit Cards, then View Statement.',
          'Select the billing period and click Download PDF.',
        ],
      },
      {
        heading: 'In the iMobile app',
        steps: ['Log in, go to Credit Cards and select Statements to view or download them.'],
      },
    ],
    sources: [
      { url: 'https://www.icici.bank.in/personal-banking/blogs/card/credit-card/credit-card-statement-online', label: 'ICICI Bank: how to check and get your credit card statement', checked: READ_ON },
    ],
    reads: READ_CARD,
    measuredOn: MEASURED,
  },
  {
    slug: 'kotak-bank-statement',
    bankName: 'Kotak Mahindra Bank',
    product: 'savings',
    title: 'Upload your Kotak bank statement',
    description:
      'Download your Kotak Mahindra Bank statement from the Kotak app, then upload the PDF to Fynora to sort and check every transaction.',
    answer:
      'Fynora reads Kotak Mahindra Bank savings account statements in PDF. Download the statement from the Kotak app and upload it: Fynora lists every transaction, compares its reading with the running balance and totals the statement prints, and sorts each transaction into a category.',
    steps: [
      {
        heading: 'In the Kotak app',
        steps: ['Log in to the Kotak app.', 'Go to the Statement section.', 'Tap Consolidated Statement.'],
      },
    ],
    sources: [
      { url: 'https://www.kotak.bank.in/en/stories-in-focus/accounts-deposits/savings-account/how-to-get-a-bank-statement-online.html', label: 'Kotak Mahindra Bank: how to get a bank statement online', checked: READ_ON },
    ],
    reads: [...READ_SAVINGS_CORE, 'The branch and IFSC'],
    measuredOn: MEASURED,
  },
  {
    slug: 'pnb-bank-statement',
    bankName: 'Punjab National Bank',
    product: 'savings',
    title: 'Upload your PNB bank statement',
    description:
      'Download your Punjab National Bank statement from the PNB ONE app, then upload the PDF to Fynora to sort and check every transaction.',
    answer:
      'Fynora reads Punjab National Bank savings account statements in PDF. Download the statement from the PNB ONE app and upload it: Fynora lists every transaction, compares its reading with the running balance and totals the statement prints, and sorts each transaction into a category.',
    steps: [
      {
        heading: 'In the PNB ONE app',
        steps: ['Open mPassbook, which shows a detailed view of your account and lets you download the account statement.'],
      },
      {
        heading: 'By email every month',
        steps: [
          'In PNB internet banking, go to Other Services, then Service Requests, then New Requests.',
          'Choose Email Statement Registration.',
        ],
      },
    ],
    sources: [
      { url: 'https://pnb.bank.in/mobile-banking.html', label: 'Punjab National Bank: PNB ONE', checked: READ_ON },
      { url: 'https://pnb.bank.in/PNB-E-Statement.html', label: 'Punjab National Bank: e-statement', checked: READ_ON },
    ],
    reads: [...READ_SAVINGS_CORE, 'The branch and IFSC'],
    measuredOn: MEASURED,
  },
  {
    slug: 'axis-credit-card-statement',
    bankName: 'Axis Bank',
    product: 'credit-card',
    title: 'Upload your Axis Bank credit card statement',
    description:
      'Download your Axis Bank credit card statement from Axis Mobile or the Axis support site, then upload the PDF to Fynora to check it.',
    answer:
      "Fynora reads Axis Bank credit card statements in PDF. Download the statement from Axis Mobile or the Axis Bank support site and upload it: Fynora lists every transaction, adds them up and compares the result with the statement's totals, and sorts each transaction into a category.",
    steps: [
      {
        heading: 'In Axis Mobile',
        steps: ['From the home page, go to Credit Cards, then Get Statement.'],
      },
      {
        heading: 'On the Axis Bank support site',
        steps: [
          'Open Insta Services and choose Download Using Registered Mobile Number.',
          'Enter your registered mobile number and the OTP.',
          'Select your card. The latest statement downloads to your device.',
        ],
      },
    ],
    sources: [
      { url: 'https://application.axis.bank.in/webforms/axis-support/sub-issues/Cards-Credit-statement-2.aspx', label: 'Axis Bank: how do I view or download my credit card statement', checked: READ_ON },
    ],
    reads: READ_CARD,
    measuredOn: MEASURED,
  },
  {
    slug: 'kotak-credit-card-statement',
    bankName: 'Kotak Mahindra Bank',
    product: 'credit-card',
    title: 'Upload your Kotak credit card statement',
    description:
      'Download your Kotak Mahindra Bank credit card statement from Net Banking, then upload the PDF to Fynora to check it against the total due.',
    answer:
      "Fynora reads Kotak Mahindra Bank credit card statements in PDF. Download the statement from Kotak Net Banking and upload it: Fynora lists every transaction, adds them up and compares the result with the statement's total amount due, and sorts each transaction into a category.",
    steps: [
      {
        heading: 'In Kotak Net Banking',
        steps: ['Log in, then go to Credit Card, then Statement.'],
      },
      {
        heading: 'In Kotak Mobile Banking',
        steps: ['Statements can be viewed under Credit Card, then CC Statements (view only).'],
      },
    ],
    sources: [
      { url: 'https://www.kotak.bank.in/en/help-center/credit-card/statement-related/how-can-i-download-my-credit-card-statement-.html', label: 'Kotak Mahindra Bank: view or download your credit card statement', checked: READ_ON },
    ],
    reads: READ_CARD,
    measuredOn: MEASURED,
  },
  {
    slug: 'indusind-credit-card-statement',
    bankName: 'IndusInd Bank',
    product: 'credit-card',
    title: 'Upload your IndusInd credit card statement',
    description:
      'Upload your IndusInd Bank credit card statement as a PDF. Fynora sorts every transaction and checks it against the total amount due.',
    answer:
      "Fynora reads IndusInd Bank credit card statements in PDF. Upload the statement and Fynora lists every transaction, adds them up and compares the result with the statement's total amount due, and sorts each transaction into a category before you confirm.",
    steps: [
      {
        heading: 'On IndusInd Bank WhatsApp Banking',
        steps: [
          "Register for WhatsApp Banking from your registered mobile number. IndusInd Bank's page gives the number to message.",
          'Download credit card statement is one of the services it lists.',
        ],
      },
    ],
    sources: [
      { url: 'https://www.indusind.bank.in/iblogs/credit-card/enjoy-credit-card-services-on-the-go-with-whatsapp-banking/', label: 'IndusInd Bank: credit card services on WhatsApp Banking', checked: READ_ON },
    ],
    reads: READ_CARD,
    measuredOn: MEASURED,
  },
];

/** The route App.tsx renders every bank page at. */
export const BANK_PAGE_ROUTE = '/banks/:slug';

/** The hub that lists every bank page. */
export const BANKS_HUB_PATH = '/banks';

/** The hub's heading, which is also the middle crumb of every bank page's breadcrumb trail. */
export const BANKS_HUB_TITLE = 'Bank statements Fynora reads';

export function bankPagePath(page: Pick<BankPage, 'slug'>): string {
  return `/banks/${page.slug}`;
}

export function bankPageBySlug(slug: string | undefined): BankPage | undefined {
  return slug === undefined ? undefined : BANK_PAGES.find((p) => p.slug === slug);
}

export function bankPageForPath(pathname: string): BankPage | undefined {
  const match = /^\/banks\/([^/]+)\/?$/.exec(pathname);
  return match ? bankPageBySlug(match[1]) : undefined;
}

/** "Fynora is not affiliated with..." for one bank. Every page shows it (bankPages.test.tsx). */
export function notAffiliatedLine(bankName: string): string {
  return `Fynora is an independent app. It is not affiliated with, endorsed by or partnered with ${bankName}. ${bankName} is named here only to describe its statements.`;
}

const freePlan = PLANS.find((p) => p.id === 'free');
const plusPlan = PLANS.find((p) => p.id === 'plus');
if (!freePlan || !plusPlan || plusPlan.price === null) {
  throw new Error('bankPages: the Free and Plus plans must exist in plans.ts, Plus with a price');
}
/** Plans.ts's own Free-plan line, e.g. "Up to 2 accounts, one month per statement". */
export const FREE_PLAN_LIMIT = freePlan.features.find((f) => /one month per statement/i.test(f));
if (!FREE_PLAN_LIMIT) throw new Error('bankPages: plans.ts no longer states the Free plan\'s statement length');

function landingAnswer(question: string): string {
  const item = landingFaq.items.find(([q]) => q === question);
  if (!item) throw new Error(`bankPages: the landing FAQ no longer has "${question}"`);
  return item[1];
}

/**
 * A bank page's questions and answers, shown on the page and marked up as its FAQPage, so the two
 * can never differ. The answers reuse the landing page's reviewed wording and plans.ts's limits.
 */
export function bankPageFaq(page: BankPage): [string, string][] {
  const product = page.product === 'credit-card' ? 'credit card statement' : 'statement';
  return [
    [`Does Fynora connect to my ${page.bankName} account?`, landingAnswer('Does Fynora connect to my bank account?')],
    [
      `My ${page.bankName} ${product} is password-protected. Can I still upload it?`,
      'Yes. Password-protected PDFs work: enter the password during upload and Fynora opens the file to read it. The password travels in the request body, never in a URL. Fynora does not keep it unless you choose to save it so that statement can be refreshed without asking you again; if you do not, a later re-import will ask again.',
    ],
    [
      'Can I upload a statement longer than one month?',
      `The Free plan allows ${FREE_PLAN_LIMIT!.charAt(0).toLowerCase()}${FREE_PLAN_LIMIT!.slice(1)}. Plus, at ${plusPlan!.price}${plusPlan!.cadence ?? ''}, reads longer statements and any number of accounts.`,
    ],
    [
      'What if Fynora cannot read my statement correctly?',
      "Where the statement prints balances or totals, Fynora compares its reading with them. If they disagree, or a transaction looks missing, the statement is held instead of imported, and a person checks it by hand. That takes up to 48 hours, and you're notified when it's done.",
    ],
    [`Is Fynora part of ${page.bankName}?`, notAffiliatedLine(page.bankName)],
  ];
}
