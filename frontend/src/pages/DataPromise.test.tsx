import { render } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import DataPromise from './DataPromise';

describe('DataPromise', () => {
  it('does not say Premium in the rendered page -- it is hidden from the UI (owner decision, 2026-09-22)', () => {
    render(
      <MemoryRouter>
        <DataPromise />
      </MemoryRouter>
    );
    expect(document.body.textContent ?? '').not.toMatch(/premium/i);
  });
});
