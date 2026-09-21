import { describe, expect, it } from 'vitest';
import {
  askFyn, capabilities, faq, heroBadges, heroIntelligence, heroScore, importSection, trustStrip,
} from './landing-config';

describe('hero cinematic copy', () => {
  it('keeps the health score within a real 0-100 range', () => {
    expect(heroScore.value).toBeGreaterThanOrEqual(0);
    expect(heroScore.value).toBeLessThanOrEqual(100);
    expect(heroScore.label.length).toBeGreaterThan(0);
    expect(heroScore.delta.length).toBeGreaterThan(0);
  });

  it('has at least one intelligence-scan step', () => {
    expect(heroIntelligence.steps.length).toBeGreaterThan(0);
    heroIntelligence.steps.forEach((step) => expect(step.length).toBeGreaterThan(0));
  });

  it("keeps the salary badge consistent with the dashboard mock's own salary figure", () => {
    // DashboardMock's TRANSACTIONS lists "Salary Credit" at +₹1,24,500 -- a badge quoting a
    // different salary number on the same screen would be the kind of internal inconsistency
    // DashboardMock's own file comment calls out as the first thing a finance-literate visitor
    // notices.
    const salaryBadge = heroBadges.find((b) => b.label.includes('Salary'));
    expect(salaryBadge?.label).toContain('1,24,500');
  });

  it('has at least three floating badges', () => {
    expect(heroBadges.length).toBeGreaterThanOrEqual(3);
  });

  it('does not advertise investments in a hero badge', () => {
    // Investments is a small side feature and is deliberately not marketed.
    expect(heroBadges.map((b) => b.label).join(' ')).not.toMatch(/invest/i);
  });
});

describe('reframed landing copy', () => {
  it('gives the trust strip exactly three commitments', () => {
    expect(trustStrip).toHaveLength(3);
    trustStrip.forEach((line) => expect(line.length).toBeGreaterThan(10));
  });

  it('backs the lead story with four proof cards and a card-statement note', () => {
    expect(importSection.proofs).toHaveLength(4);
    importSection.proofs.forEach((p) => {
      expect(p.title.length).toBeGreaterThan(0);
      expect(p.body.length).toBeGreaterThan(0);
    });
  });

  it('tags only the plan-gated capabilities, and only with Plus', () => {
    const tagged = capabilities.items.filter((i) => i.plan !== null);
    expect(tagged.map((i) => i.title)).toEqual(['Deeper reports', 'Gmail receipts']);
    tagged.forEach((i) => expect(i.plan).toBe('Plus'));
    expect(capabilities.items).toHaveLength(9);
  });

  it('shows the four real Ask Fyn example prompts, one per chat tool', () => {
    // FynWidget.tsx carries exactly these four; each maps to a real tool
    // (GET_BALANCE, GET_RECENT_TRANSACTIONS_SUMMARY, GET_SPEND_BY_CATEGORY, GET_BUDGET_STATUS).
    expect(askFyn.examples).toEqual([
      "What's my balance?",
      'What did I spend this month?',
      'How much did I spend on Dining?',
      'How are my budgets doing?',
    ]);
  });

  it('names the AI vendor in the FAQ answer that says a question leaves Fynora', () => {
    const aiAnswer = faq.items.find(([q]) => /AI on my data/i.test(q));
    expect(aiAnswer?.[1]).toMatch(/Anthropic/);
    expect(aiAnswer?.[1]).toMatch(/Importing a statement does not send it/i);
  });
});
