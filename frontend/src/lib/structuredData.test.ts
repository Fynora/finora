import { describe, expect, it } from 'vitest';
import {
  HELP_CENTER_TITLE,
  breadcrumbs,
  faqPage,
  jsonLdScripts,
  offer,
  organization,
  priceAmount,
  softwareApplication,
  structuredDataFor,
  webPage,
  website,
} from './structuredData';
import { GRIEVANCE_EMAIL, LEGAL_NAME, REGISTERED_OFFICE } from './contact';
import { PLAY_STORE_URL } from './openInApp';
import { PLANS, PRICING_CARDS } from '../pages/landing/plans';
import { faq, footer, hero } from '../pages/landing/landing-config';
import { HELP_ARTICLES, helpArticlePath } from '../pages/helpArticles';
import { SITE_ORIGIN } from './siteUrl';
import { SUPPORT_EMAIL } from './contact';

describe('priceAmount', () => {
  it('reads the number out of a rupee sticker price, thousands separator included', () => {
    expect(priceAmount('₹0')).toBe(0);
    expect(priceAmount('₹249')).toBe(249);
    expect(priceAmount('₹1,999')).toBe(1999);
  });

  it('refuses a price with no number in it rather than publishing NaN', () => {
    expect(() => priceAmount('Coming soon')).toThrow(/cannot read a number/);
    expect(() => priceAmount('')).toThrow(/cannot read a number/);
  });
});

describe('offer', () => {
  it("is worded from the plan's own price, cadence, second cadence and GST flag", () => {
    const plus = PLANS.find((p) => p.id === 'plus')!;
    expect(offer(plus)).toEqual({
      '@type': 'Offer',
      name: 'Plus',
      price: 249,
      priceCurrency: 'INR',
      description: '₹249/month, or ₹1,999/year, including GST',
      availability: 'https://schema.org/InStock',
      url: `${SITE_ORIGIN}/#pricing`,
    });
    const free = PLANS.find((p) => p.id === 'free')!;
    expect(offer(free)).toMatchObject({ name: 'Free', price: 0, description: '₹0/month' });
  });

  it('refuses a plan that cannot be bought', () => {
    const plus = PLANS.find((p) => p.id === 'plus')!;
    expect(() => offer({ ...plus, availability: 'coming-soon', price: null })).toThrow(/not purchasable/);
    expect(() => offer({ ...plus, price: null })).toThrow(/not purchasable/);
  });
});

describe('the homepage blocks', () => {
  it('describe the organisation from the footer and contact constants, on the indexed host', () => {
    expect(organization()).toMatchObject({
      '@type': 'Organization',
      name: 'Fynora',
      legalName: 'Fynora Technovation LLP',
      url: 'https://app.fynora.net/',
      logo: 'https://app.fynora.net/favicon.png',
      email: SUPPORT_EMAIL,
      sameAs: [footer.instagram, PLAY_STORE_URL],
    });
    expect(website()).toMatchObject({ '@type': 'WebSite', url: 'https://app.fynora.net/' });
    expect(website().publisher).toEqual({ '@id': organization()['@id'] });
  });

  it('describe the product with the hero sentence and exactly the publicly sold plans as offers', () => {
    const app = softwareApplication();
    expect(app).toMatchObject({
      '@type': 'SoftwareApplication',
      applicationCategory: 'FinanceApplication',
      operatingSystem: 'Web',
      description: hero.blurb,
    });
    const offers = app.offers as { name: string }[];
    // PRICING_CARDS is Free and Plus. Premium is sold in-app but not marketed publicly, and must
    // not appear in public structured data either.
    expect(offers.map((o) => o.name)).toEqual(PRICING_CARDS.map((p) => p.name));
    expect(offers.map((o) => o.name)).not.toContain('Premium');
  });

  it("turn the landing page's FAQ into a FAQPage, one Question per item, text unchanged", () => {
    const page = faqPage(faq.items);
    const entity = page.mainEntity as { name: string; acceptedAnswer: { text: string } }[];
    expect(entity).toHaveLength(faq.items.length);
    expect(entity[0].name).toBe(faq.items[0][0]);
    expect(entity[0].acceptedAnswer.text).toBe(faq.items[0][1]);
    expect(() => faqPage([])).toThrow(/no questions/);
  });
});

