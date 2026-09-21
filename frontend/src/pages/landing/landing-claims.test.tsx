import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import Landing from '../Landing';
import { AVAILABILITY_LABEL, COMPARISON, PLANS, PRICING_CARDS } from './plans';
import { SETTINGS_CATEGORIES } from '../settings/SettingsNav';
import { askFyn, beforeAfter, capabilities, faq, hero, importSection, security, trust } from './landing-config';

/**
 * Enforces the mechanically-checkable half of docs/project-management/standards/marketing-claims-checklist.md.
 *
 * It cannot decide whether a sentence is TRUE -- that is the reviewer's job, and the checklist is
 * the review. What it can do is make sure the specific mistakes this page has ALREADY SHIPPED
 * cannot come back silently: invented testimonials, fabricated usage counters, prices on tiers
 * nobody can buy, "bank-level encryption", and a subscribe box that discards the address.
 *
 * Each assertion below corresponds to a real regression, not a hypothetical one. If one starts
 * failing, the fix is almost always the copy, not the test.
 */
function renderLanding() {
  return render(
    <MemoryRouter>
      <Landing />
    </MemoryRouter>
  );
}

function pageText(): string {
  return document.body.innerText || document.body.textContent || '';
}

describe('landing page — marketing claims', () => {
  it('makes no fabricated social-proof claim', () => {
    renderLanding();
    const text = pageText();

    // Shipped once as "Trusted by thousands of users across India" and as three invented
    // testimonials. Both are unverifiable claims about real people.
    const patterns = [
      /trusted by [\d,]+/i,
      /trusted by (thousands|millions|hundreds)/i,
      /\b\d[\d,]*\+?\s*(users|customers|people)\b/i,
      /what (our |early )?users are saying/i,
      /join [\d,]+/i,
    ];
    const hits = patterns.filter((p) => p.test(text)).map(String);
    expect(hits).toEqual([]);
  });

  it('shows no usage counter presented as a live platform metric', () => {
    renderLanding();
    const text = pageText();

    // "486,000+ Transactions Processed" and friends were animated to look live and were invented.
    const patterns = [
      /[\d,]+\+?\s*(statements?|transactions?)\s*(imported|processed)/i,
      /[\d,]+\+?\s*(budgets?|goals?)\s*(managed|created)/i,
    ];
    expect(patterns.filter((p) => p.test(text)).map(String)).toEqual([]);
  });

  /**
   * Product's Billing Plan Taxonomy Decision (2026-08-12): exactly Free/Plus/Premium, no more,
   * no less. `plans.ts` described Free/Premium/Family/Future for four days after that decision
   * before being caught and fixed -- this is what would have caught it immediately.
   */
  it('offers exactly the Free/Plus/Premium taxonomy Product approved, and shows two of them publicly', () => {
    // PLANS keeps all three (the in-app Billing page still sells Premium); the PUBLIC page shows two.
    expect(PLANS.map((p) => p.id)).toEqual(['free', 'plus', 'premium']);
    expect(PRICING_CARDS.map((p) => p.id)).toEqual(['free', 'plus']);
  });

  /**
   * The rule from plans.ts, asserted rather than trusted: a price may only appear on a plan that
   * can actually be bought. There is no billing in the backend, so today that is Free alone.
   */
  it('prices only the plans that are actually available', () => {
    for (const plan of PLANS) {
      if (plan.price !== null) {
        expect(
          plan.availability,
          `Plan "${plan.name}" carries a price but is not available for purchase. ` +
            'Show its status where the price would go instead — see plans.ts.'
        ).toBe('available');
      }
    }
    expect(PLANS.filter((p) => p.availability === 'available').map((p) => p.id)).toEqual(['free', 'plus', 'premium']);
  });

  /**
   * Product decision (2026-09-21): investments are a small, free side feature -- adding a holding
   * and seeing SIP/broker transactions under Investments -- and must not be sold or advertised.
   * "Investment insights" was listed as a Premium benefit, and the hero showed an "Investment +12%"
   * chip, for a product with no market-price feed, NAV, or returns calculation at all. Both were
   * removed; this keeps them from coming back as a plan benefit or a performance figure.
   */
  it('never presents investment tracking as something a plan includes', () => {
    const planCopy = PLANS.flatMap((p) => [p.blurb, p.promise, p.stage.outcome, ...p.features]);
    const comparisonLabels = COMPARISON.map((row) => row.label);
    expect(
      [...planCopy, ...comparisonLabels].filter((text) => /invest/i.test(text)),
      'Investments are not a plan feature. Do not list them in a tier or the comparison table.'
    ).toEqual([]);

    const { container } = renderLanding();
    const pricing = container.querySelector('#pricing');
    expect(pricing?.textContent ?? '').not.toMatch(/invest/i);

    // Anywhere on the page: no investment-tracking benefit, and no investment performance figure.
    const text = pageText();
    const claims = [
      /investment insights/i,
      /(track|tracking|see|view) (your )?investments?/i,
      /investments? included/i,
      /investments?\s*[+\-\u2212]\s*\d+(\.\d+)?\s*%/i,
    ];
    expect(claims.filter((p) => p.test(text)).map(String)).toEqual([]);
  });

  it("describes Premium by what it enforces today, without claiming the bank feed that isn't live", () => {
    const premium = PLANS.find((p) => p.id === 'premium')!;
    const copy = [premium.blurb, premium.promise, premium.stage.outcome, ...premium.features].join(' ');

    // GMAIL_SYNC is the one Premium-only capability that is real (GmailConnectionService).
    expect(copy).toMatch(/gmail/i);
    // ACCOUNT_AGGREGATOR_SYNC is seeded Premium-only but waits on Setu access: not available yet,
    // so it may not be presented as something Premium gives today.
    expect(copy).not.toMatch(/bank feed|account aggregator|bank sync|live bank/i);
  });

  it('renders no rupee price for an unreleased tier', () => {
    const { container } = renderLanding();

    // Scoped to the pricing section rather than the whole page. An earlier version of this test
    // scanned everything and tried to tell plan prices from the dashboard illustration's sample
    // amounts by magnitude -- which flagged a ₹480 Swiggy charge as a suspicious price. The
    // section boundary is the real distinction, so use it.
    const pricing = container.querySelector('#pricing');
    expect(pricing, 'The pricing section should have id="pricing"').not.toBeNull();

    const rendered = (pricing?.textContent ?? '').match(/₹[\d,]+/g) ?? [];
    // A plan's own price, plus any rupee amount inside its secondaryPriceNote (e.g. "or
    // ₹3,500/year") -- both trace back to this same plans.ts source of truth, so both are
    // legitimate here; the invariant this test guards is "every rendered price is backed by
    // plans.ts," not "only the primary price may ever appear."
    const allowed = PLANS.flatMap((p) => [
      ...(p.price ? [p.price] : []),
      ...((p.secondaryPriceNote?.match(/₹[\d,]+/g)) ?? []),
    ]);

    // ₹149 and ₹249 shipped here on tiers with no billing behind them.
    expect(
      rendered.filter((p) => !allowed.includes(p)),
      'A price is rendered in the pricing section for a plan that is not purchasable. ' +
        'Show its availability where the price would go instead — see plans.ts.'
    ).toEqual([]);
  });

  it('does not overstate encryption', () => {
    renderLanding();
    const text = pageText();

    // What is true: TLS in transit, bcrypt hashing, content-addressed integrity checks. These
    // three phrases claim more than the application implements.
    const overclaims = [/bank[- ]level encryption/i, /military[- ]grade/i, /end[- ]to[- ]end encrypt/i];
    expect(overclaims.filter((p) => p.test(text)).map(String)).toEqual([]);
  });

  it('exposes no form that submits nowhere', () => {
    const { container } = renderLanding();

    // The old newsletter box accepted an email, thanked the visitor, and discarded it. Any email
    // capture on this page must post somewhere real -- today the waitlist is a mailto, which does.
    const emailInputs = container.querySelectorAll('input[type="email"]');
    expect(
      emailInputs.length,
      'An email input on the landing page must submit somewhere real. The previous newsletter ' +
        'box discarded the address. Use the mailto waitlist, or wire a real endpoint.'
    ).toBe(0);
  });

  // The waitlist assertion that used to sit here required a waitlist link to EXIST. It was
  // removed with the CTA itself: nothing stores the interest, so the control could not keep the
  // promise in its own label. The inverse rule -- no signup control without a destination -- is
  // enforced below, and is the one that actually matters.

  it('labels the unreleased mobile apps as unreleased', () => {
    renderLanding();
    // Built through Phase 5, on no app store. The page may mention them; it may not imply a
    // download exists.
    expect(screen.getByText(/not on the app stores yet/i)).toBeTruthy();
  });

  it('opens external links safely', () => {
    const { container } = renderLanding();
    for (const link of container.querySelectorAll('a[target="_blank"]')) {
      const rel = link.getAttribute('rel') ?? '';
      expect(rel).toContain('noopener');
      expect(rel).toContain('noreferrer');
    }
  });
});

