import { render } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import Help from './Help';

describe('Help', () => {
  it('has no Gmail Sync category or answers (dropped for v1, owner decision 2026-09-21)', () => {
    // Help said Gmail Sync was "Premium only" while the landing page listed it under Plus. The feature
    // is no longer offered, so neither surface may describe it.
    render(
      <MemoryRouter>
        <Help />
      </MemoryRouter>
    );
    expect(document.body.textContent ?? '').not.toMatch(/gmail/i);
  });

  it('does not say Premium -- it is hidden from the UI (owner decision, 2026-09-22)', () => {
    render(
      <MemoryRouter>
        <Help />
      </MemoryRouter>
    );
    const text = document.body.textContent ?? '';
    expect(text).not.toMatch(/premium/i);
    expect(text).toMatch(/Two: Free and Plus/i);
  });
});
