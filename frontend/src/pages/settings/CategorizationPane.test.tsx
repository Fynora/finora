import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { CategorizationPane } from './CategorizationPane';
import { workspaceApi } from '../../api/endpoints';

vi.mock('../../api/endpoints', () => ({
  workspaceApi: { getSettings: vi.fn(), updateSettings: vi.fn() },
  // The money-kinds sections below the threshold (Plan 2) have their own tests.
  inflowApi: { kinds: vi.fn().mockResolvedValue([]), senderRules: vi.fn().mockResolvedValue([]) },
}));

/** These components refresh cached money figures after a change, so they need a QueryClient. */
function renderQ(ui: React.ReactElement) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(ui, { wrapper: ({ children }) => <QueryClientProvider client={client}>{children}</QueryClientProvider> });
}

describe('CategorizationPane', () => {
  it('loads the confidence threshold and saves a change', async () => {
    vi.mocked(workspaceApi.getSettings).mockResolvedValue({ autoApplyConfidenceThreshold: 90 } as never);
    vi.mocked(workspaceApi.updateSettings).mockResolvedValue({ autoApplyConfidenceThreshold: 75 } as never);
    renderQ(<CategorizationPane />);
    const slider = await screen.findByLabelText(/confidence threshold/i);
    expect(slider).toHaveValue('90');
    fireEvent.change(slider, { target: { value: '75' } });
    await userEvent.click(screen.getByRole('button', { name: 'Save setting' }));
    await waitFor(() => expect(workspaceApi.updateSettings).toHaveBeenCalledWith({ autoApplyConfidenceThreshold: 75 }));
  });

  it('shows an error message, not a silent 90% default, when the workspace settings fail to load', async () => {
    vi.mocked(workspaceApi.getSettings).mockRejectedValue(new Error('network down'));
    renderQ(<CategorizationPane />);
    expect(await screen.findByText("Couldn't load your settings — please try again later.")).toBeInTheDocument();
    expect(screen.queryByLabelText(/confidence threshold/i)).toBeNull();
  });
});
