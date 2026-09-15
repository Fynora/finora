import { describe, it, expect, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { CategoriesSection } from './CategoriesSection';
import { adminUserCategoriesApi } from '../../api/endpoints';

vi.mock('../../api/endpoints', () => ({
  adminUserCategoriesApi: { aiCreated: vi.fn() },
}));

function renderSection() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <CategoriesSection userId="u1" />
    </QueryClientProvider>
  );
}

describe('CategoriesSection', () => {
  it('lists AI-created categories with their reasons', async () => {
    vi.mocked(adminUserCategoriesApi.aiCreated).mockResolvedValue([
      { id: '1', name: 'Pet Care', isSystem: false, icon: 'tag', color: 'gray',
        aiCreationReason: 'Pet supplies retailer, no existing match' },
    ]);

    renderSection();

    await waitFor(() => expect(screen.getByText('Pet Care')).toBeInTheDocument());
    expect(screen.getByText(/pet supplies retailer/i)).toBeInTheDocument();
  });

  it('shows an empty state when the user has no Fynora-created categories', async () => {
    vi.mocked(adminUserCategoriesApi.aiCreated).mockResolvedValue([]);

    renderSection();

    await waitFor(() => expect(screen.getByText(/no fynora-created categories/i)).toBeInTheDocument());
  });
});