describe('the Organization', () => {
  it('has the legal name and registered office the Contact, Terms and Privacy pages print', () => {
    expect(organization().legalName).toBe(LEGAL_NAME);
    expect(organization().address).toEqual({
      '@type': 'PostalAddress',
      streetAddress: REGISTERED_OFFICE.streetAddress,
      addressLocality: 'Jhansi',
      addressRegion: 'Uttar Pradesh',
      postalCode: '284003',
      addressCountry: 'IN',
    });
  });

  it('gives the support and grievance mailboxes as contact points, both reached from /contact', () => {
    expect(organization().contactPoint).toEqual([
      { '@type': 'ContactPoint', contactType: 'customer support', email: SUPPORT_EMAIL, url: 'https://app.fynora.net/contact' },
      { '@type': 'ContactPoint', contactType: 'data protection grievances', email: GRIEVANCE_EMAIL, url: 'https://app.fynora.net/contact' },
    ]);
  });

  it('links only official profiles the site itself links: the footer Instagram and the Play listing', () => {
    expect(organization().sameAs).toEqual([footer.instagram, PLAY_STORE_URL]);
    expect(PLAY_STORE_URL).toBe('https://play.google.com/store/apps/details?id=com.fynora.android');
  });

  it('states no founder, founding date or registration number, which no page states either', () => {
    for (const key of ['founder', 'founders', 'foundingDate', 'taxID', 'vatID', 'leiCode', 'identifier']) {
      expect(organization(), key).not.toHaveProperty(key);
    }
  });
});

describe('webPage', () => {
  it('carries the page\'s month-precision dateModified, on its canonical URL, inside the site', () => {
    expect(webPage('/privacy', 'Privacy Policy', '2026-10')).toEqual({
      '@context': 'https://schema.org',
      '@type': 'WebPage',
      '@id': 'https://app.fynora.net/privacy#webpage',
      url: 'https://app.fynora.net/privacy',
      name: 'Privacy Policy',
      dateModified: '2026-10',
      isPartOf: { '@id': website()['@id'] },
      publisher: { '@id': organization()['@id'] },
    });
  });

  it('refuses a date that is not YYYY-MM, rather than publish a wrong or invented one', () => {
    for (const bad of ['2026-13', '2026-00', '2026-10-01', 'October 2026', '26-10', '']) {
      expect(() => webPage('/privacy', 'Privacy Policy', bad), bad).toThrow(/not YYYY-MM/);
    }
  });
});

describe('breadcrumbs', () => {
  it('is Home then the page, on canonical URLs', () => {
    expect(breadcrumbs('/terms', 'Terms & Conditions')).toEqual({
      '@context': 'https://schema.org',
      '@type': 'BreadcrumbList',
      itemListElement: [
        { '@type': 'ListItem', position: 1, name: 'Home', item: 'https://app.fynora.net/' },
        { '@type': 'ListItem', position: 2, name: 'Terms & Conditions', item: 'https://app.fynora.net/terms' },
      ],
    });
  });

  it('puts the parent pages between Home and the page, in order', () => {
    expect(breadcrumbs('/help/plans-and-prices', 'What plans does Fynora offer?', [{ route: '/help', name: 'Help Center' }]).itemListElement).toEqual([
      { '@type': 'ListItem', position: 1, name: 'Home', item: 'https://app.fynora.net/' },
      { '@type': 'ListItem', position: 2, name: 'Help Center', item: 'https://app.fynora.net/help' },
      { '@type': 'ListItem', position: 3, name: 'What plans does Fynora offer?', item: 'https://app.fynora.net/help/plans-and-prices' },
    ]);
  });

  it('has nothing to say on the homepage', () => {
    expect(() => breadcrumbs('/', 'Home')).toThrow(/no breadcrumb/);
  });
});

