import { SUPPORT_EMAIL } from '../lib/contact';

/**
 * The Help Center's articles, in the order they are shown. In their own module (not Help.tsx) so
 * src/lib/structuredData.ts and the prerender can read them without importing a React component
 * and its icons.
 *
 * Every article is also a page of its own at /help/<slug> (HelpArticle.tsx), prerendered to
 * dist/help/<slug>.html with its own title, description, canonical and FAQPage markup, so a search
 * engine or an AI answer can quote and link one answer rather than a page of thirty-six. Adding an
 * article here adds it to /help, to its category filter, to the build and to the structured data at
 * once. It must also be added to public/sitemap.xml; seoFiles.test.tsx fails until it is.
 */
export interface HelpArticle {
  /**
   * The article's address, /help/<slug>. Lower-case words and hyphens. Once published, NEVER change
   * a slug: search results, AI answers and support replies link to it, and the old address would
   * become a 404.
   */
  slug: string;
  category: string;
  question: string;
  /** Leads with the direct answer, so the first sentence can be quoted on its own. */
  answer: string;
  /**
   * The page's meta description, for an answer outside the 70 to 160 characters a search result
   * shows (pageMeta.test.tsx enforces the range). Only a shortening of the answer itself: it must
   * say nothing the answer does not. Omitted when the answer already fits.
   */
  description?: string;
  /** Slugs of other articles worth reading next, beyond the ones in the same category. */
  related?: string[];
}

