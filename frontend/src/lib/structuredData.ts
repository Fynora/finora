import { SITE_ORIGIN, canonicalUrl } from './siteUrl';
import { GRIEVANCE_EMAIL, LEGAL_NAME, REGISTERED_OFFICE, SUPPORT_EMAIL } from './contact';
import { PLAY_STORE_URL } from './openInApp';
import { PRICING_CARDS, type Plan } from '../pages/landing/plans';
import { faq, footer, hero } from '../pages/landing/landing-config';
import { helpArticleForPath } from '../pages/helpArticles';

/**
 * Structured data (schema.org JSON-LD) for the prerendered public pages. The site had none at all,
 * in the static HTML or after the bundle ran.
 *
 * Every value here is read from the page's own source of truth, never typed in a second time: the
 * offers come from plans.ts (the same array the pricing cards render), the FAQs from
 * landing-config.ts and helpArticles.ts, the product description from the hero, the Instagram link
 * from the footer. If a price or an answer changes on the page, the markup follows. That is the
 * only safe way to publish machine-readable claims next to a landing page whose rule is that
 * every sentence is a public claim.
 *
 * Emitted by scripts/prerender.mjs through scripts/ssr-entry.tsx, so it is in the HTML a crawler
 * reads without JavaScript. The CSP's script-src does not apply: a JSON-LD block is data, not a
 * script the browser runs.
 *
 * Not included, deliberately:
 * - Android in SoftwareApplication's operatingSystem. The Android app is on Google Play (the
 *   Organization's sameAs links its listing, and landing-config.ts says so), but this block's
 *   offers are the web prices from plans.ts, and a store purchase is priced and invoiced by Google.
 *   Listing Android here would attach the web prices to it.
 * - A founder, founding date or company registration number. None is stated anywhere on the site,
 *   and structured data must not be the first place a fact appears.
 */

type JsonLd = Record<string, unknown>;

const ORGANIZATION_ID = `${SITE_ORIGIN}/#organization`;
const WEBSITE_ID = `${SITE_ORIGIN}/#website`;

/**
 * The company, as Contact, Terms and Privacy describe it: legal name and registered office from
 * contact.ts (the constants those pages render), the two public mailboxes, and the official profiles
 * the site itself links: Instagram from the footer and the Android app's Google Play listing.
 */
export function organization(): JsonLd {
  return {
    '@context': 'https://schema.org',
    '@type': 'Organization',
    '@id': ORGANIZATION_ID,
    name: 'Fynora',
    legalName: LEGAL_NAME,
    url: `${SITE_ORIGIN}/`,
    logo: `${SITE_ORIGIN}/favicon.png`,
    email: SUPPORT_EMAIL,
    address: {
      '@type': 'PostalAddress',
      streetAddress: REGISTERED_OFFICE.streetAddress,
      addressLocality: REGISTERED_OFFICE.addressLocality,
      addressRegion: REGISTERED_OFFICE.addressRegion,
      postalCode: REGISTERED_OFFICE.postalCode,
      addressCountry: REGISTERED_OFFICE.addressCountry,
    },
    contactPoint: [
      { '@type': 'ContactPoint', contactType: 'customer support', email: SUPPORT_EMAIL, url: `${SITE_ORIGIN}/contact` },
      // The DPDP Act grievance officer's mailbox, which Contact and Privacy publish for
      // data-protection complaints.
      { '@type': 'ContactPoint', contactType: 'data protection grievances', email: GRIEVANCE_EMAIL, url: `${SITE_ORIGIN}/contact` },
    ],
    sameAs: [footer.instagram, PLAY_STORE_URL],
  };
}

/**
 * A page's own WebPage block, with the date its "Last updated: <Month> <year>." line gives, as
 * ISO 8601 year and month ("2026-10"). Only month precision is published on the page, so no day is
 * invented for this.
 */
export function webPage(route: string, title: string, dateModified: string): JsonLd {
  if (!/^\d{4}-(0[1-9]|1[0-2])$/.test(dateModified)) {
    throw new Error(`structuredData: ${route}'s dateModified ${JSON.stringify(dateModified)} is not YYYY-MM`);
  }
  return {
    '@context': 'https://schema.org',
    '@type': 'WebPage',
    '@id': `${canonicalUrl(route)}#webpage`,
    url: canonicalUrl(route),
    name: title,
    dateModified,
    isPartOf: { '@id': WEBSITE_ID },
    publisher: { '@id': ORGANIZATION_ID },
  };
}

export function website(): JsonLd {
  return {
    '@context': 'https://schema.org',
    '@type': 'WebSite',
    '@id': WEBSITE_ID,
    name: 'Fynora',
    url: `${SITE_ORIGIN}/`,
    publisher: { '@id': ORGANIZATION_ID },
  };
}

/** The numeric part of a plan's sticker price: '₹249' -> 249, '₹1,999' -> 1999, '₹0' -> 0. */
export function priceAmount(price: string): number {
  const digits = price.replace(/[^\d.]/g, '');
  if (digits === '' || Number.isNaN(Number(digits))) {
    throw new Error(`structuredData: cannot read a number out of the plan price ${JSON.stringify(price)}`);
  }
  return Number(digits);
}

