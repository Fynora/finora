import { render, screen, fireEvent, waitFor } from '@testing-library/react-native';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { JourneyScreen } from './JourneyScreen';
import { dashboardApi } from '../api/endpoints';
import { usePreventScreenCapture } from '../lib/screenCapture';

jest.mock('../api/endpoints', () => ({
  dashboardApi: { timeline: jest.fn() },
}));

const api = dashboardApi as jest.Mocked<typeof dashboardApi>;

function event(overrides: Record<string, unknown> = {}) {
  return {
    eventType: 'FIRST_IMPORT', bucket: 'STARTING', importance: 'MAJOR', permanent: true,
    title: 'Imported your first statement', detail: null, occurredAt: '2026-03-10T09:00:00Z', ...overrides,
  };
}

function renderScreen() {
  const navigate = jest.fn();
  // gcTime: 0 -- see SubscriptionScreen.test.tsx's own comment on this exact line.
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  render(
    <QueryClientProvider client={queryClient}>
      <JourneyScreen navigation={{ navigate } as any} route={{ key: 'j', name: 'Journey' } as any} />
    </QueryClientProvider>
  );
  return navigate;
}

describe('JourneyScreen', () => {
  beforeEach(() => api.timeline.mockReset());

  it('groups milestones under their year, newest year first, with detail and badge', async () => {
    api.timeline.mockResolvedValue([
      event({ title: 'Completed Emergency Fund', eventType: 'GOAL_COMPLETED', importance: 'LANDMARK',
        detail: 'Goal of ₹1,00,000', occurredAt: '2026-06-01T12:00:00Z' }),
      event({ title: 'Older milestone', occurredAt: '2025-06-01T12:00:00Z' }),
    ] as any);
    renderScreen();

    expect(await screen.findByText('Completed Emergency Fund')).toBeTruthy();
    expect(screen.getByText('Goal of ₹1,00,000')).toBeTruthy();
    expect(screen.getByText('Goal Achiever')).toBeTruthy();
    const years = screen.getAllByRole('header').map((h) => h.props.children);
    expect(years).toEqual(['2026', '2025']);
  });

  it('shows a spinner, not the empty state, while the timeline is loading', () => {
    api.timeline.mockReturnValue(new Promise(() => {}));
    renderScreen();

    expect(screen.queryByText(/Your journey starts here/)).toBeNull();
  });

  it('shows the empty state for a user with no milestones yet', async () => {
    api.timeline.mockResolvedValue([]);
    renderScreen();

    expect(await screen.findByText(/Your journey starts here/)).toBeTruthy();
  });

  it('shows a load failure rather than a false empty state', async () => {
    api.timeline.mockRejectedValue(new Error('boom'));
    renderScreen();

    expect(await screen.findByText(/Couldn't load your journey/)).toBeTruthy();
    expect(screen.queryByText(/Your journey starts here/)).toBeNull();
  });

  it('blocks screenshots and screen recording, since milestones name real goals and net worth', async () => {
    api.timeline.mockResolvedValue([event()] as any);
    renderScreen();
    await screen.findByText('Imported your first statement');

    expect(usePreventScreenCapture).toHaveBeenCalled();
  });

  it('opens the year in review', async () => {
    api.timeline.mockResolvedValue([event()] as any);
    const navigate = renderScreen();

    fireEvent.press(await screen.findByText('See your year in review'));

    await waitFor(() => expect(navigate).toHaveBeenCalledWith('Wrapped'));
  });
});
