import { render, screen, fireEvent, waitFor } from '@testing-library/react-native';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { JourneyWidget } from './JourneyWidget';
import { dashboardApi } from '../../api/endpoints';

jest.mock('../../api/endpoints', () => ({
  dashboardApi: { timeline: jest.fn(), momentum: jest.fn() },
}));

const api = dashboardApi as jest.Mocked<typeof dashboardApi>;

function event(overrides: Record<string, unknown> = {}) {
  return {
    eventType: 'GOAL_COMPLETED', bucket: 'PROGRESS', importance: 'LANDMARK', permanent: true,
    title: 'Completed Emergency Fund', detail: null, occurredAt: '2026-08-01T10:00:00Z', ...overrides,
  };
}

function renderWidget(onViewJourney = jest.fn()) {
  // gcTime: 0 -- see SubscriptionScreen.test.tsx's own comment on this exact line.
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  render(<QueryClientProvider client={queryClient}><JourneyWidget onViewJourney={onViewJourney} /></QueryClientProvider>);
  return onViewJourney;
}

describe('JourneyWidget', () => {
  beforeEach(() => {
    api.timeline.mockReset();
    api.momentum.mockReset().mockResolvedValue({ activeMonths: 0, windowMonths: 6 });
  });

  it('shows the most recent milestone, the momentum caption, and opens the journey on tap', async () => {
    api.timeline.mockResolvedValue([event()] as any);
    api.momentum.mockResolvedValue({ activeMonths: 4, windowMonths: 6 });
    const onViewJourney = renderWidget();

    expect(await screen.findByText('Completed Emergency Fund')).toBeTruthy();
    expect(await screen.findByText('Active 4 of the last 6 months')).toBeTruthy();
    fireEvent.press(screen.getByText('View your journey'));
    expect(onViewJourney).toHaveBeenCalledTimes(1);
  });

  it('omits the momentum caption when there is no active month', async () => {
    api.timeline.mockResolvedValue([event()] as any);
    renderWidget();

    await screen.findByText('Completed Emergency Fund');
    await waitFor(() => expect(api.momentum).toHaveBeenCalled());
    expect(screen.queryByText(/Active \d+ of the last/)).toBeNull();
  });

  it('renders nothing when every event is Minor', async () => {
    api.timeline.mockResolvedValue([event({ importance: 'MINOR', title: 'Small thing' })] as any);
    renderWidget();

    await waitFor(() => expect(api.timeline).toHaveBeenCalled());
    expect(screen.queryByText('Your Journey')).toBeNull();
    expect(screen.queryByText('Small thing')).toBeNull();
  });

  it('renders nothing while loading, and nothing when the timeline fails to load', async () => {
    api.timeline.mockRejectedValue(new Error('boom'));
    renderWidget();

    await waitFor(() => expect(api.timeline).toHaveBeenCalled());
    expect(screen.queryByText('Your Journey')).toBeNull();
    expect(screen.queryByText(/couldn't/i)).toBeNull();
  });
});