export const HELP_ARTICLES: HelpArticle[] = [
  { slug: 'what-is-fynora', category: 'Getting Started', question: 'What is Fynora?', answer: 'Fynora is a personal finance platform that imports your bank/card statements, categorizes transactions automatically, and gives you one dashboard across accounts, budgets, goals, and investments.', description: "Fynora is a personal finance platform that imports your bank and card statements, categorizes transactions automatically and shows one dashboard.", related: ['supported-file-formats', 'plans-and-prices'] },
  { slug: 'do-i-need-to-connect-my-bank-account', category: 'Getting Started', question: 'Do I need to connect my bank account?', answer: "No live bank connection is required. You export a CSV or PDF statement from your bank or card's own portal and upload it to Fynora — nothing requires your bank login credentials.", description: "No live bank connection is required. Export a CSV or PDF statement from your bank or card portal and upload it. Fynora never needs your bank login.", related: ['supported-file-formats'] },

  { slug: 'what-do-i-need-to-sign-up', category: 'Account Registration', question: 'What do I need to sign up?', answer: 'A full name, email address, mobile number, and a password of at least 8 characters. You\'ll verify your phone number with an OTP right after registering.' },
  { slug: 'same-email-or-phone-on-two-accounts', category: 'Account Registration', question: 'Can I use the same email or phone number twice?', answer: 'No — each email address and mobile number can only be registered to one account. If you see a "duplicate" error, that identifier is already in use.' },

  { slug: 'log-in-with-phone-number', category: 'Login & Authentication', question: 'Can I log in with my phone number instead of email?', answer: 'Yes. The sign-in field accepts either your email address or your registered mobile number — you don\'t need to remember which one you used to register.' },
  { slug: 'too-many-wrong-passwords', category: 'Login & Authentication', question: 'What happens if I enter the wrong password too many times?', answer: 'After several consecutive failed attempts, the account is temporarily locked for a short period as a security measure, then unlocks automatically.' },
  { slug: 'why-verify-phone-number', category: 'Login & Authentication', question: 'Why do I need to verify my phone number?', answer: "Phone verification confirms you have access to the number on file and is required before you can use the rest of the app — you'll be redirected to the verification screen until it's complete.", description: "Phone verification confirms you have access to the number on file. It is required before you can use the rest of the Fynora app." },

  { slug: 'supported-file-formats', category: 'Importing Statements', question: 'Which file formats are supported?', answer: 'PDF and CSV only: CSV exports from your bank or card portal, and PDF statements, including password-protected ones your bank emails you. Excel files (.xls or .xlsx) are not accepted; if your bank offers one, download the CSV or PDF version instead. Fynora automatically detects common column-naming variants (e.g. "Debit (INR)" vs "Withdrawal Amt") across different banks. PDF support is built for digital, text-based statements; a scanned or photographed PDF may not read reliably, so use your bank\'s digital PDF or a CSV export instead.', description: "PDF and CSV only, including password-protected PDFs. Excel files (.xls or .xlsx) are not accepted, and a scanned PDF may not read reliably." },
  // Gate 2b (spec §3): sharing a statement in from the phone's mail app. Proven on iOS from Files and
  // on Android up to the share sheet; see the PR for what each platform was checked on.
  { slug: 'import-statement-from-email', category: 'Importing Statements', question: 'Can I import a statement straight from my email?', answer: 'Yes, in the Fynora app on your phone. Statement in your email? Open the email, tap the PDF, then Share → Fynora.' },
  { slug: 'import-statements-for-multiple-accounts', category: 'Importing Statements', question: 'Can I import statements for more than one account?', answer: 'Yes — each statement is matched to an existing account or used to create a new one automatically, based on details detected in the file.' },
  { slug: 'importing-the-same-statement-twice', category: 'Importing Statements', question: 'What if I import the same statement twice?', answer: 'Fynora checks for likely duplicate transactions during import and flags them for your review rather than silently double-counting them.', related: ['past-imports'] },

  { slug: 'add-account-manually', category: 'Managing Accounts', question: 'Can I add an account manually?', answer: 'Yes — from the Accounts page you can add a savings account, credit card, wallet, or investment account by hand, including an optional account holder name and masked account number.', description: "Yes. From the Accounts page you can add a savings account, credit card, wallet or investment account by hand, with an optional holder name and masked number." },
  { slug: 'how-account-numbers-are-displayed', category: 'Managing Accounts', question: 'How is my account number displayed?', answer: 'Account numbers are shown masked by default, with an eye icon to reveal them on your own screen — this is a display convenience, not an additional storage boundary.', description: "Account numbers are shown masked by default, with an eye icon to reveal them on your own screen. It is a display convenience, not a storage boundary." },

  { slug: 'edit-imported-transaction', category: 'Transactions', question: 'Can I edit a transaction after it\'s imported?', answer: 'Yes — every transaction supports full edit and delete, including its category, merchant, description, notes, tags, date, amount, and type.' },
  { slug: 'wrong-transaction-category', category: 'Transactions', question: 'Why was my transaction categorized incorrectly?', answer: 'Automatic categorization is always a starting suggestion, never a final decision. Correct it once and Fynora remembers your correction for that merchant going forward.', description: "Automatic categorization is a starting suggestion, never a final decision. Correct it once and Fynora remembers your correction for that merchant." },
  { slug: 'deleting-a-transaction-updates-dashboard', category: 'Transactions', question: 'What happens to my Dashboard when I delete a transaction?', answer: 'Deleting or editing a transaction automatically refreshes the Dashboard, Accounts, Budgets, Reports, Goals, and Insights — nothing is left showing stale numbers.', description: "Deleting or editing a transaction refreshes the Dashboard, Accounts, Budgets, Reports, Goals and Insights, so nothing is left showing stale numbers." },

  { slug: 'set-a-budget', category: 'Budgets', question: 'How do I set a budget?', answer: 'Go to Budgets, choose a category, and set a monthly limit. Your spend against that limit updates in real time as transactions come in.' },
  { slug: 'over-budget-warning', category: 'Budgets', question: 'Will I be warned before going over budget?', answer: 'Yes — the Budget Progress bar changes color as you approach and exceed your limit, both on the Budgets page and the Dashboard.' },

  { slug: 'track-a-savings-goal', category: 'Goals', question: 'How do I track a savings goal?', answer: 'Create a goal with a target amount and, optionally, a target date. Add contributions as you save, and Fynora tracks your percentage complete.', related: ['set-a-budget', 'spending-trends-over-months'] },

  { slug: 'track-investments', category: 'Investments', question: 'Can I track my investments alongside everyday spending?', answer: 'Yes — investment accounts contribute to your overall net worth figure alongside savings and credit card accounts.', related: ['add-account-manually'] },

  { slug: 'spending-trends-over-months', category: 'Reports', question: 'Can I see spending trends over multiple months?', answer: 'Yes — the Reports page and the Dashboard\'s Cash Flow chart both support viewing income/expense trends across a selectable range of months.', related: ['how-ai-insights-work', 'set-a-budget'] },

  { slug: 'plans-and-prices', category: 'Billing & Subscriptions', question: 'What plans does Fynora offer?', answer: 'Two: Free and Plus (₹249/month or ₹1,999/year). Listed prices include 18% GST. For a web subscription, your Fynora invoice shows the GST part separately; App Store and Google Play purchases are invoiced by Apple or Google.', description: "Fynora has two plans: Free and Plus (₹249/month or ₹1,999/year). Listed prices include 18% GST." },
  { slug: 'upgrade-downgrade-or-switch-billing-cycle', category: 'Billing & Subscriptions', question: 'How do I upgrade, downgrade, or switch billing cycle?', answer: "From Billing in the app — pick a plan and complete checkout through Razorpay. Upgrading takes effect immediately; a downgrade is scheduled for the end of your current billing period instead of switching you over right away, so you keep what you paid for.", description: "Pick a plan in Billing and check out through Razorpay. An upgrade applies immediately; a downgrade waits until the end of your current billing period." },
  { slug: 'cancel-subscription', category: 'Billing & Subscriptions', question: 'What happens if I cancel?', answer: "Your plan stays active until the end of the current billing period, then moves to Free automatically — no early cutoff, and no partial refund for time already paid for.", description: "Your plan stays active until the end of the current billing period, then moves to Free automatically. There is no partial refund for time already paid." },
  { slug: 'pause-subscription', category: 'Billing & Subscriptions', question: 'Can I pause my subscription instead of cancelling?', answer: 'Yes. Billing stops right away and Plus features turn off until you resume — your plan and payment setup stay in place, so resuming needs no new checkout.' },
  { slug: 'payment-invoice', category: 'Billing & Subscriptions', question: 'Can I get an invoice for a payment?', answer: "Yes — every successful payment in your Billing history has View and Download options for its invoice PDF. Pending, failed, and refunded payments don't have one, since nothing was actually charged.", description: "Yes. Every successful payment in your Billing history has View and Download options for its invoice PDF. Pending, failed and refunded payments have none." },
  { slug: 'card-details-storage', category: 'Billing & Subscriptions', question: 'Does Fynora store my card details?', answer: "No — Razorpay authorizes every charge directly with your bank. Fynora only ever sees your card's network and last four digits after a charge succeeds, for display on your Billing page.", description: "No. Razorpay authorizes every charge directly with your bank. Fynora only sees your card's network and last four digits, for display on your Billing page." },
  { slug: 'app-store-or-google-play-subscription', category: 'Billing & Subscriptions', question: 'I subscribed through the App Store or Play Store — can I manage it from Fynora?', answer: "No. A subscription bought that way is managed by Apple or Google, not Razorpay — change your plan, update payment, or cancel from your device's own subscription settings instead. Billing shows it as read-only.", description: "No. A subscription bought through the App Store or Google Play is managed by Apple or Google. Change or cancel it from your device's subscription settings." },

  { slug: 'how-ai-insights-work', category: 'AI Insights', question: 'How does AI Insights work?', answer: 'Insights are generated from your real transaction history — biggest spending category, month-over-month category movers, and top merchants — plus the occasional grounded recommendation, like suggesting a budget for a category trending up with none set.', description: "Insights come from your own transaction history: your biggest spending category, month-over-month category movers, top merchants and grounded recommendations.", related: ['spending-trends-over-months', 'wrong-transaction-category'] },

  { slug: 'past-imports', category: 'Statement History', question: 'Where can I see past imports?', answer: 'Statement History groups every import by the account it belongs to, and lets you view its transactions, download the original file, re-import it, or delete it.', related: ['importing-the-same-statement-twice'] },
  { slug: 'delete-account-with-statements', category: 'Statement History', question: 'What happens if I delete an account with statements?', answer: 'A deleted account remains visible in Statement History for 7 days from the deletion date before it disappears, in case you need to reference it.', related: ['re-import-into-deleted-account'] },

  { slug: 'zero-transactions-found', category: 'Troubleshooting', question: 'My statement showed "0 transactions found" — what happened?', answer: 'This usually means the file\'s header row uses column names Fynora didn\'t recognize. Try re-exporting from your bank with default column names, or contact support with the file.', description: "Usually the file's header row uses column names Fynora didn't recognize. Re-export from your bank with default column names, or contact support with the file.", related: ['supported-file-formats', 'contact-support'] },
  { slug: 're-import-into-deleted-account', category: 'Troubleshooting', question: 'I can\'t re-import a statement into a deleted account.', answer: 'That\'s expected — a deleted account can\'t receive new statements. Restore isn\'t currently self-serve; contact support if you need this reversed within the 7-day window.', description: "A deleted account can't receive new statements. Restore isn't self-serve; contact support if you need it reversed within the 7-day window.", related: ['delete-account-with-statements'] },

  { slug: 'contact-support', category: 'Contact Support', question: 'How do I reach support?', answer: `Already a Fynora customer? Log in and open Support from the Help menu (top right) to file a ticket — you can attach a screenshot or file, and track its status. Not signed in yet, or reaching out about something else? Email ${SUPPORT_EMAIL} — for import problems, attaching the file (with sensitive numbers redacted if you prefer) speeds up a diagnosis.`, description: `Customers can log in and open Support from the Help menu to file and track a ticket. Not signed in yet, or something else? Email ${SUPPORT_EMAIL}.` },
  { slug: 'support-ticket-status', category: 'Contact Support', question: 'Can I check the status of a support ticket I filed?', answer: 'Yes — "My Tickets," reachable from the Help menu once you\'re logged in, lists every ticket you\'ve filed along with its current status (Open, In Progress, Resolved, or Closed).', description: "Yes. My Tickets, in the Help menu once you're logged in, lists every ticket you've filed with its status: Open, In Progress, Resolved or Closed." },
  { slug: 'support-ticket-vs-feedback', category: 'Contact Support', question: 'What\'s the difference between a support ticket and feedback?', answer: 'A support ticket is for something specific that\'s broken or wrong with your account — it gets a status and a response. "Send feedback" (also in the Help menu) is for a general bug report, feature request, or idea — it\'s read, but doesn\'t get an individual reply or a status you can track.', description: "A support ticket is for something broken in your account and gets a status and a response. Feedback is for bug reports and ideas: read, but not answered." },
];

