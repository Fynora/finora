import { render } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import TrustSecurity from './TrustSecurity';

describe('TrustSecurity', () => {
  it('does not say Premium -- it is hidden from the UI (owner decision, 2026-09-22)', () => {
    render(
      <MemoryRouter>
        <TrustSecurity />
      </MemoryRouter>
    );
    expect(document.body.textContent ?? '').not.toMatch(/premium/i);
  });
});
