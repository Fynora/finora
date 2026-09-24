/**
 * Every word on the landing page, in one place.
 *
 * The point is that changing marketing copy should never mean opening component logic. A section
 * file decides how something looks; this file decides what it says. That split is what makes
 * localization, A/B variants and a non-engineer editing a headline all tractable -- and it means a
 * claim can be reviewed by reading one file instead of seventeen.
 *
 * Plans live separately in ./plans, because they carry a real invariant (only an available plan
 * may have a price) that the claims test enforces. Copy has no invariant; plans do.
 *
 * THE ONE RULE FOR EDITING THIS FILE: every sentence here is a public claim about a financial
 * product. Before changing one, run the four questions in
 * docs/project-management/standards/marketing-claims-checklist.md. `landing-claims.test.tsx` catches the mistakes
 * that can be caught mechanically; it cannot catch a sentence that is merely untrue.
 *
 * THE LEAD STORY is "statements read correctly": Fynora checks what it read against what the
 * statement itself says, holds back what it cannot verify, and lets the person confirm before
 * anything is saved. Every proof point for it below names the code that backs it in a comment, so
 * a reviewer can check the claim rather than trust it. Two things are deliberately NOT marketed:
 * investment tracking (a small side feature) and a bank feed through Account Aggregator (an FIU
 * must itself be regulated by RBI/SEBI/IRDAI/PFRDA, which Fynora is not -- do not promise it).
 */

export const hero = {
  headline: 'Upload a bank statement.',
  headlineAccent: 'See where every rupee went.',
  // "from Indian banks and credit cards" names no bank on purpose: the registry recognises many, but
  // recognised is not the same as "every layout parses", and per-bank coverage is not measured here.
  blurb: 'Fynora reads PDF and CSV statements from Indian banks and credit cards, checks its own work, and sorts every transaction.',
  primaryCta: 'Import your first statement',
  secondaryCta: 'See how it works',
  // Each tick is checkable against code: the validators and TrustPredicate (imports/), the
  // confirm-before-save import session (ImportService.confirmSession), and the Free plan (plans.ts).
  assurances: [
    'Checks its reading against your statement',
    "Holds back what it can't read with confidence",
    'You confirm before anything is saved',
    'Free to start',
  ],
};

/**
 * The strip directly under the hero. The biggest reason a visitor does not sign up is not price or
 * features, it is "I am about to upload my bank statement to a company I have never heard of", so
 * the answer to that sits above the fold rather than in section ten.
 */
export const trustStrip = [
  // Fynora never requests net-banking credentials (see the FAQ); the import path stores none.
  'Never asks for your bank password',
  // StatementImportController.downloadFile, scoped to the signed-in user.
  'Your original file stays yours to download',
  // DataPane "Export My Data" and account delete (POST /account/delete).
  'Export or delete everything, any time',
];

/**
 * Copy for the cinematic hero's score ring, intelligence-scan checklist, and floating data
 * badges. Same rule as the rest of this file: these are illustrative figures, kept internally
 * consistent with the numbers DashboardMock already shows elsewhere on the page (see
 * DashboardMock.tsx's own note on why that matters).
 */
export const heroScore = {
  label: 'Financial Health',
  value: 84,
  delta: '+6 this month',
};

export const heroIntelligence = {
  heading: 'Analyzing your finances…',
  steps: [
    'Spending patterns detected',
    'Subscription detected',
    'Saving opportunity found',
    'Financial health calculated',
  ],
};

export const heroBadges = [
  { label: '+₹1,24,500 Salary' },
  { label: 'Budget on track' },
  { label: 'Goal 72%' },
  { label: 'AI Insight ✨' },
  { label: 'Savings improved' },
];

export const problem = {
  eyebrow: 'Why this is hard',
  title: "Managing money shouldn't feel like work.",
  chores: [
    'Downloading statements. Every month.',
    'Scrolling hundreds of rows for one charge.',
    'Guessing where the money actually went.',
    'A spreadsheet that is stale by Tuesday.',
  ],
  closer: 'The data already exists.',
  closerMuted: 'Understanding it is the hard part.',
};

/**
 * "Read correctly" is the lead story, so this section carries the proof rather than just the list of
 * accepted files. Evidence for each card:
 *   - checks its own work: BalanceChainValidator (running balance), the printed-vs-parsed count check
 *     in TrustPredicate, StatementTotalsValidator, CreditCardStatementTotalsValidator.
 *   - holds back what it cannot verify: TrustPredicate lists the conditions (a count mismatch, a
 *     confirmed dropped row, an impossible period, a systematic balance-chain break, column
 *     ambiguity, an unsure header, a corrupted description) under which a statement is held for
 *     review by HeldStatementService instead of reaching the ledger.
 *   - confirm before saved: ImportController stages, then /csv/confirm or /pdf/confirm-multi.
 *     DuplicateDetector flags duplicate rows; Statement History shows "Duplicates flagged".
 *   - original file: Statement History "Download Original File".
 * Scanned (image-only) PDFs are NOT claimed: OCR exists but its accuracy on real scans is unmeasured.
 */
