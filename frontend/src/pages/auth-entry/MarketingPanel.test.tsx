import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { MarketingPanel } from './MarketingPanel';

describe('MarketingPanel', () => {
  it('renders the badge, headline, and description passed in as props', () => {
    render(
      <MemoryRouter>
        <MarketingPanel badge="Welcome back" headline="Pick up right where you left off" description="Sign in to see your finances." />
      </MemoryRouter>
    );
    expect(screen.getByText('Welcome back')).toBeInTheDocument();
    expect(screen.getByText('Pick up right where you left off')).toBeInTheDocument();
    expect(screen.getByText('Sign in to see your finances.')).toBeInTheDocument();
  });

  it('renders the fixed feature list regardless of props', () => {
    render(
      <MemoryRouter>
        <MarketingPanel badge="x" headline="x" description="x" />
      </MemoryRouter>
    );
    expect(screen.getByText('Secure & Private')).toBeInTheDocument();
    expect(screen.getByText('Auto Statement Import')).toBeInTheDocument();
    expect(screen.getByText('Net Worth')).toBeInTheDocument();
  });

  // Product decision (2026-09-21): investments are not marketed. This panel is on the public sign-in
  // and sign-up pages, and used to list "Investment Tracking" as a headline feature.
  it('does not market investment tracking', () => {
    const { container } = render(
      <MemoryRouter>
        <MarketingPanel badge="x" headline="x" description="x" />
      </MemoryRouter>
    );
    expect(container.textContent ?? '').not.toMatch(/invest|portfolio/i);
  });
});
