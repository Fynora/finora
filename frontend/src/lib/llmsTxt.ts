import { SITE_ORIGIN } from './siteUrl';
import { SUPPORT_EMAIL } from './contact';
import { PLAY_STORE_URL } from './openInApp';
import { PRICING_CARDS, type Plan } from '../pages/landing/plans';
import {
  askFyn,
  everywhere,
  faq,
  finalCta,
  footer,
  hero,
  importSection,
  learning,
  security,
  trust,
} from '../pages/landing/landing-config';
import { HELP_ARTICLES, helpArticleBySlug, helpArticleDescription, helpArticlePath } from '../pages/helpArticles';

/**
 * /llms.txt and /llms-full.txt: a plain-text description of Fynora for AI assistants and answer
 * engines (llmstxt.org). AI tools repeat what they read here, so it must say nothing the site does
 * not already say.
 *
 * How that is kept true:
 * - Almost every sentence is read from the site's own reviewed copy, never typed a second time:
 *   the homepage's sentences and FAQ (landing-config.ts, held to the code by
 *   landing-claims.test.tsx), the plans and prices (plans.ts PRICING_CARDS, the same array the
 *   pricing cards render), the Help answers (helpArticles.ts) and the Play Store address
 *   (openInApp.ts). If one of those changes, these files follow at the next build.
 * - The few sentences written here are in STATED_CLAIMS below, each with the page that states it
 *   and the words that page uses. llmsTxt.test.tsx renders that page and fails if the words are gone.
 * - Every address on app.fynora.net is a page in the sitemap; the test fails otherwise.
 *
 * Written into dist/ by scripts/prerender.mjs, so both are static files at the site root. Not in
 * the sitemap: they are not pages for people, and robots.txt already lets every crawler fetch them.
 */

/** A sentence written here rather than read from the site's copy, and where the site says it. */
export interface StatedClaim {
  text: string;
  /** The public page that states it. llmsTxt.test.tsx renders it. */
  page: 'privacy' | 'footer';
  /** Words that page renders, which this sentence restates. */
  evidence: string;
}

export const STATED_CLAIMS = {
  notABank: {
    text: 'Fynora is not a bank and does not give investment advice.',
    page: 'footer',
    evidence: 'Not a bank. Not investment advice.',
  },
  operator: {
    text: "Fynora is operated by Fynora Technovation LLP, registered in Jhansi, Uttar Pradesh, India. Under India's Digital Personal Data Protection (DPDP) Act, Fynora Technovation LLP is the Data Fiduciary for your personal data.",
    page: 'privacy',
    evidence: 'Fynora is operated by Fynora Technovation LLP, registered at 463, Sita Ram Compound, Chaman Ganj, Sipri Bazaar, Jhansi, Uttar Pradesh, 284003, India. Under the DPDP Act, Fynora Technovation LLP is the "Data Fiduciary"',
  },
  hosting: {
    text: "Fynora's application data is hosted and processed on servers outside India, in the United States.",
    page: 'privacy',
    evidence: "Fynora's application data is currently hosted and processed on servers located outside India (in the United States).",
  },
  importNotAi: {
    text: "Statements are read by Fynora's own rule-based extraction. Importing a statement does not send it to a third-party AI service.",
    page: 'privacy',
    evidence: "statements are processed entirely automatically by Fynora's own rule-based extraction logic — Fynora does not send your statement or its contents to third-party AI services",
  },
  noAdTracking: {
    text: 'Fynora does not use third-party advertising or tracking cookies.',
    page: 'privacy',
    evidence: 'We do not currently use third-party advertising or tracking cookies.',
  },
  adults: {
    text: 'Fynora is for people aged 18 or over.',
    page: 'privacy',
    evidence: 'Fynora is intended for users 18 years of age or older',
  },
} satisfies Record<string, StatedClaim>;

/** An absolute address on the indexed host. */
export function siteUrl(route: string): string {
  return route === '/' ? `${SITE_ORIGIN}/` : `${SITE_ORIGIN}${route}`;
}

/** A homepage FAQ item as one line, question first: its answer ("Yes, both.") needs the question. */
function faqLine(question: string): string {
  const item = faq.items.find(([q]) => q === question);
  if (!item) throw new Error(`llmsTxt: the homepage FAQ has no question ${JSON.stringify(question)}`);
  return `**${item[0]}** ${item[1]}`;
}

/** A Help answer as one line, question first. */
function helpLine(slug: string): string {
  const article = helpArticleBySlug(slug);
  if (!article) throw new Error(`llmsTxt: no Help article ${slug}`);
  return `**${article.question}** ${article.answer}`;
}

/** The footer's "Made in India." line, by what it says rather than where it sits in the list. */
function madeInIndia(): string {
  const line = footer.principles.find((p) => /\bIndia\b/.test(p));
  if (!line) throw new Error('llmsTxt: the footer no longer says where Fynora is made');
  return line;
}

