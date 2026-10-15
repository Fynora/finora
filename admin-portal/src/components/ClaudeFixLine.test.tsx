import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import ClaudeFixLine from './ClaudeFixLine';
import type { HeldClaudeFix } from '../types';

const fix = (over: Partial<HeldClaudeFix>): HeldClaudeFix => ({
  state: 'NOT_STARTED', reason: null, publicPr: null, privatePr: null, attempt: 0, monthlyCap: 50, ...over,
});

const line = () => screen.getByTestId('claude-fix-line').textContent;

describe('ClaudeFixLine', () => {
  it('says the fix is off when the switch is off and nothing was recorded', () => {
    render(<ClaudeFixLine fix={fix({ state: 'OFF' })} />);
    expect(line()).toBe('Claude fixClaude fix off');
  });

  it('says a hold recorded while the switch was off needs a person', () => {
    render(<ClaudeFixLine fix={fix({ reason: 'OFF' })} />);
    expect(screen.getByText('Needs a person: the Claude fix was off when this was held')).toBeInTheDocument();
  });

  it.each([
    ['DIFFERS', 'Needs a person: the blanked copy does not match'],
    ['NOT_CHECKED', 'Needs a person: the blanked copy was not checked'],
    ['REFUSED', 'Needs a person: the blanked copy was refused'],
    ['CSV', 'Needs a person: the statement is a CSV'],
  ] as const)('says a person is needed when not tried: %s', (reason, words) => {
    render(<ClaudeFixLine fix={fix({ reason })} />);
    expect(screen.getByText(words)).toBeInTheDocument();
  });

  it('names the monthly limit when it is reached', () => {
    render(<ClaudeFixLine fix={fix({ reason: 'CAP_REACHED', monthlyCap: 50 })} />);
    expect(screen.getByText("Needs a person: this month's 50 Claude runs are used")).toBeInTheDocument();
  });

  it('says the fix could not start', () => {
    render(<ClaudeFixLine fix={fix({ reason: 'ERROR' })} />);
    expect(screen.getByText('Needs a person: the Claude fix could not start')).toBeInTheDocument();
  });

  it('says Claude is working, with the attempt', () => {
    render(<ClaudeFixLine fix={fix({ state: 'SENT', attempt: 2 })} />);
    expect(screen.getByText('Claude is working on it (attempt 2 of 2)')).toBeInTheDocument();
  });

  it('links both PRs once Claude opened them', () => {
    render(<ClaudeFixLine fix={fix({ state: 'PR_OPEN', attempt: 1, publicPr: 41, privatePr: 7 })} />);
    expect(line()).toBe('Claude fixClaude opened a fix: PR #41 (Finora), #7 (private)');
    expect(screen.getByRole('link', { name: 'PR #41' })).toHaveAttribute('href', 'https://github.com/Fynora/finora/pull/41');
    expect(screen.getByRole('link', { name: '#7' }))
      .toHaveAttribute('href', 'https://github.com/Fynora/finora-statement-traces/pull/7');
  });

  it('says the deployed fix is re-reading the statement', () => {
    render(<ClaudeFixLine fix={fix({ state: 'REREADING', publicPr: 41, privatePr: 7, attempt: 1 })} />);
    expect(screen.getByText('Fix deployed, re-reading the statement')).toBeInTheDocument();
  });

  it("says which of Claude's PRs fixed it", () => {
    render(<ClaudeFixLine fix={fix({ state: 'FIXED', publicPr: 41, privatePr: 7, attempt: 1 })} />);
    expect(line()).toBe("Claude fixFixed by Claude's PR #41");
    expect(screen.getByRole('link', { name: 'PR #41' })).toHaveAttribute('href', 'https://github.com/Fynora/finora/pull/41');
  });

  it.each(['RUN_FAILED', 'NO_PR'] as const)('says Claude gave up after 2 attempts: %s', (reason) => {
    render(<ClaudeFixLine fix={fix({ state: 'GAVE_UP', reason, attempt: 2 })} />);
    expect(screen.getByText('Needs a person: Claude gave up after 2 attempts')).toBeInTheDocument();
  });

  it('says Claude gave up after one attempt when its first PR was closed', () => {
    render(<ClaudeFixLine fix={fix({ state: 'GAVE_UP', reason: 'NO_PR', attempt: 1 })} />);
    expect(screen.getByText('Needs a person: Claude gave up after 1 attempt')).toBeInTheDocument();
  });

  it('says the deployed fix left the statement held', () => {
    render(<ClaudeFixLine fix={fix({ state: 'GAVE_UP', reason: 'STILL_HELD', attempt: 1 })} />);
    expect(screen.getByText("Needs a person: Claude's fix was deployed but the statement is still held"))
      .toBeInTheDocument();
  });

  it('says a person decided the hold', () => {
    render(<ClaudeFixLine fix={fix({ state: 'GAVE_UP', reason: 'DECIDED', attempt: 1 })} />);
    expect(screen.getByText('Claude stopped: a person decided this hold')).toBeInTheDocument();
  });

  it('shows nothing while the switch is on and nothing is recorded, or from an older backend', () => {
    const { container, rerender } = render(<ClaudeFixLine fix={null} />);
    expect(container).toBeEmptyDOMElement();
    rerender(<ClaudeFixLine fix={undefined} />);
    expect(container).toBeEmptyDOMElement();
  });
});