describe('structuredDataFor', () => {
  it('gives the homepage the organisation, the site, the product and its FAQ', () => {
    expect(structuredDataFor('/', null).map((b) => b['@type'])).toEqual([
      'Organization', 'WebSite', 'SoftwareApplication', 'FAQPage',
    ]);
  });

  it('gives /help a breadcrumb trail only: each answer is marked up once, on its own page', () => {
    expect(structuredDataFor('/help', HELP_CENTER_TITLE).map((b) => b['@type'])).toEqual(['BreadcrumbList']);
  });

  it('gives every Help article its own question as a FAQPage, under Home > Help Center > question', () => {
    for (const article of HELP_ARTICLES) {
      const route = helpArticlePath(article);
      const blocks = structuredDataFor(route, article.question);
      expect(blocks.map((b) => b['@type']), article.slug).toEqual(['BreadcrumbList', 'FAQPage']);
      expect(blocks[0]).toEqual(breadcrumbs(route, article.question, [{ route: '/help', name: 'Help Center' }]));
      expect(blocks[1].mainEntity, article.slug).toEqual([
        { '@type': 'Question', name: article.question, acceptedAnswer: { '@type': 'Answer', text: article.answer } },
      ]);
    }
  });

  it('refuses an address under /help/ that is no article, or a heading that is not its question', () => {
    expect(() => structuredDataFor('/help/no-such-answer', 'Anything')).toThrow(/no Help article has that slug/);
    expect(() => structuredDataFor(helpArticlePath(HELP_ARTICLES[0]), 'Some other heading')).toThrow(/not its article's question/);
  });

  it('gives every other page a breadcrumb trail and nothing else', () => {
    expect(structuredDataFor('/privacy', 'Privacy Policy').map((b) => b['@type'])).toEqual(['BreadcrumbList']);
  });

  it('adds a WebPage with the date when the page has a "Last updated" line', () => {
    const blocks = structuredDataFor('/privacy', 'Privacy Policy', '2026-10');
    expect(blocks.map((b) => b['@type'])).toEqual(['BreadcrumbList', 'WebPage']);
    expect(blocks[1].dateModified).toBe('2026-10');
  });

  it('gives /about the full Organization, the same entity as the homepage\'s', () => {
    const blocks = structuredDataFor('/about', 'About Fynora');
    expect(blocks.map((b) => b['@type'])).toEqual(['BreadcrumbList', 'Organization']);
    expect(blocks[1]).toEqual(organization());
    expect(blocks[1]['@id']).toBe(structuredDataFor('/', null)[0]['@id']);
  });

  it('refuses to build a breadcrumb without the page heading', () => {
    expect(() => structuredDataFor('/privacy', null)).toThrow(/needs its heading/);
    expect(() => structuredDataFor('/privacy', '  ')).toThrow(/needs its heading/);
  });
});

describe('jsonLdScripts', () => {
  it('writes one script tag per block that a JSON parser reads back unchanged', () => {
    const out = jsonLdScripts(structuredDataFor('/', null));
    const scripts = [...out.matchAll(/<script type="application\/ld\+json">([\s\S]*?)<\/script>/g)];
    expect(scripts).toHaveLength(4);
    expect(JSON.parse(scripts[0][1])).toEqual(organization());
  });

  it('cannot be closed early by a "</script>" inside an answer', () => {
    const out = jsonLdScripts([faqPage([['q', 'a </script><script>alert(1)</script>']])]);
    expect(out.match(/<\/script>/g)).toHaveLength(1);
    expect(out).not.toContain('<script>alert');
    const inner = /<script type="application\/ld\+json">([\s\S]*)<\/script>/.exec(out)![1];
    expect(JSON.parse(inner).mainEntity[0].acceptedAnswer.text).toBe('a </script><script>alert(1)</script>');
  });
});