export const importSection = {
  eyebrow: 'Read correctly',
  title: 'Upload once.',
  titleLine2: 'We check it before you trust it.',
  blurb:
    'Fynora reads the statement, finds the accounts and sorts the transactions. Then, where the statement prints counts or balances, it compares its reading with them, so a problem shows up before it reaches your numbers.',
  supported: ['PDF', 'CSV', 'Password-protected', 'Multiple accounts', 'Composite statements'],
  proofs: [
    {
      title: 'It checks its own work',
      body: "When the statement prints transaction counts or running balances, it compares what it read against them.",
    },
    {
      title: "It holds back what it can't verify",
      body: 'If a transaction looks wrong or missing, the statement is held for review instead of being imported.',
    },
    {
      title: 'You confirm before it is saved',
      body: 'You see the rows Fynora read and confirm them. Rows that look like duplicates are flagged for you to keep or skip.',
    },
    {
      title: 'Your original file stays yours',
      body: 'The original is kept in Statement History, and you can download it again.',
    },
  ],
  // No credit-card line (owner decision 2026-09-21): a card statement prints its due date, limit and
  // total due by default, so reading them is not a differentiator worth a sentence. The extractors
  // exist (CreditCardSummaryExtractor, PaymentDueDateGridExtractor, CreditLimitGridExtractor).
};

export const learning = {
  eyebrow: 'It learns from you',
  title: 'Correct it once.',
  titleLine2: 'It remembers.',
  blurb: 'Fix a category once and Fynora applies your choice to that merchant on your future imports.',
  footnote: "Nothing is filed quietly. Anything it isn't sure about waits for you.",
};

export const beforeAfter = {
  eyebrow: 'The difference',
  title: 'Same statement. Different month.',
  blurb: 'Nothing about your bank changes. What changes is how much of your Sunday it costs.',
  before: [
    'Download the statement',
    'Scroll hundreds of rows',
    'Categorize by hand',
    'Paste into a spreadsheet',
    'Still not certain',
  ],
  after: [
    'Upload the statement',
    'Organized automatically',
    'Categorized, and it learns',
    'A dashboard, already current',
    'You actually know',
  ],
  beforeVerdict: 'Confusion.',
  afterVerdict: 'Confidence.',
};

/**
 * What Fynora does, as a grid. Each card is written from what the screen or service actually does;
 * the evidence is beside it. Only the plan-gated ones carry a plan tag (a "Free" tag on seven of
 * nine cards is noise). Not on this list on purpose: investment tracking, referrals, Journey and
 * Wrapped (both thin today), and any bank feed.
 *   - categorised, "Why this category?", custom categories: Ledger, TransactionExplanationService,
 *     CategoryCreateEditPanel.
 *   - dashboard: Dashboard.tsx headings "Financial Health Score" ("No change vs your last recorded
 *     score"), "Cash Flow Overview", "Accounts Overview", "Budget Progress", "Goals".
 *   - budgets and goals: Budgets.tsx ("Budgets on Track", "Days Left"), Goals.tsx.
 *   - recurring: RecurringController; Insights "Recurring Payments & Subscriptions"; the
 *     Dashboard's upcoming recurring payments; RecurringDto.nextEstimate.
 *   - reports: Reports.tsx "Category Breakdown", Income / Expense / Net.
 *   - deeper reports: AnalyticsController.requireAdvancedReports (ADVANCED_REPORTS, Plus).
 *   - what changed: Insights "Category Movers vs. Recent Average".
 *   - Financial Memory: FinancialMemory.tsx ("Merchants identified", "Rules learned", "Manual
 *     corrections", "Completeness").
 *   - Not on this list: Gmail receipts. Gmail sync is dropped for v1 (owner, 2026-09-21), so no
 *     public surface (this page, the plans, Help) may describe it; tests enforce that.
 */