/**
 * Controls that promise something must be able to deliver it. Every rule here corresponds to a
 * dead control that actually shipped -- a nav item that scrolled nowhere, a waitlist button that
 * stored nothing. A control the page cannot honour is the same broken promise as a false claim,
 * just wearing a border.
 */
describe('landing page — nothing that promises what it cannot do', () => {
  it('points every in-page link at a section that exists', () => {
    const { container } = renderLanding();

    // The nav's "Before & after" linked to #difference while no section carried that id, so the
    // item silently did nothing. Checked for every anchor rather than that one.
    const broken = [...container.querySelectorAll('a[href^="#"]')]
      .map((a) => a.getAttribute('href') as string)
      .filter((href) => href.length > 1)
      .filter((href) => !container.querySelector(`[id="${href.slice(1)}"]`));

    expect([...new Set(broken)], 'These anchors scroll nowhere — the target id does not exist.').toEqual([]);
  });

  /**
   * No waitlist, notify-me or subscribe control unless something receives it. A mailto counts
   * (it genuinely delivers); a button wired to nothing does not, and neither does a form with no
   * action -- that is what the old newsletter box was.
   */
  it('offers no signup control without a destination', () => {
    const { container } = renderLanding();

    const suspicious = [...container.querySelectorAll('a,button')]
      .filter((el) => /waitlist|notify me|subscribe|join the list/i.test(el.textContent ?? ''))
      .filter((el) => {
        const href = el.getAttribute('href') ?? '';
        return !(href.startsWith('mailto:') || href.startsWith('http') || href.startsWith('/'));
      })
      .map((el) => (el.textContent ?? '').trim().slice(0, 40));

    expect(
      suspicious,
      'A signup control must lead somewhere that receives it. Remove it, or wire a real endpoint.'
    ).toEqual([]);
  });

  it('never marks an available plan as coming soon, or an unavailable one as available', () => {
    for (const plan of PLANS) {
      const label = AVAILABILITY_LABEL[plan.availability];
      if (plan.availability === 'available') {
        expect(label, `"${plan.name}" is available but labelled "${label}".`).toMatch(/available/i);
      } else {
        expect(label, `"${plan.name}" is not available but labelled "${label}".`).not.toMatch(/available/i);
      }
    }
  });

  it('offers a purchase action only on a plan that can be purchased', () => {
    const { container } = renderLanding();
    const pricing = container.querySelector('#pricing');

    const buyish = [...(pricing?.querySelectorAll('a,button') ?? [])]
      .filter((el) => /start free|get started|subscribe|buy|upgrade now/i.test(el.textContent ?? ''));

    // One call to action per plan the page actually shows.
    expect(buyish).toHaveLength(PRICING_CARDS.filter((p) => p.availability === 'available').length);
  });

  // The parallel structure IS the argument of that section; unequal columns break the comparison.
  it('keeps the before and after columns the same length', () => {
    expect(beforeAfter.before).toHaveLength(beforeAfter.after.length);
  });
});

