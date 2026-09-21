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
 *     MerchantUnderstandingService (sends the description only; the description is free text, so the
 *     policy must not claim it holds no identifying detail).
 *   - shared learning: SharedCorpusService (>= 3 distinct users, only vpa:-keyed payees the
 *     classifier calls BUSINESS or FINANCIAL_INSTITUTION), SharedCorpusRetentionSweepService (only
 *     keys with one or two voters and no shared row, 180 / 365 days from the most recent entry), and
 *     AccountPurgeSweepService (deletes the user's own observations only).
 *   - the classifier is a heuristic and has been wrong before, so "individuals are never recorded"
 *     would promise an outcome the code cannot guarantee.
 *   - Gmail examples: only parsers that are switched on by default (Amazon, Booking, Myntra, Ola);
 *     PhonePe/CRED/Paytm default off and there is no Uber parser class.
 */
describe('Privacy policy matches what the product does', () => {
  it('discloses that a hand-typed transaction can reach the AI for categorisation', () => {
    expect(policyText()).toMatch(/add a transaction by hand without choosing a category/i);
  });

  it('says the typed description is free text, and that importing a statement does not do this', () => {
    const t = policyText();
    expect(t).toMatch(/description is free text and may contain personal information/i);
    expect(t).toMatch(/Importing a statement does not do this/i);
    expect(t).not.toMatch(/without your amount, account or any other identifying detail/i);
    // The stored value is whatever the service returns; the code does not guarantee it is a merchant description.
    expect(t).toMatch(/short description returned by the service is stored\s+against the payee ID/i);
    expect(t).not.toMatch(/short description of the merchant/i);
  });

  it('no longer claims categorisation never involves a third-party AI', () => {
    const t = policyText();
    expect(t).not.toMatch(/not a third-party AI service — see\s+Uploaded Statements above/);
    expect(t).toMatch(/except for the two uses of an AI service/i);
  });

  it('describes learning across users: what is recorded, the threshold, what is shared, and deletion', () => {
    const t = policyText();
    expect(t).toMatch(/several separate users/i);
    expect(t).toMatch(/payee ID/i);
    expect(t).toMatch(/does not include your transaction amounts, transaction dates, account ID or statement files/i);
    expect(t).toMatch(/If you delete your account, the entries in the log that belong to your account/i);
    // Only what the code shows: a separate table with no account ID, untouched by the purge. No stated reason.
    expect(t).toMatch(
      /Shared suggestions are stored separately from\s+individual user log entries, hold no account ID, and are not removed when an account is deleted/i
    );
    expect(t).not.toMatch(/because they are about\s+the payee/i);
  });

  it('does not promise that individuals are never recorded, because the classifier can be wrong', () => {
    const t = policyText();
    expect(t).not.toMatch(/never recorded/i);
    expect(t).toMatch(/classification is automatic and can be wrong/i);
    expect(t).toMatch(/classified as an individual, or not classified, are not recorded/i);
  });

  it('discloses the retention that applies and the entries that have no scheduled deletion', () => {
    const t = policyText();
    expect(t).toMatch(/180 days after the most recent entry/i);
    expect(t).toMatch(/365 days after the most recent entry/i);
    expect(t).toMatch(/three or more users but no shared\s+suggestion, currently have no scheduled deletion date/i);
  });

  it('lists Anthropic as a provider, and covers AI features in the cross-border note', () => {
    const t = policyText();
    expect(t).toMatch(/Anthropic\s*—\s*the AI service behind Ask Fyn/i);
    expect(t).toMatch(/authentication, communications and AI\s+features are also based outside India/i);
  });

  it('lists only Gmail example senders that actually have a parser switched on', () => {
    const t = policyText();
    expect(t).toMatch(/for example Amazon, Myntra or Ola/i);
    expect(t).not.toMatch(/Uber|PhonePe/);
  });
});
