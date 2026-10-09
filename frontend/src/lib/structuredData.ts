import { SITE_ORIGIN, canonicalUrl } from './siteUrl';
import { SUPPORT_EMAIL } from './contact';
import { PRICING_CARDS, type Plan } from '../pages/landing/plans';
import { faq, footer, hero } from '../pages/landing/landing-config';
import { HELP_ARTICLES } from '../pages/helpArticles';

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

/** Home > <this page>. `title` is the page's heading as plain text (not HTML-escaped). */
export function breadcrumbs(route: string, title: string): JsonLd {
  if (route === '/') throw new Error('structuredData: the homepage has no breadcrumb trail');
  return {
    '@context': 'https://schema.org',
    '@type': 'BreadcrumbList',
    itemListElement: [
      { '@type': 'ListItem', position: 1, name: 'Home', item: `${SITE_ORIGIN}/` },
      { '@type': 'ListItem', position: 2, name: title, item: canonicalUrl(route) },
    ],
  };
}

/**
 * Everything a prerendered route should carry. The homepage describes the organisation, the site,
 * the product with its prices, and the landing page's own FAQ; /help adds its articles as an
 * FAQPage; every other page gets a breadcrumb trail. `title` is unused for the homepage.
 */
export function structuredDataFor(route: string, title: string | null): JsonLd[] {
  if (route === '/') {
    return [organization(), website(), softwareApplication(), faqPage(faq.items)];
  }
  if (title === null || title.trim() === '') {
    throw new Error(`structuredData: ${route} needs its heading for the breadcrumb trail`);
  }
  const blocks: JsonLd[] = [breadcrumbs(route, title)];
  if (route === '/help') {
    blocks.push(faqPage(HELP_ARTICLES.map((a) => [a.question, a.answer] as const)));
  }
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
