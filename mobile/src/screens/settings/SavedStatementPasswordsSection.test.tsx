import { AppAlert } from '../../lib/appAlert';
import { fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { SavedStatementPasswordsSection } from './SavedStatementPasswordsSection';
import { statementPasswordsApi } from '../../api/endpoints';
import { ThemeProvider } from '../../theme';

jest.mock('../../api/endpoints', () => ({
  statementPasswordsApi: { list: jest.fn(), remove: jest.fn(), removeAll: jest.fn() },
}));

const api = statementPasswordsApi as jest.Mocked<typeof statementPasswordsApi>;

function renderSection() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <ThemeProvider>
        <SavedStatementPasswordsSection />
      </ThemeProvider>
    </QueryClientProvider>
  );
}

const item = (id: string, fileName: string) => ({
  statementImportId: id, fileName, accountName: 'Savings',
  periodStart: '2026-07-01', periodEnd: '2026-07-31', savedAt: '2026-08-02T10:00:00Z',
});

describe('SavedStatementPasswordsSection', () => {
  beforeEach(() => {
    api.list.mockReset();
    api.remove.mockReset().mockResolvedValue(undefined as never);
    api.removeAll.mockReset().mockResolvedValue({ removed: 1 });
  });

  it('lists the statements with a saved password and removes one', async () => {
    api.list.mockResolvedValueOnce({ saveAvailable: true, items: [item('s1', 'july.pdf'), item('s2', 'june.pdf')] })
      .mockResolvedValue({ saveAvailable: true, items: [item('s2', 'june.pdf')] });
    renderSection();

    expect(await screen.findByText('july.pdf')).toBeTruthy();
    fireEvent.press(screen.getByTestId('remove-saved-password-s1'));

    await waitFor(() => expect(api.remove).toHaveBeenCalledWith('s1'));
    await waitFor(() => expect(screen.queryByText('july.pdf')).toBeNull());
    expect(screen.getByText('june.pdf')).toBeTruthy();
  });

  it('removes all only after the user confirms', async () => {
    api.list.mockResolvedValueOnce({ saveAvailable: true, items: [item('s1', 'july.pdf')] })
      .mockResolvedValue({ saveAvailable: true, items: [] });
    const alert = jest.spyOn(AppAlert, 'alert').mockImplementation(() => {});
    renderSection();

    fireEvent.press(await screen.findByTestId('remove-all-saved-passwords'));
    expect(api.removeAll).not.toHaveBeenCalled();
    const buttons = alert.mock.calls[0][2]!;
    buttons.find((b) => b.text === 'Remove all')!.onPress!();

    await waitFor(() => expect(api.removeAll).toHaveBeenCalledTimes(1));
    expect(await screen.findByText(/haven't saved any statement passwords/i)).toBeTruthy();
    alert.mockRestore();
  });

  it('shows nothing while saving is off and nothing was ever saved', async () => {
    api.list.mockResolvedValue({ saveAvailable: false, items: [] });
    renderSection();
    await waitFor(() => expect(api.list).toHaveBeenCalled());
    expect(screen.queryByTestId('saved-statement-passwords')).toBeNull();
  });

  it('still lets the user remove what was saved after saving is switched off', async () => {
    api.list.mockResolvedValue({ saveAvailable: false, items: [item('s1', 'july.pdf')] });
    renderSection();
    expect(await screen.findByTestId('remove-saved-password-s1')).toBeTruthy();
  });
});
