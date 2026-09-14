import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { AccountPane } from './AccountPane';

describe('AccountPane', () => {
  it('renders both Deactivate and Delete as outline buttons, not filled primary/premium ones', () => {
    render(<AccountPane loading={false} loadError={false} signInMethod="PASSWORD" />);
    // Regression guard, not a behavior change -- Button.tsx's `danger`/`secondary` variants were
    // already outline-only (no filled background) before this extraction; this just proves the
    // move didn't accidentally pick up variant="primary" or "premium" along the way.
    for (const name of ['Deactivate Account', 'Delete Account']) {
      const btn = screen.getByRole('button', { name });
      expect(btn.className).not.toMatch(/bg-primary\b/);
      expect(btn.className).not.toMatch(/bg-premium/);
    }
  });
});
