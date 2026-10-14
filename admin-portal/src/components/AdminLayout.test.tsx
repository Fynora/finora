import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { AdminLayout } from './AdminLayout';
import { useAdminAuth } from '../context/AdminAuthContext';
import { mockAdminAuthState } from '../test/mockAdminAuth';

vi.mock('../context/AdminAuthContext', () => ({
  useAdminAuth: vi.fn(),
}));
// The header widgets have their own tests and data dependencies; only the navigation drawer is
// under test here.
vi.mock('./GlobalSearch', () => ({ GlobalSearch: () => null }));
vi.mock('./ThemeToggle', () => ({ ThemeToggle: () => null }));
vi.mock('./NotificationBell', () => ({ NotificationBell: () => null }));

function renderLayout(initialPath = '/users') {
  vi.mocked(useAdminAuth).mockReturnValue(mockAdminAuthState({
    fullName: 'Support Admin',
    permissions: ['USER_VIEW', 'AUDIT_VIEW'],
    logout: vi.fn(),
  }));
  return render(
    <MemoryRouter initialEntries={[initialPath]}>
      <Routes>
        <Route path="/users" element={<AdminLayout title="Users"><p>users page</p></AdminLayout>} />
        <Route path="/audit" element={<AdminLayout title="Audit Log"><p>audit page</p></AdminLayout>} />
      </Routes>
    </MemoryRouter>
  );
}

async function openDrawer() {
  const user = userEvent.setup();
  await user.click(screen.getByRole('button', { name: 'Open navigation' }));
  return { user, drawer: screen.getByRole('dialog', { name: 'Navigation' }) };
}

describe('AdminLayout navigation drawer', () => {
  beforeEach(() => {
    vi.mocked(useAdminAuth).mockReset();
    localStorage.clear();
    document.body.style.overflow = '';
  });

  it('is closed until the menu button opens it, then shows the same links as the sidebar', async () => {
    renderLayout();
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Open navigation' })).toHaveAttribute('aria-expanded', 'false');

    const { drawer } = await openDrawer();

    expect(drawer).toHaveAttribute('aria-modal', 'true');
    expect(within(drawer).getByRole('link', { name: 'Users' })).toBeInTheDocument();
    expect(within(drawer).getByRole('link', { name: 'Audit Log' })).toBeInTheDocument();
    expect(within(drawer).queryByRole('link', { name: 'Banks' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Open navigation' })).toHaveAttribute('aria-expanded', 'true');
  });

  it('moves focus to its close button and locks page scroll while open', async () => {
    renderLayout();
    await openDrawer();

    expect(screen.getByRole('button', { name: 'Close navigation' })).toHaveFocus();
    expect(document.body.style.overflow).toBe('hidden');
  });

  it('closes on Escape, restores page scroll, and returns focus to the menu button', async () => {
    renderLayout();
    const { user } = await openDrawer();

    await user.keyboard('{Escape}');

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(document.body.style.overflow).toBe('');
    expect(screen.getByRole('button', { name: 'Open navigation' })).toHaveFocus();
  });

  it('closes from the close button', async () => {
    renderLayout();
    const { user } = await openDrawer();

    await user.click(screen.getByRole('button', { name: 'Close navigation' }));

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('closes when the backdrop is clicked', async () => {
    renderLayout();
    const { user, drawer } = await openDrawer();

    const backdrop = drawer.querySelector('[aria-hidden="true"]');
    expect(backdrop).not.toBeNull();
    await user.click(backdrop as Element);

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('closes when the current page\'s own link is followed, which changes no route', async () => {
    renderLayout('/users');
    const { user, drawer } = await openDrawer();

    await user.click(within(drawer).getByRole('link', { name: 'Users' }));

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(screen.getByText('users page')).toBeInTheDocument();
  });

  it('navigates and leaves no drawer behind when another page\'s link is followed', async () => {
    renderLayout('/users');
    const { user, drawer } = await openDrawer();

    await user.click(within(drawer).getByRole('link', { name: 'Audit Log' }));

    expect(screen.getByText('audit page')).toBeInTheDocument();
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(document.body.style.overflow).toBe('');
  });

  it('keeps Tab inside the drawer, wrapping in both directions', async () => {
    renderLayout();
    const { user, drawer } = await openDrawer();
    const closeButton = screen.getByRole('button', { name: 'Close navigation' });
    const logout = within(drawer).getByRole('button', { name: /log out/i });

    await user.tab({ shift: true });
    expect(logout).toHaveFocus();

    await user.tab();
    expect(closeButton).toHaveFocus();
  });
});
