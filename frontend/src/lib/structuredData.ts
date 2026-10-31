import { SITE_ORIGIN, canonicalUrl } from './siteUrl';
import { SUPPORT_EMAIL } from './contact';
import { PRICING_CARDS, type Plan } from '../pages/landing/plans';
import { faq, footer, hero } from '../pages/landing/landing-config';
import { helpArticleForPath } from '../pages/helpArticles';
import { CONVERTER_DESCRIPTION, CONVERTER_FAQ, CONVERTER_ROUTE, CONVERTER_TITLE } from '../pages/converter/converterContent';

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
 * - A postal address on the Organization. The registered office is typed inline on Contact and
 *   Terms; a third copy here would be a third place for it to drift. Centralise it first.
 * - Any operating system but the web. The mobile apps' store status is not something this file
 *   can verify, and SoftwareApplication's operatingSystem is read as "where can I get it".
 */

type JsonLd = Record<string, unknown>;

const ORGANIZATION_ID = `${SITE_ORIGIN}/#organization`;
const WEBSITE_ID = `${SITE_ORIGIN}/#website`;

export function organization(): JsonLd {
  return {
    '@context': 'https://schema.org',
    '@type': 'Organization',
    '@id': ORGANIZATION_ID,
    name: 'Fynora',
    legalName: 'Fynora Technovation LLP',
    url: `${SITE_ORIGIN}/`,
    logo: `${SITE_ORIGIN}/favicon.png`,
    email: SUPPORT_EMAIL,
    sameAs: [footer.instagram],
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

/**
 * The free statement converter as a free web application. Every value is the page's own: its
 * description, its route, and a price of nothing.
 */
export function converterApplication(): JsonLd {
  return {
    '@context': 'https://schema.org',
    '@type': 'WebApplication',
    name: 'Fynora bank statement converter',
    url: canonicalUrl(CONVERTER_ROUTE),
    description: CONVERTER_DESCRIPTION,
    applicationCategory: 'FinanceApplication',
    operatingSystem: 'Web',
    isAccessibleForFree: true,
    offers: { '@type': 'Offer', price: 0, priceCurrency: 'INR' },
    publisher: { '@id': ORGANIZATION_ID },
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
 * page, /help included, gets a breadcrumb trail. `title` is unused for the homepage.
 *
 * /help itself carries no FAQPage. It used to mark up all of its answers, but each answer now has
 * its own page with its own markup, and Google asks for FAQ markup on one instance of a question
 * only; the article page is the one search results and AI answers should link.
 */
export function structuredDataFor(route: string, title: string | null): JsonLd[] {
  if (route === '/') {
    return [organization(), website(), softwareApplication(), faqPage(faq.items)];
  }
  if (title === null || title.trim() === '') {
    throw new Error(`structuredData: ${route} needs its heading for the breadcrumb trail`);
  }
  if (route === CONVERTER_ROUTE) {
    if (title !== CONVERTER_TITLE) {
      throw new Error(`structuredData: ${route}'s heading is not the converter's title`);
    }
    return [breadcrumbs(route, title), faqPage(CONVERTER_FAQ), converterApplication()];
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
  return [breadcrumbs(route, title)];
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
