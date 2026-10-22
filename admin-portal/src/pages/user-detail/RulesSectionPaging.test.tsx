import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RulesSection } from './RulesSection';
import { adminUserRulesApi } from '../../api/endpoints';
import type { RuleDto } from '../../types';

vi.mock('../../api/endpoints', () => ({
  adminUserRulesApi: { list: vi.fn(), create: vi.fn(), update: vi.fn(), delete: vi.fn() },
}));

function rule(i: number): RuleDto {
  return { id: `r${i}`, scope: 'USER', field: 'DESCRIPTION', operator: 'CONTAINS', comparisonValue: `keyword${i}`,
    actionType: 'ASSIGN_CATEGORY', actionValue: 'Food', priority: 100, enabled: true, matchCount: 0, lastMatchedAt: null };
}

describe('RulesSection paging', () => {
  it('shows 25 rules a page', async () => {
    vi.mocked(adminUserRulesApi.list).mockResolvedValue(Array.from({ length: 30 }, (_, i) => rule(i)));
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={queryClient}>
        <RulesSection userId="u1" />
      </QueryClientProvider>
    );

    expect(await screen.findByText(/keyword24\b/)).toBeInTheDocument();
    expect(screen.queryByText(/keyword25\b/)).not.toBeInTheDocument();
    expect(screen.getByText('Showing 1–25 of 30')).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: 'Next page' }));
    expect(screen.getByText(/keyword29\b/)).toBeInTheDocument();
    expect(screen.getByText('Showing 26–30 of 30')).toBeInTheDocument();
  });
});
