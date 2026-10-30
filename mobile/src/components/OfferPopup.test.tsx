import { fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { billingApi, type BillingOffer, type MySubscription } from '../api/endpoints';
import { playIntroOffer } from '../lib/revenueCat';
import { safeStorage } from '../lib/safeStorage';
import { trackNavigation } from '../lib/trackNavigation';
import { OfferPopup, offerPopupDismissedKey } from './OfferPopup';

const mockAuth = { onboardingCompleted: true, referralPromptPending: false };
jest.mock('../context/AuthContext', () => ({ useAuth: () => mockAuth }));

const mockNavigate = jest.fn();
const mockFocus = { focused: true };
jest.mock('@react-navigation/native', () => ({
  useNavigation: () => ({ navigate: mockNavigate }),
  useIsFocused: () => mockFocus.focused,
}));

jest.mock('../api/endpoints', () => ({ billingApi: { mySubscription: jest.fn(), offers: jest.fn() } }));
jest.mock('../lib/revenueCat', () => ({ playIntroOffer: jest.fn() }));
jest.mock('../lib/trackNavigation', () => ({ trackNavigation: jest.fn() }));

// An in-memory SecureStore, so a dismissal written by one render is read back by the next.
const mockStore = new Map<string, string>();
jest.mock('../lib/safeStorage', () => ({
  safeStorage: {
    getItem: jest.fn(async (key: string) => mockStore.get(key) ?? null),
    setItem: jest.fn(async (key: string, value: string) => {
      mockStore.set(key, value);
    }),
    removeItem: jest.fn(async (key: string) => {
      mockStore.delete(key);
    }),
  },
}));

const api = billingApi as jest.Mocked<typeof billingApi>;
const mockedPlay = playIntroOffer as jest.MockedFunction<typeof playIntroOffer>;

// Synthetic campaign, shaped like GET /billing/offers.
const diwali: BillingOffer = {
  planCode: 'PLUS', billingCycle: 'MONTHLY', code: 'DIWALI2026', label: 'Diwali offer',
  firstPeriodPrice: 125, regularPrice: 249, endsAt: '2099-11-07T18:30:00Z',
};

function subscription(overrides: Partial<MySubscription> = {}): MySubscription {
  return { planCode: 'FREE', hasBillingSubscription: false, ...overrides } as MySubscription;
}

function renderPopup(suppressed = false) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <OfferPopup suppressed={suppressed} />
    </QueryClientProvider>
  );
}

/** Lets every request the pop-up depends on settle, so "not shown" is not just "not loaded yet". */
async function settled() {
  await waitFor(() => expect(api.mySubscription).toHaveBeenCalled());
  await waitFor(() => expect(mockedPlay).toHaveBeenCalled());
}

beforeEach(() => {
  mockStore.clear();
  mockAuth.onboardingCompleted = true;
  mockAuth.referralPromptPending = false;
  mockFocus.focused = true;
  mockNavigate.mockReset();
  (trackNavigation as jest.Mock).mockReset();
  api.mySubscription.mockReset().mockResolvedValue(subscription());
  api.offers.mockReset().mockResolvedValue([diwali]);
  mockedPlay.mockReset().mockResolvedValue({ offerPrice: '₹125', regularPrice: '₹249' });
});

it("shows a Free customer the offer with Play's prices and the last day", async () => {
  renderPopup();

  expect(await screen.findByTestId('offer-popup')).toBeTruthy();
  expect(screen.getByText('Diwali offer')).toBeTruthy();
  expect(screen.getByText('Plus at ₹125 for your first month (then ₹249/month). Ends 7 Nov.')).toBeTruthy();
});

it('closes on the cross and stays closed for this offer', async () => {
  const first = renderPopup();
  await screen.findByTestId('offer-popup');

  fireEvent.press(screen.getByLabelText('Close'));
  expect(screen.queryByTestId('offer-popup')).toBeNull();
  await waitFor(() => expect(mockStore.has(offerPopupDismissedKey('DIWALI2026'))).toBe(true));

  first.unmount();
  renderPopup();
  await settled();
  await waitFor(() => expect(safeStorage.getItem).toHaveBeenCalledTimes(2));
  expect(screen.queryByTestId('offer-popup')).toBeNull();
});

it('closes on the backdrop', async () => {
  renderPopup();
  await screen.findByTestId('offer-popup');

  fireEvent.press(screen.getByLabelText('Close offer'));
  expect(screen.queryByTestId('offer-popup')).toBeNull();
});

it('shows again for a later campaign with a new code', async () => {
  mockStore.set(offerPopupDismissedKey('DIWALI2026'), '2026-11-01T00:00:00Z');
  api.offers.mockResolvedValue([{ ...diwali, code: 'HOLI2027', label: 'Holi offer' }]);
  renderPopup();

  expect(await screen.findByText('Holi offer')).toBeTruthy();
});

it('Get Plus closes it, remembers that, and opens the plans', async () => {
  renderPopup();
  await screen.findByTestId('offer-popup');

  fireEvent.press(screen.getByText('Get Plus'));
  expect(screen.queryByTestId('offer-popup')).toBeNull();
  expect(trackNavigation).toHaveBeenCalledWith('subscription', 'contextual');
  expect(mockNavigate).toHaveBeenCalledWith('More', { screen: 'Subscription' });
  await waitFor(() => expect(mockStore.has(offerPopupDismissedKey('DIWALI2026'))).toBe(true));
});

it.each([
  ['a Play or web subscriber', subscription({ planCode: 'PLUS', hasBillingSubscription: true })],
  ['a complimentary Plus grant', subscription({ planCode: 'PLUS', hasBillingSubscription: false })],
])('is never shown to %s', async (_who, sub) => {
  api.mySubscription.mockResolvedValue(sub);
  renderPopup();

  await settled();
  await waitFor(() => expect(api.offers).toHaveBeenCalled());
  expect(screen.queryByTestId('offer-popup')).toBeNull();
});

it('is not shown when Play would not charge this customer the offer (iOS, or had Plus before)', async () => {
  mockedPlay.mockResolvedValue(null);
  renderPopup();

  await settled();
  expect(screen.queryByTestId('offer-popup')).toBeNull();
});

it('is not shown once the campaign has ended, even if Play still has the offer', async () => {
  api.offers.mockResolvedValue([{ ...diwali, endsAt: '2020-01-01T00:00:00Z' }]);
  renderPopup();

  await settled();
  await waitFor(() => expect(api.offers).toHaveBeenCalled());
  expect(screen.queryByTestId('offer-popup')).toBeNull();
});

it.each([
  ['onboarding is not finished', () => { mockAuth.onboardingCompleted = false; }],
  ['the referral-code prompt is up', () => { mockAuth.referralPromptPending = true; }],
  ['the dashboard is not the screen on show', () => { mockFocus.focused = false; }],
])('waits while %s', async (_why, arrange) => {
  arrange();
  renderPopup();

  await settled();
  await waitFor(() => expect(api.offers).toHaveBeenCalled());
  expect(screen.queryByTestId('offer-popup')).toBeNull();
});

it('waits while the dashboard has a sheet of its own open', async () => {
  renderPopup(true);

  await settled();
  await waitFor(() => expect(api.offers).toHaveBeenCalled());
  expect(screen.queryByTestId('offer-popup')).toBeNull();
});

it('makes a SecureStore-safe key from any campaign code', () => {
  expect(offerPopupDismissedKey('DIWALI 2026/x')).toBe('fynora.offerPopupDismissed.DIWALI_2026_x');
});
