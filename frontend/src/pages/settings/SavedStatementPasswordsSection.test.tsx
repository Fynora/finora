import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { SavedStatementPasswordsSection } from './SavedStatementPasswordsSection';
import { statementPasswordsApi } from '../../api/endpoints';

vi.mock('../../api/endpoints', () => ({
  statementPasswordsApi: { list: vi.fn(), remove: vi.fn(), removeAll: vi.fn() },
}));

const item = (id: string, fileName: string) => ({
  statementImportId: id, fileName, accountName: 'Savings',
  periodStart: '2026-07-01', periodEnd: '2026-07-31', savedAt: '2026-08-02T10:00:00Z',
});

describe('SavedStatementPasswordsSection', () => {
  beforeEach(() => {
    vi.mocked(statementPasswordsApi.list).mockReset();
    vi.mocked(statementPasswordsApi.remove).mockReset().mockResolvedValue(undefined as never);
    vi.mocked(statementPasswordsApi.removeAll).mockReset().mockResolvedValue({ removed: 2 });
  });

  it('lists the statements with a saved password and removes one', async () => {
    vi.mocked(statementPasswordsApi.list).mockResolvedValue({
      saveAvailable: true, items: [item('s1', 'july.pdf'), item('s2', 'june.pdf')],
    });
    const user = userEvent.setup();
    render(<SavedStatementPasswordsSection />);

    expect(await screen.findByText('july.pdf')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: /remove saved password for july.pdf/i }));

    await waitFor(() => expect(statementPasswordsApi.remove).toHaveBeenCalledWith('s1'));
    await waitFor(() => expect(screen.queryByText('july.pdf')).not.toBeInTheDocument());
    expect(screen.getByText('june.pdf')).toBeInTheDocument();
  });

  it('removes all only after the user confirms', async () => {
    vi.mocked(statementPasswordsApi.list).mockResolvedValue({ saveAvailable: true, items: [item('s1', 'july.pdf')] });
    const user = userEvent.setup();
    render(<SavedStatementPasswordsSection />);

    await user.click(await screen.findByRole('button', { name: /^remove all$/i }));
    expect(statementPasswordsApi.removeAll).not.toHaveBeenCalled();
    const buttons = screen.getAllByRole('button', { name: /^remove all$/i });
    await user.click(buttons[buttons.length - 1]);

    await waitFor(() => expect(statementPasswordsApi.removeAll).toHaveBeenCalledTimes(1));
    expect(await screen.findByText(/haven't saved any statement passwords/i)).toBeInTheDocument();
  });

  it('shows nothing while saving is off and nothing was ever saved', async () => {
    vi.mocked(statementPasswordsApi.list).mockResolvedValue({ saveAvailable: false, items: [] });
    const { container } = render(<SavedStatementPasswordsSection />);
    await waitFor(() => expect(statementPasswordsApi.list).toHaveBeenCalled());
    expect(container).toBeEmptyDOMElement();
  });

  it('still lets the user remove what was saved after saving is switched off', async () => {
    vi.mocked(statementPasswordsApi.list).mockResolvedValue({ saveAvailable: false, items: [item('s1', 'july.pdf')] });
    render(<SavedStatementPasswordsSection />);
    expect(await screen.findByRole('button', { name: /remove saved password for july.pdf/i })).toBeInTheDocument();
  });
});