export const HELP_CATEGORIES = Array.from(new Set(HELP_ARTICLES.map((a) => a.category)));

/** The route App.tsx renders every article at. */
export const HELP_ARTICLE_ROUTE = '/help/:slug';

/** An article's address: '/help/<slug>'. */
export function helpArticlePath(article: Pick<HelpArticle, 'slug'>): string {
  return `/help/${article.slug}`;
}

/** The article at a slug, or undefined. Exact match: slugs are lower-case by rule. */
export function helpArticleBySlug(slug: string | undefined): HelpArticle | undefined {
  return slug === undefined ? undefined : HELP_ARTICLES.find((a) => a.slug === slug);
}

/** The article an address names ('/help/<slug>', trailing slash allowed), or undefined. */
export function helpArticleForPath(pathname: string): HelpArticle | undefined {
  const match = /^\/help\/([^/]+)\/?$/.exec(pathname);
  return match ? helpArticleBySlug(match[1]) : undefined;
}

/** The article's meta description: its own, or its answer when that already fits a result. */
export function helpArticleDescription(article: HelpArticle): string {
  return article.description ?? article.answer;
}

/**
 * What to read next: the article's category first, then its own `related` picks, never itself,
 * never twice, at most `limit`.
 */
export function relatedHelpArticles(article: HelpArticle, limit = 5): HelpArticle[] {
  const sameCategory = HELP_ARTICLES.filter((a) => a.category === article.category && a.slug !== article.slug);
  const picked = (article.related ?? []).map((slug) => {
    const found = helpArticleBySlug(slug);
    if (!found) throw new Error(`helpArticles: ${article.slug} lists an unknown related slug ${slug}`);
    return found;
  });
  const seen = new Set<string>([article.slug]);
  const out: HelpArticle[] = [];
  for (const a of [...sameCategory, ...picked]) {
    if (seen.has(a.slug)) continue;
    seen.add(a.slug);
    out.push(a);
  }
  return out.slice(0, limit);
}
