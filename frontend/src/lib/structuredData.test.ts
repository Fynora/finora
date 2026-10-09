import { describe, expect, it } from 'vitest';
import {
  breadcrumbs,
  faqPage,
  jsonLdScripts,
  offer,
  organization,
  priceAmount,
  softwareApplication,
  structuredDataFor,
  website,
} from './structuredData';
import { PLANS, PRICING_CARDS } from '../pages/landing/plans';
import { faq, footer, hero } from '../pages/landing/landing-config';
import { HELP_ARTICLES } from '../pages/helpArticles';
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
      sameAs: [footer.instagram],
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

  it('gives /help a breadcrumb trail and every Help article as a FAQPage question', () => {
    const blocks = structuredDataFor('/help', 'Help Center');
    expect(blocks.map((b) => b['@type'])).toEqual(['BreadcrumbList', 'FAQPage']);
    const entity = blocks[1].mainEntity as { name: string }[];
    expect(entity).toHaveLength(HELP_ARTICLES.length);
    expect(entity.map((q) => q.name)).toEqual(HELP_ARTICLES.map((a) => a.question));
  });

  it('gives every other page a breadcrumb trail and nothing else', () => {
    expect(structuredDataFor('/privacy', 'Privacy Policy').map((b) => b['@type'])).toEqual(['BreadcrumbList']);
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
