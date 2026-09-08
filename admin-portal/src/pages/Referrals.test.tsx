import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import Referrals from './Referrals';
import { adminReferralsApi } from '../api/endpoints';
import { useAdminAuth } from '../context/AdminAuthContext';
import { mockAdminAuthState } from '../test/mockAdminAuth';
import type { AdminReferralSummaryDto } from '../types';

vi.mock('../context/ThemeContext', () => ({
  useTheme: () => ({ theme: 'system', resolvedTheme: 'light', setTheme: vi.fn() }),
}));
vi.mock('../context/AdminAuthContext', () => ({
  useAdminAuth: vi.fn(),
}));
const notifySuccess = vi.fn();
const notifyError = vi.fn();
vi.mock('../context/NotificationContext', () => ({
  useNotify: () => ({ success: notifySuccess, error: notifyError }),
}));
vi.mock('../api/endpoints', () => ({
  adminReferralsApi: { list: vi.fn(), creditReward: vi.fn() },
}));

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <Referrals />
      </MemoryRouter>
    </QueryClientProvider>
  );
}

function mockAuth(permissions: string[]) {
  vi.mocked(useAdminAuth).mockReturnValue(mockAdminAuthState({
    hasPermission: (p: string) => permissions.includes(p),
    permissions,
  }));
}

function row(overrides: Partial<AdminReferralSummaryDto> = {}): AdminReferralSummaryDto {
  return {
    referralId: 'referral-1',
    referrerUserId: 'referrer-1', referrerEmail: 'referrer@example.com', referrerFullName: 'Alice',
    referredUserId: 'referred-1', referredEmail: 'referred@example.com', referredFullName: 'Bob',
    status: 'SUBSCRIBED', reward: null, createdAt: '2026-09-01T10:00:00Z',
    ...overrides,
  };
}

function pageOf(...rows: AdminReferralSummaryDto[]) {
  return { content: rows, page: 0, size: 20, totalElements: rows.length, totalPages: 1 };
}

describe('Referrals (admin portal)', () => {
  beforeEach(() => {
    mockAuth(['REFERRAL_MANAGEMENT_VIEW', 'REFERRAL_MANAGEMENT_MANAGE']);
    vi.mocked(adminReferralsApi.list).mockReset().mockResolvedValue(pageOf());
    vi.mocked(adminReferralsApi.creditReward).mockReset();
    notifySuccess.mockClear();
    notifyError.mockClear();
  });

  it('shows the empty state with no referrals', async () => {
    renderPage();
    expect(await screen.findByText(/no referrals yet/i)).toBeInTheDocument();
  });

  it('lets an admin credit a SUBSCRIBED referral', async () => {
    vi.mocked(adminReferralsApi.list).mockResolvedValue(pageOf(row()));
    vi.mocked(adminReferralsApi.creditReward).mockResolvedValue(undefined as any);
    renderPage();

    await screen.findByText('Alice');
    fireEvent.change(screen.getByPlaceholderText('Amount'), { target: { value: '250' } });
    fireEvent.click(screen.getByText('Credit'));

    await waitFor(() =>
      expect(adminReferralsApi.creditReward).toHaveBeenCalledWith('referral-1', 250, 'Admin credited referral reward'));
    await waitFor(() => expect(notifySuccess).toHaveBeenCalled());
  });

  it('shows a rupee amount for an already-REWARDED referral instead of the credit form', async () => {
    vi.mocked(adminReferralsApi.list).mockResolvedValue(pageOf(row({ status: 'REWARDED', reward: 250 })));
    renderPage();

    await screen.findByText('Alice');
    expect(screen.getByText('₹250.00')).toBeInTheDocument();
    expect(screen.queryByPlaceholderText('Amount')).not.toBeInTheDocument();
  });
});
