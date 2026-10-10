import { StyleSheet } from 'react-native';
import { fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ChecklistWidget } from './ChecklistWidget';
import { onboardingApi } from '../api/endpoints';

jest.mock('../api/endpoints', () => ({ onboardingApi: { getChecklist: jest.fn() } }));

function renderWithClient() {
  // gcTime: 0 -- see mobile/src/screens/SubscriptionScreen.test.tsx's own comment on this exact
  // line: without it, a successful query here schedules a real 5-minute GC timer nothing ever
  // cancels.
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return render(
    <QueryClientProvider client={client}>
      <ChecklistWidget />
    </QueryClientProvider>
  );
}

describe('ChecklistWidget', () => {
  it('shows progress text for a partially complete checklist', async () => {
    (onboardingApi.getChecklist as jest.Mock).mockResolvedValue({
      items: [{ key: 'COMPLETE_PROFILE', completed: true }],
      completedCount: 4, totalCount: 6,
    });
    renderWithClient();
    await waitFor(() => expect(screen.getByText('4 / 6 Complete')).toBeTruthy());
  });

  it('renders nothing once completedCount equals totalCount', async () => {
    (onboardingApi.getChecklist as jest.Mock).mockResolvedValue({
      items: [], completedCount: 6, totalCount: 6,
    });
    renderWithClient();
    await waitFor(() => expect(screen.queryByText(/Getting Started/)).toBeNull());
  });

  it('starts collapsed -- item labels are not shown until the header is pressed', async () => {
    (onboardingApi.getChecklist as jest.Mock).mockResolvedValue({
      items: [{ key: 'COMPLETE_PROFILE', completed: true }],
      completedCount: 1, totalCount: 6,
    });
    renderWithClient();

    await waitFor(() => expect(screen.getByText('Getting Started')).toBeTruthy());
    expect(screen.getByText('1 / 6 Complete')).toBeTruthy();
    expect(screen.queryByText(/Complete your profile/)).toBeNull();
  });

  it('expands to show items on press, collapses again on a second press', async () => {
    (onboardingApi.getChecklist as jest.Mock).mockResolvedValue({
      items: [
        { key: 'COMPLETE_PROFILE', completed: true },
        { key: 'IMPORT_STATEMENT', completed: false },
        { key: 'REVIEW_TRANSACTIONS', completed: false },
        { key: 'CREATE_BUDGET', completed: false },
        { key: 'CREATE_GOAL', completed: false },
        { key: 'VIEW_INSIGHTS', completed: false },
      ],
      completedCount: 1, totalCount: 6,
    });
    renderWithClient();
    const header = await screen.findByRole('button', { name: /Getting Started/ });

    fireEvent.press(header);
    expect(await screen.findByText(/Complete your profile/)).toBeTruthy();
    expect(screen.getByText(/Import first statement/)).toBeTruthy();

    fireEvent.press(header);
    await waitFor(() => expect(screen.queryByText(/Complete your profile/)).toBeNull());
  });

  // The rows used to carry a check-mark emoji as text. The indicator is drawn now, so each row
  // says in words whether it is done, or a screen reader would hear six bare labels.
  it('says in words whether each item is done', async () => {
    (onboardingApi.getChecklist as jest.Mock).mockResolvedValue({
      items: [
        { key: 'COMPLETE_PROFILE', completed: true },
        { key: 'IMPORT_STATEMENT', completed: false },
      ],
      completedCount: 1, totalCount: 6,
    });
    renderWithClient();
    fireEvent.press(await screen.findByRole('button', { name: /Getting Started/ }));

    expect(await screen.findByLabelText('Complete your profile, done')).toBeTruthy();
    expect(screen.getByLabelText('Import first statement, not done')).toBeTruthy();
  });

  it('draws the progress bar at the completed share once expanded', async () => {
    (onboardingApi.getChecklist as jest.Mock).mockResolvedValue({
      items: [{ key: 'COMPLETE_PROFILE', completed: true }],
      completedCount: 3, totalCount: 6,
    });
    renderWithClient();
    fireEvent.press(await screen.findByRole('button', { name: /Getting Started/ }));

    const fill = await screen.findByTestId('checklist-progress-fill', { includeHiddenElements: true });
    expect(StyleSheet.flatten(fill.props.style).width).toBe('50%');
  });
});