/** A plan's heading line with its prices, then its blurb and features, all from its own fields. */
export function planLines(plan: Plan): string[] {
  if (plan.availability !== 'available' || plan.price === null) {
    throw new Error(`llmsTxt: plan ${plan.id} is not on sale and must not be listed with a price`);
  }
  const price = [`${plan.price}${plan.cadence ?? ''}`, plan.secondaryPriceNote].filter(Boolean).join(' ');
  const gst = plan.priceIncludesGst ? ', including GST' : '';
  return [`### ${plan.name}: ${price}${gst}`, '', plan.blurb, '', ...plan.features.map((feature) => `- ${feature}`)];
}

// The public pages worth an assistant's time, by name only: a description here would be a second
// copy of each page's own summary.
export const KEY_PAGES: readonly (readonly [string, string])[] = [
  ['Home', '/'],
  ['About Fynora', '/about'],
  ['Help Center', '/help'],
  ['Trust & Security', '/trust'],
  ['Your data: export and ownership', '/your-data'],
  ['Privacy Policy', '/privacy'],
  ['Terms & Conditions', '/terms'],
  ['Refund Policy', '/refund-policy'],
  ['Contact', '/contact'],
];

const lowerFirst = (s: string) => s.charAt(0).toLowerCase() + s.slice(1);

export function llmsTxt(): string {
  const lines: string[] = [
    '# Fynora',
    '',
    `> ${hero.blurb}`,
    '',
    `${importSection.blurb} ${everywhere.nativeNote}`,
    '',
    `${STATED_CLAIMS.notABank.text} ${madeInIndia()}`,
    '',
    '## How importing works',
    '',
    // The Help answer rather than the homepage FAQ's: it also says what does not work (a scanned
    // PDF), which an assistant answering "can Fynora read my file?" needs.
    `- ${helpLine('supported-file-formats')}`,
    `- ${helpLine('import-statement-from-email')}`,
    ...importSection.proofs.map((p) => `- ${p.title}: ${p.body}`),
    `- ${faqLine('Can I import several bank accounts?')}`,
    `- ${faqLine("What happens if my statement can't be read?")}`,
    '',
    '## How categorisation works',
    '',
    `- ${learning.blurb} ${learning.footnote}`,
    `- ${faqLine('How does categorization get better?')}`,
    `- ${faqLine("Does Fynora learn from other people's data?")}`,
    '',
    '## Privacy and security',
    '',
    `- ${security.blurb}`,
    `- ${STATED_CLAIMS.importNotAi.text}`,
    `- ${faqLine('Does Fynora use AI on my data?')}`,
    `- ${askFyn.blurb}`,
    `- Fynora will never: ${trust.never.map(lowerFirst).join('; ')}.`,
    `- Why not: ${trust.whyLead} ${trust.whyBody}`,
    `- ${STATED_CLAIMS.noAdTracking.text}`,
    `- ${faqLine('Can I export or delete my data?')}`,
    `- ${STATED_CLAIMS.operator.text}`,
    `- ${STATED_CLAIMS.hosting.text}`,
    `- ${STATED_CLAIMS.adults.text}`,
    '',
    '## Plans and prices',
    '',
    `${finalCta.footnote} Prices are in Indian rupees.`,
    '',
    ...PRICING_CARDS.flatMap((plan) => [...planLines(plan), '']),
    '## Key pages',
    '',
    ...KEY_PAGES.map(([name, route]) => `- [${name}](${siteUrl(route)})`),
    `- [Fynora for Android on Google Play](${PLAY_STORE_URL})`,
    '',
    '## Help answers',
    '',
    ...HELP_ARTICLES.map((a) => `- [${a.question}](${siteUrl(helpArticlePath(a))}): ${helpArticleDescription(a)}`),
    '',
    '## Contact',
    '',
    `- Support email: ${SUPPORT_EMAIL}`,
    `- [Contact page](${siteUrl('/contact')})`,
    '',
    '## Optional',
    '',
    `- [Every Help answer in full, and the homepage questions](${siteUrl('/llms-full.txt')})`,
    '',
  ];
  return lines.join('\n');
}

/** llms.txt, then every Help answer and every homepage question answered in full. */
export function llmsFullTxt(): string {
  const lines: string[] = [
    llmsTxt().trimEnd(),
    '',
    '## Help answers in full',
    '',
    ...HELP_ARTICLES.flatMap((a) => [`### ${a.question}`, '', a.answer, '', `Source: ${siteUrl(helpArticlePath(a))}`, '']),
    '## Homepage questions in full',
    '',
    ...faq.items.flatMap(([question, answer]) => [`### ${question}`, '', answer, '', `Source: ${siteUrl('/')}`, '']),
  ];
  return lines.join('\n');
}
