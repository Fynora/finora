import { render } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import Privacy from './Privacy';

function policyText(): string {
  render(
    <MemoryRouter>
      <Privacy />
    </MemoryRouter>
  );
  return document.body.textContent ?? '';
}

/**
 * The public page makes claims about AI, shared learning and Gmail. The policy must say the same
 * things, or the page outruns it. Each assertion below is a fact read from code:
 *   - hand-typed transaction to the AI: TransactionService -> CategorizationService.suggest ->
 *     MerchantUnderstandingService (sends the description only, "never the amount, account, or any
 *     other user-identifying detail").
 *   - shared learning: SharedCorpusService (>= 3 distinct users, only vpa:-keyed BUSINESS or
 *     FINANCIAL_INSTITUTION payees), SharedCorpusRetentionSweepService (6 / 12 months), and
 *     AccountPurgeSweepService (deletes the user's own observations only).
 *   - Gmail examples: only parsers that are switched on by default (Amazon, Booking, Myntra, Ola);
 *     PhonePe/CRED/Paytm default off and there is no Uber parser class.
 */
describe('Privacy policy matches what the product does', () => {
  it('discloses that a hand-typed transaction can reach the AI for categorisation', () => {
    expect(policyText()).toMatch(/add a transaction by hand without choosing a category/i);
  });

  it('no longer claims categorisation never involves a third-party AI', () => {
    expect(policyText()).not.toMatch(/not a third-party AI service — see\s+Uploaded Statements above/);
  });

  it('describes learning across users: what is recorded, the threshold, what is shared, and deletion', () => {
    const t = policyText();
    expect(t).toMatch(/several separate users/i);
    expect(t).toMatch(/payee ID/i);
    expect(t).toMatch(/holds no amounts, dates, names or statements/i);
    expect(t).toMatch(/If you delete your account, your own entries/i);
  });

  it('lists only Gmail example senders that actually have a parser switched on', () => {
    const t = policyText();
    expect(t).toMatch(/for example Amazon, Myntra or Ola/i);
    expect(t).not.toMatch(/Uber|PhonePe/);
  });
});