export const capabilities = {
  eyebrow: 'What Fynora does',
  title: 'Everything your statements',
  titleLine2: 'can tell you.',
  blurb:
    'Import a statement and Fynora turns it into categories, budgets, goals, recurring payments and reports, all in one place.',
  items: [
    {
      title: 'Every transaction sorted',
      body: 'Automatic categories that learn from you. Each one has a "Why this category?", and you can create your own.',
      plan: null as string | null,
    },
    {
      title: 'A dashboard that explains itself',
      body: 'A Financial Health Score and how it changed, plus cash flow, accounts, budgets and goals on one page.',
      plan: null,
    },
    {
      title: 'Budgets and goals',
      body: 'Set a monthly limit per category and see whether you are on track. Track your goals and what you put toward them.',
      plan: null,
    },
    {
      title: 'Recurring payments and subscriptions',
      body: 'Fynora spots repeat charges and estimates when the next one is due.',
      plan: null,
    },
    {
      title: 'Reports',
      body: 'See income, expenses and net, and where the money went by category.',
      plan: null,
    },
    {
      title: 'Deeper reports',
      body: 'Spend trend, top merchants and categories, lifestyle inflation and a multi-year comparison.',
      plan: 'Plus',
    },
    {
      title: 'What changed',
      body: 'Insights show which categories moved compared with your recent average.',
      plan: null,
    },
    {
      title: 'Financial Memory',
      body: 'See what Fynora has learned: the merchants it recognises, the rules and corrections it remembers, and how complete your history is.',
      plan: null,
    },
  ],
  mockCaption: 'Sample data, for illustration.',
};

/**
 * Ask Fyn is live. The example prompts are the four real ones in FynWidget.tsx (one per chat tool:
 * GET_BALANCE, GET_RECENT_TRANSACTIONS_SUMMARY, GET_SPEND_BY_CATEGORY, GET_BUDGET_STATUS), so a
 * tap on the product always has a genuine capability behind it. "Can't change anything" matches the
 * read-only tool set and the privacy policy. Where a question goes (Anthropic's Claude) is NOT
 * repeated in this section (owner decision 2026-09-21); it stays in the FAQ answer "Does Fynora use
 * AI on my data?" and in the privacy policy, and landing-claims.test.tsx fails if the FAQ loses it.
 * The Free daily limit is an environment variable (FYN_FREE_DAILY_QUESTION_LIMIT, default 3), so the
 * page says "small daily limit" rather than a number that could drift from production.
 */
export const askFyn = {
  eyebrow: 'Ask Fyn',
  title: 'Ask about your own money.',
  blurb:
    "Ask Fyn about your balance, recent spending, spending in a category, or how a budget is going. It can read your numbers. It can't change anything in your account, and it is not a financial advisor.",
  examples: [
    "What's my balance?",
    'What did I spend this month?',
    'How much did I spend on Dining?',
    'How are my budgets doing?',
  ],
  points: [
    'Attach a screenshot and ask about it.',
    'Free plans have a small daily limit on questions. Plus has no daily limit, within fair use.',
  ],
};

export const trust = {
  eyebrow: 'Complete transparency',
  title: 'What Fynora Will Never Do.',
  never: [
    'Sell your financial data',
    'Ask for your net-banking password',
    'Push loans or credit cards at you',
    'Recommend a product because someone paid us',
    'Hide how a decision was made',
  ],
  // Specific commitments, each one backed by a code path, instead of the old absolute "Your data
  // stays yours". Cross-user learning exists (SharedCorpusService), so "never shown to other users"
  // is the promise that is both true and keepable; see the FAQ for exactly what is shared.
  always: [
    'You can see why a category was chosen',
    'You confirm imports before they are saved',
    'Your original file stays yours to download',
    'If an import fails, staff open the file through a permissioned review queue, and each download is logged',
    'Export everything or delete your account, any time',
    'Your transactions and statements are never shown to other users',
  ],
  whyTitle: 'Why?',
  whyLead: "Because we don't make money selling financial products.",
  whyBody:
    'No commissions, no referral fees, no sponsored placements. Our success depends entirely on building software people trust, which only works if the advice was never for sale.',
};

/**
 * Deliberately plain-language. Every step was verified against the code before it was written --
 * see Security.tsx's own note for what is and is not claimed. "Private storage" is the honest
 * label: files are content-addressed and integrity-checked, which is a real property, whereas
 * "encrypted storage" would be taking credit for a platform default the application does not
 * implement.
 */
export const security = {
  eyebrow: 'Security & privacy',
  title: 'You never hand us your bank login.',
  blurb:
    'Uploading a statement is all Fynora needs. It never asks for your bank login.',
  chain: [
    { title: 'You', body: 'Your device, your statement.' },
    { title: 'HTTPS', body: 'Encrypted the whole way across.' },
    { title: 'Fynora', body: 'Checks it is really you, every request.' },
    { title: 'Private storage', body: 'Your files, fingerprinted and verified.' },
    { title: 'Your data only', body: 'Every query bound to your account.' },
  ],
  // The sessions sentence: SecurityPane "Active Sessions" / "Sign out this device".
  footnote:
    'Passwords are hashed and never stored in readable form. Not even we can see them. You can see where you are signed in and sign out any device.',
  ownership: 'Your financial data belongs to you.',
  ownershipAccent: 'Fynora exists to help you understand it, not to profit from it.',
};

