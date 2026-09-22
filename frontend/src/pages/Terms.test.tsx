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
});