/**
 * One sentence on this page must never contradict another sentence on this page, or the product
 * itself. Each rule below is a contradiction that actually shipped and sat next to the sentence it
 * disproved: "No upsells, ever" above two paid plans; "Only the first step exists today" above three
 * "Available today" badges; a "Most popular" badge with no usage data behind it; and "holds no
 * connection to your bank" while Settings offered Bank Sync and Gmail. A visitor who spots one
 * discounts the whole page, and this page's only product is trust.
 *
 * FAQ answers are checked against the config rather than the rendered page, because the FAQ shows
 * only its first answer until a visitor opens the rest.
 */
describe('landing page — no self-contradiction', () => {
  const claimText = () => [
    pageText(),
    hero.assurances.join(' '),
    security.blurb,
    ...faq.items.flat(),
    ...importSection.proofs.flatMap((p) => [p.title, p.body]),
    ...capabilities.items.flatMap((i) => [i.title, i.body]),
    askFyn.blurb, ...askFyn.points,
    ...trust.never, ...trust.always,
  ].join(' ');

  it('makes no "no upsells" promise while a plan costs money', () => {
    expect(PLANS.some((p) => p.price && p.price !== '₹0'), 'no paid plan left; this rule is moot').toBe(true);
    renderLanding();
    expect(claimText()).not.toMatch(/no upsells?/i);
  });

  it('badges no plan "most popular" without usage data behind it', () => {
    renderLanding();
    expect(claimText()).not.toMatch(/most popular|best[- ]seller|customers.? favou?rite/i);
  });

  it('never says only the first step exists while every plan is available', () => {
    renderLanding();
    if (PLANS.every((p) => p.availability === 'available')) {
      expect(pageText()).not.toMatch(/only the first (step|plan)|first step exists|where fynora is going/i);
    }
  });

  it('gives an available plan a rung label that is not a time', () => {
    for (const plan of PLANS.filter((p) => p.availability === 'available')) {
      expect(
        plan.stage.when,
        `"${plan.name}" is available, but its rung label "${plan.stage.when}" reads as a future date.`
      ).not.toMatch(/tomorrow|later|soon|planned|upcoming|next/i);
    }
  });

  it('claims no absolute absence of a bank connection while Settings offers one', () => {
    const offersConnection = SETTINGS_CATEGORIES.some((c) => c.key === 'bank-sync' || c.key === 'connected-apps');
    expect(offersConnection, 'Settings no longer offers Bank Sync or Connected Apps; loosen this rule.').toBe(true);
    renderLanding();
    expect(claimText()).not.toMatch(
      /no standing (connection|access)|holds no connection|no connection to your bank|only the statements you upload|reads only the statements/i
    );
  });

  it('does not say user data is never shared while Privacy discloses processors', () => {
    // Privacy.tsx discloses that Ask Fyn sends account data to Anthropic, and that authorized staff
    // may open a statement to fix a failed import. "Never sold" is a promise we can keep.
    renderLanding();
    expect(claimText()).not.toMatch(/never sold or shared|never shared/i);
  });
});

