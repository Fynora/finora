import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { CategorizationPane } from './CategorizationPane';
import { workspaceApi } from '../../api/endpoints';

vi.mock('../../api/endpoints', () => ({
  workspaceApi: { getSettings: vi.fn(), updateSettings: vi.fn() },
}));

describe('CategorizationPane', () => {
  it('loads the confidence threshold and saves a change', async () => {
    vi.mocked(workspaceApi.getSettings).mockResolvedValue({ autoApplyConfidenceThreshold: 90 } as never);
    vi.mocked(workspaceApi.updateSettings).mockResolvedValue({ autoApplyConfidenceThreshold: 75 } as never);
    render(<CategorizationPane />);
    const slider = await screen.findByLabelText(/confidence threshold/i);
    expect(slider).toHaveValue('90');
    fireEvent.change(slider, { target: { value: '75' } });
    await userEvent.click(screen.getByRole('button', { name: 'Save setting' }));
    await waitFor(() => expect(workspaceApi.updateSettings).toHaveBeenCalledWith({ autoApplyConfidenceThreshold: 75 }));
  });
});