export const everywhere = {
  eyebrow: 'Anywhere',
  title: 'One picture. Every device.',
  blurb: 'The same account, the same numbers, wherever you happen to be looking.',
  moments: [
    { when: 'Morning', what: "Import last month's statement over coffee." },
    { when: 'Afternoon', what: "Check what's left in the food budget before ordering lunch." },
    { when: 'Evening', what: 'Nudge a goal after the salary lands. Same numbers, same account.' },
  ],
  // The web app is responsive and runs anywhere today. The native apps are built and on no store.
  // Update this note and the section wording together, never just the note.
  nativeStatus: 'In development',
  nativeNote:
    'Fynora runs in any browser today. Native iOS and Android apps are being built and are not on the app stores yet.',
};

export const useCases = {
  eyebrow: 'Made for everyone',
  title: 'For every stage of your life.',
  blurb: 'One platform. Endless clarity.',
  audiences: [
    { title: 'Working professionals', body: 'Salary in, spending out, savings visible, without keeping a spreadsheet alive.' },
    { title: 'Families', body: 'Household money in one place, so it can be discussed instead of guessed at.' },
    { title: 'Freelancers', body: 'Irregular income made legible, and the cash flow that follows it.' },
    { title: 'Students', body: 'Build the habit early, while the numbers are still small enough to learn on.' },
  ],
};

export const faq = {
  eyebrow: 'Questions',
  title: 'Anything else?',
  items: [
    [
      'Is my financial data secure?',
      'Passwords are hashed with bcrypt and never stored in readable form, sessions use short-lived access tokens with rotating refresh tokens, and every request to a protected endpoint is verified server-side. Traffic is encrypted in transit over HTTPS, and uploaded statements are fingerprinted so a corrupted or swapped file is detected rather than served. Your data is never sold.',
    ],
    [
      'Does Fynora connect to my bank account?',
      'Not by default, and never with your net-banking password. Fynora never asks for your net-banking credentials, and nothing in the core product requires a bank connection: it works from the statements you upload yourself.',
    ],
    [
      'Which files can I upload?',
      'PDF and CSV. Password-protected PDFs work too: enter the password during upload and Fynora opens the file to read it. The password travels in the request body, never in a URL, and is not stored afterwards, so a later re-import will ask again.',
    ],
    [
      'Can I import several bank accounts?',
      'Yes. Savings accounts, credit cards and wallets. Each statement is matched to the right account automatically, and a single statement covering several accounts is split into them rather than flattened into one. Free plans hold up to 2 accounts; Plus has no limit.',
    ],
    [
      "What happens if my statement can't be read?",
      "Where the statement prints its own transaction counts and balances, Fynora checks its reading against them. If something looks wrong or missing, the statement is held for review instead of being imported. Someone at Fynora may open the original file to fix it; that needs a specific permission and each download is logged.",
    ],
    [
      'How does categorization get better?',
      'Correct a transaction once and Fynora remembers that merchant, applying your preference on future imports. It records how confident each suggestion was and which signals matched, and anything below your confidence threshold waits for you rather than being filed quietly. It is always a suggestion, never a decision you cannot see or change.',
    ],
    [
      "Does Fynora learn from other people's data?",
      'Only for payees Fynora identifies as businesses, and only after several separate people categorise the same payee the same way. What is shared is the payee ID, the category and a count. Your amounts, transaction dates and statements are never part of it, and your own corrections come first for your own account.',
    ],
    [
      'Does Fynora use AI on my data?',
      "Ask Fyn does, only when you use it, and it sends your question and the data needed to answer it to Anthropic's Claude. Importing a statement does not send it to any AI service. If you add a transaction by hand without picking a category, the description you typed may be sent to Anthropic's Claude to help pick one.",
    ],
    [
      'Can I export or delete my data?',
      'Yes, both, and both are self-service from Settings. Export downloads a ZIP of everything in your account (accounts, transactions, budgets, goals and your original statement files), with a manifest explaining what is included. Deletion is permanent and irreversible: it is confirmed with your password and a phone OTP, and there is no way to cancel a request once submitted.',
    ],
  ] as [string, string][],
};

export const finalCta = {
  title: 'Your next bank statement',
  titleLine2: "doesn't have to be another PDF.",
  blurb: 'Let Fynora turn it into clarity.',
  primary: 'Start free',
  footnote: 'Free forever for the core product. No credit card required.',
};

export const footer = {
  mission: 'Helping people understand their finances with clarity, transparency and confidence.',
  principles: ['Built with transparency.', 'Designed for trust.', 'Made in India.'],
  tagline: 'Understand every rupee. Not just your balance.',
  instagram: 'https://www.instagram.com/fynora_technovation/',
  instagramHandle: '@fynora_technovation',
};