/**
 * The reframe's owner decisions, enforced. Each of these is a claim we decided NOT to make, and each
 * is easy to reintroduce by accident when someone edits copy: a bank name because it "adds proof", an
 * investment line because the feature exists, a bank feed because the code is written, Premium because
 * it is still for sale in the app.
 */
describe('landing page — the reframe', () => {
  const claimText = () => [
    pageText(),
    hero.assurances.join(' '),
    security.blurb,
    ...faq.items.flat(),
    ...importSection.proofs.flatMap((p) => [p.title, p.body]),
    ...capabilities.items.flatMap((i) => [i.title, i.body]),
    askFyn.blurb, ...askFyn.points,
    ...trust.never, ...trust.always,
  ].join(' ');

  it('names no bank', () => {
    renderLanding();
    // Owner decision: say "Indian banks", never a name. Recognised is not the same as "every layout
    // parses", and per-bank coverage is not measured.
    expect(claimText()).not.toMatch(
      /\b(hdfc|icici|sbi|state bank|axis bank|kotak|yes bank|idfc|pnb|punjab national|canara|bank of baroda|indusind|federal bank|rbl|hsbc|citi)\b/i
    );
  });

  it('markets no Gmail connection anywhere on the page or in the plans (dropped for v1, owner decision 2026-09-21)', () => {
    renderLanding();
    // claimText covers the rendered page plus the FAQ, capability and security copy that the accordion
    // or layout may not render; PLANS covers the plan feature lists the in-app Billing page also shows.
    expect(claimText()).not.toMatch(/gmail/i);
    expect(JSON.stringify(PLANS)).not.toMatch(/gmail/i);
  });

  it('does not describe learning from corrections as "training" (owner decision 2026-09-21)', () => {
    renderLanding();
    expect(pageText()).not.toMatch(/\btrain(ing|ed|s)?\b/i);
  });

  it('does not market investments', () => {
    renderLanding();
    expect(claimText()).not.toMatch(/investment (insights|tracking)|track (your )?investments|mutual fund|portfolio|net worth/i);
  });

  it('promises no bank feed or Account Aggregator', () => {
    renderLanding();
    // An FIU must itself be regulated by RBI/SEBI/IRDAI/PFRDA and Fynora is not, so a bank feed is
    // not something we can promise, not even as "coming".
    expect(claimText()).not.toMatch(/account aggregator|bank (feed|sync)|live bank connection/i);
  });

  it('never sells Premium on the public page', () => {
    renderLanding();
    expect(pageText()).not.toMatch(/\bpremium\b/i);
  });

  it('still says where an Ask Fyn question goes, in the FAQ, now that the Ask Fyn section does not', () => {
    // Owner decision 2026-09-21: the Ask Fyn section does not repeat the Anthropic line. The page as a
    // whole must still tell a visitor where a question goes, so the FAQ answer carries it. The FAQ is
    // an accordion, so on screen the answer is one click away; the static crawler page lists them all.
    renderLanding();
    const item = faq.items.find(([q]) => /AI on my data/i.test(q));
    expect(item, 'the "Does Fynora use AI on my data?" FAQ item is gone').toBeDefined();
    expect(pageText()).toContain(item![0]);
    expect(item![1]).toMatch(/Anthropic/);
    expect(item![1]).toMatch(/Importing a statement does not send it/i);
  });

  it('claims no scanned-statement support', () => {
    renderLanding();
    // OCR exists but its accuracy on real scans is unmeasured, so it is not a claim we can make.
    expect(claimText()).not.toMatch(/scanned|photograph|image[- ]only|\bocr\b/i);
  });
});
