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

  it('marks the active category and calls onSelect with the tapped key', async () => {
    const onSelect = vi.fn();
    render(<SettingsNav active="security" onSelect={onSelect} />);
    expect(screen.getByRole('button', { name: 'Security' })).toHaveAttribute('aria-current', 'true');
    await userEvent.click(screen.getByRole('button', { name: 'Data' }));
    expect(onSelect).toHaveBeenCalledWith('data');
  });
});
