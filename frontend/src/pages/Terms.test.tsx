import { render } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import Terms from './Terms';

describe('Terms', () => {
  it('describes only Free and Plus -- Premium is hidden from the UI (owner decision, 2026-09-22)', () => {
    render(
      <MemoryRouter>
        <Terms />
      </MemoryRouter>
    );
    const text = document.body.textContent ?? '';
    expect(text).not.toMatch(/premium/i);
    expect(text).toMatch(/Fynora offers Free and Plus plans/i);
  });

  // The Terms once said Fynora used no third-party AI on financial data, while Ask Fyn, the
  // Insights summary and hand-typed category suggestions all went to Anthropic -- a contradiction
  // with the Privacy Policy, found in review.
  it('names the three features that use Anthropic, and never claims no third-party AI is used', () => {
    render(
      <MemoryRouter>
        <Terms />
      </MemoryRouter>
    );
    const text = document.body.textContent ?? '';
    expect(text).not.toMatch(/does not use third-party AI\s+services/i);
    expect(text).toMatch(/Anthropic's Claude: Ask Fyn, the summary on the Insights page, and category suggestions/i);
  });
});
