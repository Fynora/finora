import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import Help from './Help';

// Public page, but PublicLayout pulls in auth-aware chrome; nothing here should reach the network.
vi.mock('../api/endpoints', () => ({
  authApi: { login: vi.fn(), logout: vi.fn(), register: vi.fn(), refresh: vi.fn().mockRejectedValue(new Error('no session')) },
  userApi: { get: vi.fn(), update: vi.fn() },
}));

function renderHelp() {
  return render(<MemoryRouter><Help /></MemoryRouter>);
}

describe('Help center', () => {
  it('still lists the other topics', () => {
    renderHelp();
    expect(screen.getByRole('button', { name: 'Importing Statements' })).toBeInTheDocument();
    expect(screen.getByText('What is Fynora?')).toBeInTheDocument();
  });

  // Gmail sync is paused (lib/features.ts): help for a feature users cannot find would only confuse.
  it('has no Gmail Sync topic while Gmail sync is paused', () => {
    renderHelp();

    expect(screen.queryByRole('button', { name: 'Gmail Sync' })).not.toBeInTheDocument();
    expect(screen.queryByText('What is Gmail Sync?')).not.toBeInTheDocument();
  });

  it('does not surface the hidden Gmail answers through search either', async () => {
    renderHelp();

    await userEvent.type(screen.getByRole('textbox'), 'gmail');

    // Nothing matches at all (the page says so, and that message echoes the query, so it cannot
    // be asserted by searching for the word), and none of the hidden answers appear.
    expect(screen.getByText(/No articles match/)).toBeInTheDocument();
    expect(screen.queryByText('What is Gmail Sync?')).not.toBeInTheDocument();
    expect(screen.queryByText(/gmail\.readonly/)).not.toBeInTheDocument();
    expect(screen.queryByText(/Can I disconnect Gmail Sync/)).not.toBeInTheDocument();
  });
});
