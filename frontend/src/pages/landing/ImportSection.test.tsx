import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { importSection } from './landing-config';
import { ImportSection } from './ImportSection';

describe('ImportSection', () => {
  it('renders the real copy outside any aria-hidden wrapper', () => {
    // The heading's text is split across a <br/> (title + titleLine2 as separate text nodes), so
    // getByText can't match "Upload once." alone -- query the heading element directly instead.
    const { container } = render(<ImportSection />);
    const heading = container.querySelector('h2');
    expect(heading?.textContent).toContain(importSection.title);
    expect(heading?.textContent).toContain(importSection.titleLine2);
    expect(heading?.closest('[aria-hidden="true"]')).toBeNull();
    expect(screen.getByText(importSection.blurb)).toBeInTheDocument();
  });

  it('carries the four proof cards under the lead story', () => {
    const { container } = render(<ImportSection />);
    expect(container.querySelector('#how')).not.toBeNull();
    importSection.proofs.forEach((p) => {
      expect(screen.getByText(p.title)).toBeInTheDocument();
      expect(screen.getByText(p.body)).toBeInTheDocument();
    });
  });

  it('carries no credit-card line (owner decision 2026-09-21)', () => {
    const { container } = render(<ImportSection />);
    expect(container.textContent).not.toMatch(/credit card/i);
  });

  it('renders the reveal-once scene', () => {
    const { container } = render(<ImportSection />);
    expect(container.querySelector('[aria-hidden="true"]')).toBeInTheDocument();
  });
});
