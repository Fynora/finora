import { render, screen, fireEvent, act } from '@testing-library/react';
import { MemoryRouter, Routes, Route } from 'react-router-dom';
import { describe, it, expect, vi } from 'vitest';
import { SuccessScreen } from './SuccessScreen';

describe('SuccessScreen', () => {
  it('shows all 6 checklist items unchecked', () => {
    render(<MemoryRouter><SuccessScreen onDone={vi.fn()} /></MemoryRouter>);
    expect(screen.getByText(/Complete your profile/)).toBeInTheDocument();
    expect(screen.getByText(/Import first statement/)).toBeInTheDocument();
    expect(screen.getByText(/Review transactions/)).toBeInTheDocument();
    expect(screen.getByText(/Create a budget/)).toBeInTheDocument();
    expect(screen.getByText(/Create a goal/)).toBeInTheDocument();
    expect(screen.getByText(/View insights/)).toBeInTheDocument();
  });

  it('calls onDone when "Go to Dashboard" is clicked', () => {
    const onDone = vi.fn();
    render(<MemoryRouter><SuccessScreen onDone={onDone} /></MemoryRouter>);
    fireEvent.click(screen.getByRole('button', { name: 'Go to Dashboard' }));
    expect(onDone).toHaveBeenCalled();
  });

  it('calls onDone when "Import Statement" (the primary CTA) is clicked', () => {
    const onDone = vi.fn();
    render(<MemoryRouter><SuccessScreen onDone={onDone} /></MemoryRouter>);
    fireEvent.click(screen.getByRole('button', { name: 'Import Statement' }));
    expect(onDone).toHaveBeenCalled();
  });

  it('calls onDone when "Connect Account" is clicked', () => {
    const onDone = vi.fn();
    render(<MemoryRouter><SuccessScreen onDone={onDone} /></MemoryRouter>);
    fireEvent.click(screen.getByRole('button', { name: 'Connect Account' }));
    expect(onDone).toHaveBeenCalled();
  });

  // Bug fix regression test: navigate() used to fire in the same tick as onDone(), not sequenced
  // after it. Since the real onDone (OnboardingFlow's finishOnboarding) is async and awaits
  // onboardingApi.complete() before flipping onboardingCompleted, navigating first meant
  // ProtectedRoute -- which gates on that flag, not the URL -- still rendered onboarding's own
  // Success screen at the destination route until the completion call resolved. Import
  // Statement/Connect Account must not navigate until onDone's promise settles.
  it('waits for onDone to resolve before navigating to the destination route', async () => {
    let resolveOnDone: () => void = () => {};
    const onDone = vi.fn(() => new Promise<void>((resolve) => { resolveOnDone = resolve; }));

    render(
      <MemoryRouter initialEntries={['/success']}>
        <Routes>
          <Route path="/success" element={<SuccessScreen onDone={onDone} />} />
          <Route path="/app/import" element={<div>Import page</div>} />
        </Routes>
      </MemoryRouter>
    );

    fireEvent.click(screen.getByRole('button', { name: 'Import Statement' }));
    expect(onDone).toHaveBeenCalled();
    // Still on /success -- the destination route must not have rendered yet.
    expect(screen.queryByText('Import page')).not.toBeInTheDocument();

    await act(async () => {
      resolveOnDone();
      await Promise.resolve();
    });

    expect(await screen.findByText('Import page')).toBeInTheDocument();
  });

  it('shows the error message when passed one', () => {
    render(<MemoryRouter><SuccessScreen onDone={vi.fn()} error="Something went wrong." /></MemoryRouter>);
    expect(screen.getByText('Something went wrong.')).toBeInTheDocument();
  });

  it('shows no error text when error is not passed', () => {
    render(<MemoryRouter><SuccessScreen onDone={vi.fn()} /></MemoryRouter>);
    expect(screen.queryByText(/went wrong/i)).not.toBeInTheDocument();
  });

  // Bug fix regression test: onDone (OnboardingFlow's finishOnboarding) can reject -- it rethrows
  // after recording the error via setError, specifically so callers like this one can tell the
  // difference between "completed" and "failed" -- and this component used to have no catch at
  // all, so a rejected onDone() both left an unhandled rejection AND (for Import Statement/Connect
  // Account) still needed to skip navigate(), which a bare `await onDone(); navigate(path)` cannot
  // do on its own.
  it('does not navigate when onDone rejects', async () => {
    const onDone = vi.fn().mockRejectedValue(new Error('network error'));

    render(
      <MemoryRouter initialEntries={['/success']}>
        <Routes>
          <Route path="/success" element={<SuccessScreen onDone={onDone} />} />
          <Route path="/app/import" element={<div>Import page</div>} />
        </Routes>
      </MemoryRouter>
    );

    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Import Statement' }));
      await Promise.resolve();
      await Promise.resolve();
    });

    expect(onDone).toHaveBeenCalled();
    expect(screen.queryByText('Import page')).not.toBeInTheDocument();
  });

  it('does not throw an unhandled rejection when "Go to Dashboard" is clicked and onDone rejects', async () => {
    const onDone = vi.fn().mockRejectedValue(new Error('network error'));
    render(<MemoryRouter><SuccessScreen onDone={onDone} /></MemoryRouter>);

    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Go to Dashboard' }));
      await Promise.resolve();
      await Promise.resolve();
    });

    expect(onDone).toHaveBeenCalled();
  });
});
