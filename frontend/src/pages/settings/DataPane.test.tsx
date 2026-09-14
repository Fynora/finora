import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { DataPane } from './DataPane';
import { analyticsApi } from '../../api/endpoints';

vi.mock('../../api/endpoints', () => ({
  analyticsApi: { importStatistics: vi.fn() },
}));

describe('DataPane', () => {
  it('renders import statistics once loaded', async () => {
    vi.mocked(analyticsApi.importStatistics).mockResolvedValue({
      totalStatements: 12, totalTransactionsImported: 340, totalTransactionsSkipped: 2, lastImportedAt: '2026-08-01T00:00:00Z',
    } as never);
    render(<DataPane loading={false} loadError={false} signInMethod="PASSWORD" />);
    expect(await screen.findByText('12')).toBeInTheDocument();
  });

  it('disables Export My Data while the account is still loading', () => {
    render(<DataPane loading={true} loadError={false} signInMethod="PASSWORD" />);
    expect(screen.getByRole('button', { name: 'Export My Data' })).toBeDisabled();
  });
});
