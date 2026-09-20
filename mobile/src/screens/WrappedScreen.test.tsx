import { render, screen } from '@testing-library/react-native';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { WrappedScreen } from './WrappedScreen';
import { dashboardApi } from '../api/endpoints';
import { usePreventScreenCapture } from '../lib/screenCapture';

jest.mock('../api/endpoints', () => ({
  dashboardApi: { wrapped: jest.fn() },
}));

const api = dashboardApi as jest.Mocked<typeof dashboardApi>;

function renderScreen() {
  // gcTime: 0 -- see SubscriptionScreen.test.tsx's own comment on this exact line.
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  render(<QueryClientProvider client={queryClient}><WrappedScreen /></QueryClientProvider>);
}

describe('WrappedScreen', () => {
  beforeEach(() => api.wrapped.mockReset());

  it('asks for the current calendar year and shows the year, contribution count and milestones', async () => {
    api.wrapped.mockResolvedValue({
      year: 2026, landmarksReached: 2, goalContributions: 9,
      landmarkTitles: ['Completed Emergency Fund', 'Reached ₹1,00,000 net worth'],
    });
    renderScreen();

    expect(await screen.findByText('2026')).toBeTruthy();
    expect(api.wrapped).toHaveBeenCalledWith(new Date().getFullYear());
    expect(screen.getByText('9')).toBeTruthy();
    expect(screen.getByText('goal contributions this year')).toBeTruthy();
    expect(screen.getByText('Completed Emergency Fund')).toBeTruthy();
    expect(screen.getByText('Reached ₹1,00,000 net worth')).toBeTruthy();
  });

  it('blocks screenshots and screen recording, since milestones name real goals and net worth', async () => {
    api.wrapped.mockResolvedValue({ year: 2026, landmarksReached: 0, goalContributions: 0, landmarkTitles: [] });
    renderScreen();
    await screen.findByText('2026');

    expect(usePreventScreenCapture).toHaveBeenCalled();
  });

  it('says so plainly when no milestone was reached this year', async () => {
    api.wrapped.mockResolvedValue({ year: 2026, landmarksReached: 0, goalContributions: 0, landmarkTitles: [] });
    renderScreen();

    expect(await screen.findByText('No milestones reached yet this year.')).toBeTruthy();
  });

  it('shows a load failure rather than a blank screen', async () => {
    api.wrapped.mockRejectedValue(new Error('boom'));
    renderScreen();

    expect(await screen.findByText(/Couldn't load your year in review/)).toBeTruthy();
  });
});
