import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { SettingsNav, SETTINGS_CATEGORIES } from './SettingsNav';

describe('SettingsNav', () => {
  it('renders every category with sentence-case labels', () => {
    render(<SettingsNav active="general" onSelect={vi.fn()} />);
    for (const c of SETTINGS_CATEGORIES) {
      expect(screen.getByRole('button', { name: c.label })).toBeInTheDocument();
      expect(c.label).not.toBe(c.label.toUpperCase());
    }
  });

  // Gmail sync is paused (lib/features.ts), and Connected Apps holds nothing else on the web.
  it('offers no Connected Apps tab while Gmail sync is paused, and keeps the other seven in order', () => {
    render(<SettingsNav active="general" onSelect={vi.fn()} />);

    expect(screen.queryByRole('button', { name: 'Connected Apps' })).not.toBeInTheDocument();
    expect(SETTINGS_CATEGORIES.map((c) => c.key)).toEqual(
      ['general', 'security', 'notifications', 'categorization', 'data', 'bank-sync', 'account'],
    );
  });

  it('brings the Connected Apps tab back, in its old place, when Gmail sync is switched on', async () => {
    vi.resetModules();
    vi.doMock('../../lib/features', () => ({ GMAIL_SYNC_UI_ENABLED: true }));
    try {
      const enabled = await import('./SettingsNav');
      expect(enabled.SETTINGS_CATEGORIES.map((c) => c.key)).toEqual(
        ['general', 'security', 'notifications', 'categorization', 'data', 'connected-apps', 'bank-sync', 'account'],
      );
    } finally {
      vi.doUnmock('../../lib/features');
      vi.resetModules();
    }
  });

  it('marks the active category and calls onSelect with the tapped key', async () => {
    const onSelect = vi.fn();
    render(<SettingsNav active="security" onSelect={onSelect} />);
    expect(screen.getByRole('button', { name: 'Security' })).toHaveAttribute('aria-current', 'true');
    await userEvent.click(screen.getByRole('button', { name: 'Data' }));
    expect(onSelect).toHaveBeenCalledWith('data');
  });
});
