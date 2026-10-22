import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RelationshipsSection } from './RelationshipsSection';
import { adminUserRelationshipsApi } from '../../api/endpoints';
import type { RelationshipDto } from '../../types';

vi.mock('../../api/endpoints', () => ({
  adminUserRelationshipsApi: { list: vi.fn(), merge: vi.fn(), update: vi.fn(), delete: vi.fn(), create: vi.fn() },
}));
vi.mock('../../context/NotificationContext', () => ({
  useNotify: () => ({ success: vi.fn(), error: vi.fn() }),
}));

function relationship(i: number): RelationshipDto {
  return { id: `r${i}`, label: `Person ${i}`, relationshipType: 'FRIEND', linkedAccountId: null, identifiers: [] };
}

describe('RelationshipsSection', () => {
  // The same n-squared shape that froze the merchants list: one merge picker per row, each listing
  // every other relationship. Built only for the row being merged now.
  it('builds no merge picker until a row asks for one', async () => {
    vi.mocked(adminUserRelationshipsApi.list).mockResolvedValue(Array.from({ length: 200 }, (_, i) => relationship(i)));
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const { container } = render(
      <QueryClientProvider client={queryClient}>
        <RelationshipsSection userId="u1" />
      </QueryClientProvider>
    );

    expect(await screen.findByText('Person 24')).toBeInTheDocument();
    expect(screen.queryByText('Person 25')).not.toBeInTheDocument();
    expect(screen.getByText('Showing 1–25 of 200')).toBeInTheDocument();
    expect(container.querySelectorAll('select[aria-label="Merge from"]')).toHaveLength(0);

    await userEvent.click(screen.getAllByRole('button', { name: 'Merge…' })[0]);

    expect(screen.getAllByRole('combobox', { name: 'Merge from' })).toHaveLength(1);
    expect(container.querySelectorAll('select[aria-label="Merge from"] option')).toHaveLength(200);
  });
});