/** One schema.org Offer per plan that can be bought today, worded from the plan's own fields. */
export function offer(plan: Plan): JsonLd {
  if (plan.availability !== 'available' || plan.price === null) {
    throw new Error(`structuredData: plan ${plan.id} is not purchasable and must not become an Offer`);
  }
  const terms = [
    `${plan.price}${plan.cadence ?? ''}`,
    plan.secondaryPriceNote,
    plan.priceIncludesGst ? 'including GST' : undefined,
  ].filter((part): part is string => Boolean(part));
  return {
    '@type': 'Offer',
    name: plan.name,
    price: priceAmount(plan.price),
    priceCurrency: 'INR',
    description: terms.join(', '),
    availability: 'https://schema.org/InStock',
    url: `${SITE_ORIGIN}/#pricing`,
  };
}

export function softwareApplication(): JsonLd {
  return {
    '@context': 'https://schema.org',
    '@type': 'SoftwareApplication',
    name: 'Fynora',
    url: `${SITE_ORIGIN}/`,
    description: hero.blurb,
    applicationCategory: 'FinanceApplication',
    operatingSystem: 'Web',
    publisher: { '@id': ORGANIZATION_ID },
    offers: PRICING_CARDS.filter((p) => p.availability === 'available' && p.price !== null).map(offer),
  };
}

export function faqPage(items: ReadonlyArray<readonly [string, string]>): JsonLd {
  if (items.length === 0) throw new Error('structuredData: an FAQPage with no questions');
  return {
    '@context': 'https://schema.org',
    '@type': 'FAQPage',
    mainEntity: items.map(([question, answer]) => ({
      '@type': 'Question',
      name: question,
      acceptedAnswer: { '@type': 'Answer', text: answer },
    })),
  };
}

/** A page between Home and this one in a breadcrumb trail. */
export interface Crumb {
  route: string;
  name: string;
}

/**
 * Home > <parents...> > <this page>. `title` is the page's heading as plain text (not HTML-escaped).
 * A Help article's trail is Home > Help Center > the question.
 */
export function breadcrumbs(route: string, title: string, parents: readonly Crumb[] = []): JsonLd {
  if (route === '/') throw new Error('structuredData: the homepage has no breadcrumb trail');
  const trail: Crumb[] = [...parents, { route, name: title }];
  return {
    '@context': 'https://schema.org',
    '@type': 'BreadcrumbList',
    itemListElement: [
      { '@type': 'ListItem', position: 1, name: 'Home', item: `${SITE_ORIGIN}/` },
      ...trail.map((crumb, i) => ({
        '@type': 'ListItem',
        position: i + 2,
        name: crumb.name,
        item: canonicalUrl(crumb.route),
      })),
    ],
  };
}

// The /help page's heading, which is the middle crumb of every Help article's trail.
// structuredData.test.ts checks it is still what Help.tsx renders as its <h1>.
export const HELP_CENTER_TITLE = 'Help Center';

/**
 * Everything a prerendered route should carry. The homepage describes the organisation, the site,
 * the product with its prices, and the landing page's own FAQ. A Help article (/help/<slug>) is its
 * own question and answer as an FAQPage, under a Home > Help Center > question trail. Every other
 * page, /help included, gets a breadcrumb trail; /about adds the Organization; a page with a
 * "Last updated" line (`lastUpdated`, YYYY-MM) adds a WebPage block with that dateModified.
 * `title` is unused for the homepage.
 *
 * /help itself carries no FAQPage. It used to mark up all of its answers, but each answer now has
 * its own page with its own markup, and Google asks for FAQ markup on one instance of a question
 * only; the article page is the one search results and AI answers should link.
 */
export function structuredDataFor(route: string, title: string | null, lastUpdated: string | null = null): JsonLd[] {
  if (route === '/') {
    return [organization(), website(), softwareApplication(), faqPage(faq.items)];
  }
  if (title === null || title.trim() === '') {
    throw new Error(`structuredData: ${route} needs its heading for the breadcrumb trail`);
  }
  if (route.startsWith('/help/')) {
    const article = helpArticleForPath(route);
    if (!article) throw new Error(`structuredData: ${route} is under /help/ but no Help article has that slug`);
    if (article.question !== title) {
      throw new Error(`structuredData: ${route}'s heading is not its article's question`);
    }
    return [
      breadcrumbs(route, title, [{ route: '/help', name: HELP_CENTER_TITLE }]),
      faqPage([[article.question, article.answer]]),
    ];
  }
  const blocks: JsonLd[] = [breadcrumbs(route, title)];
  // The About page is the company's own page, so it carries the full Organization block (the same
  // @id as the homepage's, so the two are read as one entity).
  if (route === '/about') blocks.push(organization());
  if (lastUpdated !== null) blocks.push(webPage(route, title, lastUpdated));
  return blocks;
}

/**
 * The <script type="application/ld+json"> tags for a page, one per block. `<` is written as
 * \u003c so an answer that happens to contain "</script>" can never end the tag early; JSON parsers
 * read the escape back as the character.
 */
export function jsonLdScripts(blocks: JsonLd[]): string {
  return blocks
    .map((block) => `<script type="application/ld+json">${JSON.stringify(block).replace(/</g, '\\u003c')}</script>`)
    .join('\n');
}
