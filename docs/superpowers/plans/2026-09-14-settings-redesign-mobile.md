# Settings Redesign (Mobile) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the single 679-line `SettingsScreen.tsx` (one giant `ScrollView` stacking 9 `SectionCard`s) with a root grouped list (iOS-Settings-app style) that pushes each category to its own screen under the existing `More` stack. Adds a Bank Sync screen — mobile currently has zero Account Aggregator UI at all, web already does.

**Architecture:** `SettingsScreen.tsx` becomes the root list: 7 category rows that navigate to new pushed screens, plus the existing 2 standalone rows (Help & Support, Legal) unchanged. Each new screen fetches its own data via React Query with the *same* `queryKey`s the original screen already used (`['user-settings']`, `['workspace-settings']`, `['import-statistics']`) — React Query's cache dedupes automatically across screens, so General/Security/Data/Account sharing the `user-settings` query costs one network call, not four, with no custom shared hook needed (unlike the web plan, mobile already has React Query wired app-wide). Three already-self-contained section components (`AppLockSection`, `DeviceSessionsSection`, `GmailConnectionSection`) move into their new screens unchanged — they already fetch their own data independently.

**Tech Stack:** React Native, Expo, TypeScript, React Navigation (native stack), `@tanstack/react-query`, Jest + `@testing-library/react-native`.

**Spec:** No separate spec file — bounded-path redesign. Design lives in this plan and the web plan's sibling document (`2026-09-14-settings-redesign-web.md`), which share the same category set and naming.

## Global Constraints

- No backend changes for the 7 ported categories. Bank Sync is genuinely new mobile UI, but calls only endpoints the backend and web frontend already use (`/integrations/setu/links*`) — no new backend endpoint.
- Read `mobile/AGENTS.md` before touching navigation code: "Expo HAS CHANGED — read the exact versioned docs at https://docs.expo.dev/versions/v57.0.0/ before writing any code." Check React Navigation's native-stack API against that version before Task 10.
- Every new pushed screen uses the native header (default `headerShown: true`), matching `Budgets`/`Subscription`/`Reports`/`Investments`/`Profile`/`Settings`'s existing pattern in `AppTabs.tsx` — set `options={{ title: '<Category>' }}` since the route name (`SettingsGeneral`) is not a presentable title.
- Help & Support and Legal stay exactly as they are today — standalone rows on the root list, not folded into a category. They're static links/tickets, not "settings that get changed"; a one-item category pane for either would be a worse UX than a direct row, and the web plan makes the identical call.
- Touch targets: verify every new row/button hits RN's `minHeight: 44` (or `hitSlop`) — the original file already does this correctly in most places (see `stepButton: { width: 48, height: 48, ... }`); carry that discipline into new code, don't regress it.

---

## File Structure

```
mobile/src/api/endpoints.ts        # modify — add accountAggregatorApi + AccountAggregatorLinkDto
mobile/src/navigation/types.ts     # modify — add 8 new MoreStackParamList entries
mobile/src/navigation/AppTabs.tsx  # modify — register 8 new MoreStack.Screen entries

mobile/src/screens/SettingsGeneralScreen.tsx
mobile/src/screens/SettingsSecurityScreen.tsx
mobile/src/screens/SettingsCategorizationScreen.tsx
mobile/src/screens/SettingsDataScreen.tsx
mobile/src/screens/SettingsConnectedAppsScreen.tsx
mobile/src/screens/SettingsBankSyncScreen.tsx         # new feature
mobile/src/screens/SettingsBankSyncConfirmScreen.tsx  # new feature, ports web's AccountAggregatorConfirm.tsx
mobile/src/screens/SettingsAccountScreen.tsx
mobile/src/screens/SettingsScreen.tsx        # rewritten — root grouped list only, ~120 lines
mobile/src/screens/SettingsScreen.test.tsx   # rewritten — root list nav test
mobile/src/screens/Settings*.test.tsx        # new — one per new screen above
```

All line ranges below cite the pre-redesign `mobile/src/screens/SettingsScreen.tsx` (679 lines) as it exists on `origin/main` right now.

---

## Task 1: Add `accountAggregatorApi` to `mobile/src/api/endpoints.ts`

**Files:**
- Modify: `mobile/src/api/endpoints.ts`

**Interfaces:**
- Produces: `AccountAggregatorLinkDto` (same shape as `frontend/src/api/endpoints.ts:1333-1342`) and `accountAggregatorApi: { list, initiate, confirmExistingAccount, confirmNewAccount, disconnect }`.

- [ ] **Step 1: Write the addition**, placed near `gmailApi` (around line 740) following that block's exact convention — same `platform: 'MOBILE'` param on the one call that returns a redirect/authorization URL, since Setu's consent flow, like Gmail OAuth, needs to know which client is initiating it:

```ts
// mobile/src/api/endpoints.ts -- add after gmailApi's closing brace
export interface AccountAggregatorLinkDto {
  id: string;
  fiType: 'DEPOSIT' | 'CREDIT_CARD';
  status: 'CONSENT_PENDING' | 'PENDING_ACCOUNT_CONFIRMATION' | 'ACTIVE' | 'PAUSED' | 'REVOKED'
    | 'EXPIRED' | 'REJECTED' | 'LINK_FAILED';
  consentExpiresAt: string | null;
  lastSyncedAt: string | null;
  lastSyncStatus: 'SUCCESS' | 'FAILED' | null;
  statusChangedAt: string;
}

export const accountAggregatorApi = {
  list: () => api.get<AccountAggregatorLinkDto[]>('/integrations/setu/links').then((r) => r.data),
  initiate: (fiType: 'DEPOSIT' | 'CREDIT_CARD', idempotencyKey: string) =>
    api.post<{ linkId: string; status: string; redirectUrl: string | null }>(
      '/integrations/setu/links', { fiType, idempotencyKey }, { params: { platform: 'MOBILE' } }
    ).then((r) => r.data),
  confirmExistingAccount: (linkId: string, accountId: string) =>
    api.post(`/integrations/setu/links/${linkId}/confirm-existing-account`, { accountId }),
  confirmNewAccount: (linkId: string) =>
    api.post(`/integrations/setu/links/${linkId}/confirm-new-account`),
  disconnect: (linkId: string) => api.post(`/integrations/setu/links/${linkId}/disconnect`),
};
```

Before shipping this step, confirm against the backend's `AccountAggregatorLinkController` whether the `platform=MOBILE` query param is actually read on the initiate endpoint the way `GoogleOAuthController.connect` reads it for Gmail — grep `backend/src/main/java` for the controller and check its `@RequestParam` list. If it doesn't accept a `platform` param at all, drop that option object entirely rather than sending an argument the backend ignores.

- [ ] **Step 2: Commit**

```bash
git add mobile/src/api/endpoints.ts
git commit -m "feat(mobile): add Account Aggregator API client, mirroring web"
```

---

## Task 2: `SettingsGeneralScreen`

**Files:**
- Create: `mobile/src/screens/SettingsGeneralScreen.tsx` — move `THEME_LABEL` (line 43-47), `availableTimezones()` (line 53-70), the `lowBalanceDraft`/`timezoneDraft`/`timezonePickerOpen` state and `savePreferences()` (lines 109-110, 112, 187-212), the `retakingTour`/`retakeTourError`/`retakeTour()` block (lines 81-103), and the General `<SectionCard>` JSX (lines 269-332), plus `TimezonePickerModal` render (lines 560-570).
- Create: `mobile/src/screens/SettingsGeneralScreen.test.tsx` — move the `savePreferences`/theme/retake-tour test cases from `SettingsScreen.test.tsx`.

**Interfaces:**
- Consumes: `useQuery(['user-settings'], () => userApi.get())` (new per-screen call, same key the original `useQueries` used); `useThemeSetting()`, `useAuth().setOnboardingCompleted`, `onboardingApi`, `userApi`, `useSingleFlight`, `useTransientFlag`, `parsePositiveAmount`, `TextField`, `Button`, `SectionCard`, `SaveStatus`, `OptionPickerModal` — all unchanged imports, just relocated.
- Produces: `export function SettingsGeneralScreen()` — no props, no params (registered as `SettingsGeneral: undefined` in Task 10).

- [ ] **Step 1: Write the failing test**

```tsx
// mobile/src/screens/SettingsGeneralScreen.test.tsx
import { render, screen, fireEvent, waitFor } from '@testing-library/react-native';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { SettingsGeneralScreen } from './SettingsGeneralScreen';
import { userApi } from '../api/endpoints';
import { ThemeProvider } from '../theme';

jest.mock('../api/endpoints', () => ({
  userApi: { get: jest.fn(), update: jest.fn() },
  onboardingApi: { reset: jest.fn().mockResolvedValue(undefined) },
}));
jest.mock('../context/AuthContext', () => ({ useAuth: () => ({ setOnboardingCompleted: jest.fn() }) }));

const user = userApi as jest.Mocked<typeof userApi>;

function renderScreen() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <ThemeProvider><SettingsGeneralScreen /></ThemeProvider>
    </QueryClientProvider>
  );
}

test('saves the low balance threshold', async () => {
  user.get.mockResolvedValue({
    email: 'a@example.com', fullName: 'Amy', lowBalanceThreshold: 2000, theme: 'system', timezone: 'Asia/Kolkata',
    phoneNumber: '', phoneVerified: false, createdAt: '2026-01-01T00:00:00Z', passwordChangedAt: null, signInMethod: 'PASSWORD',
  } as never);
  user.update.mockResolvedValue({ lowBalanceThreshold: 5000, timezone: 'Asia/Kolkata' } as never);
  renderScreen();
  const input = await screen.findByLabelText('Low balance alert');
  fireEvent.changeText(input, '5000');
  fireEvent.press(screen.getByText('Save preferences'));
  await waitFor(() => expect(user.update).toHaveBeenCalledWith({ lowBalanceThreshold: 5000, timezone: 'Asia/Kolkata' }));
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd mobile && npx jest src/screens/SettingsGeneralScreen.test.tsx`
Expected: FAIL — `Cannot find module './SettingsGeneralScreen'`

- [ ] **Step 3: Write the implementation** — move the cited blocks verbatim, replacing the original's `useQueries` membership for `userQ` with a standalone call:

```tsx
const userQ = useQuery({ queryKey: ['user-settings'], queryFn: () => userApi.get() });
```

and replacing every `queryClient.setQueryData(['user-settings'], updated)` / `queryClient.invalidateQueries({ queryKey: [...] })` call inside the moved `savePreferences()` with the exact same calls (`useQueryClient()` still needed, import unchanged) — this part needs zero logic change, `savePreferences()` already only touches the `user-settings` and `dashboard-summary` query keys, neither of which this split affects.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd mobile && npx jest src/screens/SettingsGeneralScreen.test.tsx`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add mobile/src/screens/SettingsGeneralScreen.tsx mobile/src/screens/SettingsGeneralScreen.test.tsx
git commit -m "feat(mobile): extract Settings General screen"
```

---

## Task 3: `SettingsSecurityScreen`

**Files:**
- Create: `mobile/src/screens/SettingsSecurityScreen.tsx` — move the Security `<SectionCard>` JSX (lines 334-376, which already just wraps `AppLockSection` and `DeviceSessionsSection` — both self-contained, unchanged imports from `./settings/AppLockSection` and `./settings/DeviceSessionsSection`), plus `changePasswordOpen`/`changeEmailOpen` state and the `ChangePasswordSheet`/`ChangeEmailSheet` renders (lines 572-587).
- Create: `mobile/src/screens/SettingsSecurityScreen.test.tsx` — move the password/email/phone-verification test cases.

**Interfaces:**
- Consumes: `useQuery(['user-settings'], () => userApi.get())` (same key as Task 2 — React Query dedupes; this screen and General are never mounted at the same time anyway, since each is a separate pushed screen, but the shared key still means no cache-miss flash if the user backs out and into another category quickly).
- Produces: `export function SettingsSecurityScreen()`.

- [ ] **Step 1: Write the failing test**

```tsx
// mobile/src/screens/SettingsSecurityScreen.test.tsx
import { render, screen } from '@testing-library/react-native';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { SettingsSecurityScreen } from './SettingsSecurityScreen';
import { userApi } from '../api/endpoints';

jest.mock('../api/endpoints', () => ({ userApi: { get: jest.fn() } }));
jest.mock('./settings/AppLockSection', () => ({ AppLockSection: () => null }));
jest.mock('./settings/DeviceSessionsSection', () => ({ DeviceSessionsSection: () => null }));

test('shows the masked phone number once loaded', async () => {
  (userApi.get as jest.Mock).mockResolvedValue({
    email: 'a@example.com', fullName: 'Amy', phoneNumber: '+919876543210', // synthetic-ok: invented test number
    phoneVerified: true,
    passwordChangedAt: null, signInMethod: 'PASSWORD', lowBalanceThreshold: 2000, theme: 'system', timezone: 'IST', createdAt: '2026-01-01',
  });
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(<QueryClientProvider client={queryClient}><SettingsSecurityScreen /></QueryClientProvider>);
  expect(await screen.findByText('+•••••••••210')).toBeTruthy();
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd mobile && npx jest src/screens/SettingsSecurityScreen.test.tsx`
Expected: FAIL — module not found

- [ ] **Step 3: Write the implementation** — verbatim move, `userQ` as a standalone `useQuery` like Task 2.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd mobile && npx jest src/screens/SettingsSecurityScreen.test.tsx`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add mobile/src/screens/SettingsSecurityScreen.tsx mobile/src/screens/SettingsSecurityScreen.test.tsx
git commit -m "feat(mobile): extract Settings Security screen"
```

---

## Task 4: `SettingsCategorizationScreen`

**Files:**
- Create: `mobile/src/screens/SettingsCategorizationScreen.tsx` — move `THRESHOLD_STEP` (line 51), the `thresholdDraft`/`saveThreshold()`/`nudgeThreshold()` block (lines 111, 117-119, 214-233), and the Categorization `<SectionCard>` JSX (lines 378-458).
- Create: `mobile/src/screens/SettingsCategorizationScreen.test.tsx` — move the confidence-threshold stepper test cases (the accessibility-adjustable widget assertions in particular — this is the one screen where the existing test suite already exercises `accessibilityActions`, keep that coverage intact).

**Interfaces:**
- Consumes: `useQuery(['workspace-settings'], () => workspaceApi.getSettings())` (own key, independent of the `user-settings` query the other three screens share); `useNavigation<NativeStackNavigationProp<MoreStackParamList>>()` for the `CategoryReview` push.
- Produces: `export function SettingsCategorizationScreen()`.

- [ ] **Step 1: Write the failing test**

```tsx
// mobile/src/screens/SettingsCategorizationScreen.test.tsx
import { render, screen, fireEvent, waitFor } from '@testing-library/react-native';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { SettingsCategorizationScreen } from './SettingsCategorizationScreen';
import { workspaceApi } from '../api/endpoints';

jest.mock('../api/endpoints', () => ({ workspaceApi: { getSettings: jest.fn(), updateSettings: jest.fn() } }));
const mockNavigate = jest.fn();
jest.mock('@react-navigation/native', () => ({ useNavigation: () => ({ navigate: mockNavigate }) }));

test('increments the threshold via the accessible stepper', async () => {
  (workspaceApi.getSettings as jest.Mock).mockResolvedValue({ autoApplyConfidenceThreshold: 90 });
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(<QueryClientProvider client={queryClient}><SettingsCategorizationScreen /></QueryClientProvider>);
  await screen.findByText('90%');
  fireEvent.press(screen.getByLabelText('Decrease threshold'));
  await waitFor(() => expect(screen.getByText('85%')).toBeTruthy());
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd mobile && npx jest src/screens/SettingsCategorizationScreen.test.tsx`
Expected: FAIL — module not found

- [ ] **Step 3: Write the implementation** — verbatim move, `workspaceQ` as a standalone `useQuery`.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd mobile && npx jest src/screens/SettingsCategorizationScreen.test.tsx`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add mobile/src/screens/SettingsCategorizationScreen.tsx mobile/src/screens/SettingsCategorizationScreen.test.tsx
git commit -m "feat(mobile): extract Settings Categorization screen"
```

---

## Task 5: `SettingsDataScreen`

**Files:**
- Create: `mobile/src/screens/SettingsDataScreen.tsx` — move the Data `<SectionCard>` JSX (lines 485-511) and `exportOpen` state + `ExportDataSheet` render (line 609-615).
- Create: `mobile/src/screens/SettingsDataScreen.test.tsx` — move the import-statistics/export test cases.

**Interfaces:**
- Consumes: `useQuery(['import-statistics'], () => analyticsApi.importStatistics(), { retry: false })` (own key); `useQuery(['user-settings'], ...)` only for `signInMethod`, passed to `ExportDataSheet`.
- Produces: `export function SettingsDataScreen()`.

- [ ] **Step 1: Write the failing test**

```tsx
// mobile/src/screens/SettingsDataScreen.test.tsx
import { render, screen } from '@testing-library/react-native';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { SettingsDataScreen } from './SettingsDataScreen';
import { analyticsApi, userApi } from '../api/endpoints';

jest.mock('../api/endpoints', () => ({
  analyticsApi: { importStatistics: jest.fn() },
  userApi: { get: jest.fn().mockResolvedValue({ signInMethod: 'PASSWORD' }) },
}));

test('renders total statements once loaded', async () => {
  (analyticsApi.importStatistics as jest.Mock).mockResolvedValue({
    totalStatements: 12, totalTransactionsImported: 340, totalTransactionsSkipped: 2, lastImportedAt: '2026-08-01T00:00:00Z',
  });
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(<QueryClientProvider client={queryClient}><SettingsDataScreen /></QueryClientProvider>);
  expect(await screen.findByText('12')).toBeTruthy();
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd mobile && npx jest src/screens/SettingsDataScreen.test.tsx`
Expected: FAIL — module not found

- [ ] **Step 3: Write the implementation** — verbatim move.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd mobile && npx jest src/screens/SettingsDataScreen.test.tsx`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add mobile/src/screens/SettingsDataScreen.tsx mobile/src/screens/SettingsDataScreen.test.tsx
git commit -m "feat(mobile): extract Settings Data screen"
```

---

## Task 6: `SettingsConnectedAppsScreen`

**Files:**
- Create: `mobile/src/screens/SettingsConnectedAppsScreen.tsx` — thin wrapper: a `SectionCard` titled "Connected Apps" containing `<GmailConnectionSection />` (line 536-538 in the original — this was already a two-line wrap around an already-self-contained component).
- Create: `mobile/src/screens/SettingsConnectedAppsScreen.test.tsx` — a single smoke test (the real behavior coverage already lives in `GmailConnectionSection.test.tsx`, untouched by this plan).

**Interfaces:**
- Consumes: `GmailConnectionSection` from `./settings/GmailConnectionSection` (unchanged).
- Produces: `export function SettingsConnectedAppsScreen()`.

- [ ] **Step 1: Write the failing test**

```tsx
// mobile/src/screens/SettingsConnectedAppsScreen.test.tsx
import { render, screen } from '@testing-library/react-native';
import { SettingsConnectedAppsScreen } from './SettingsConnectedAppsScreen';

jest.mock('./settings/GmailConnectionSection', () => ({
  GmailConnectionSection: () => { const { Text } = require('react-native'); return <Text>Gmail section</Text>; },
}));

test('renders the Connected Apps section shell around GmailConnectionSection', () => {
  render(<SettingsConnectedAppsScreen />);
  expect(screen.getByText('Gmail section')).toBeTruthy();
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd mobile && npx jest src/screens/SettingsConnectedAppsScreen.test.tsx`
Expected: FAIL — module not found

- [ ] **Step 3: Write the implementation**

```tsx
// mobile/src/screens/SettingsConnectedAppsScreen.tsx
import { ScrollView, StyleSheet } from 'react-native';
import { SectionCard } from '../components/AccountUI';
import { GmailConnectionSection } from './settings/GmailConnectionSection';
import { spacing, useTheme } from '../theme';

export function SettingsConnectedAppsScreen() {
  const c = useTheme();
  return (
    <ScrollView style={{ backgroundColor: c.bg }} contentContainerStyle={styles.content}>
      <SectionCard title="Connected Apps" subtitle="Link external accounts Fynora can read transactions from">
        <GmailConnectionSection />
      </SectionCard>
    </ScrollView>
  );
}

const styles = StyleSheet.create({ content: { padding: spacing.md, paddingBottom: spacing.xl } });
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd mobile && npx jest src/screens/SettingsConnectedAppsScreen.test.tsx`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add mobile/src/screens/SettingsConnectedAppsScreen.tsx mobile/src/screens/SettingsConnectedAppsScreen.test.tsx
git commit -m "feat(mobile): extract Settings Connected Apps screen"
```

---

## Task 7: `SettingsBankSyncScreen` (new)

**Files:**
- Create: `mobile/src/screens/SettingsBankSyncScreen.tsx`
- Create: `mobile/src/screens/SettingsBankSyncScreen.test.tsx`

No source to move from — mobile has never had this UI. Ports the *content and status logic* of web's `frontend/src/pages/settings/BankSyncPane.tsx` (Task 8 of the web plan) into RN, using the `accountAggregatorApi` added in Task 1 above.

**Interfaces:**
- Consumes: `accountAggregatorApi` (Task 1); `useNavigation<NativeStackNavigationProp<MoreStackParamList>>()` to push `SettingsBankSyncConfirm` for a `PENDING_ACCOUNT_CONFIRMATION` link, matching web's `navigate('/app/settings/bank-sync/${link.id}/confirm')`.
- Produces: `export function SettingsBankSyncScreen()`.

- [ ] **Step 1: Write the failing test**

```tsx
// mobile/src/screens/SettingsBankSyncScreen.test.tsx
import { render, screen, fireEvent, waitFor } from '@testing-library/react-native';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { SettingsBankSyncScreen } from './SettingsBankSyncScreen';
import { accountAggregatorApi } from '../api/endpoints';

jest.mock('../api/endpoints', () => ({
  accountAggregatorApi: { list: jest.fn(), initiate: jest.fn(), disconnect: jest.fn() },
}));
const mockNavigate = jest.fn();
jest.mock('@react-navigation/native', () => ({ useNavigation: () => ({ navigate: mockNavigate }) }));

function renderScreen() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(<QueryClientProvider client={queryClient}><SettingsBankSyncScreen /></QueryClientProvider>);
}

test('shows a linked account and pushes the confirm screen for a pending one', async () => {
  (accountAggregatorApi.list as jest.Mock).mockResolvedValue([
    { id: 'l1', fiType: 'DEPOSIT', status: 'PENDING_ACCOUNT_CONFIRMATION', consentExpiresAt: null, lastSyncedAt: null, lastSyncStatus: null, statusChangedAt: '2026-08-01T00:00:00Z' },
  ]);
  renderScreen();
  await screen.findByText('Bank Account');
  fireEvent.press(screen.getByText('Confirm Account'));
  expect(mockNavigate).toHaveBeenCalledWith('SettingsBankSyncConfirm', { linkId: 'l1' });
});

test('empty state offers Connect a Bank Account', async () => {
  (accountAggregatorApi.list as jest.Mock).mockResolvedValue([]);
  renderScreen();
  expect(await screen.findByText('No bank accounts linked yet.')).toBeTruthy();
  expect(screen.getByText('Connect a Bank Account')).toBeTruthy();
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd mobile && npx jest src/screens/SettingsBankSyncScreen.test.tsx`
Expected: FAIL — module not found

- [ ] **Step 3: Write the implementation** — porting web's `aaFiTypeLabel`/`aaStatusCopy`/`aaStatusBadgeClass`/`AA_TERMINAL_STATUSES` logic (`frontend/src/pages/Settings.tsx:127-168`, unchanged by the web plan's Task 8 move) verbatim as plain functions, and the list/empty/connect JSX translated to RN primitives:

```tsx
// mobile/src/screens/SettingsBankSyncScreen.tsx
import { useState } from 'react';
import { ActivityIndicator, Linking, Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';
import { useNavigation } from '@react-navigation/native';
import type { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { SectionCard } from '../components/AccountUI';
import { Button } from '../components/Button';
import { accountAggregatorApi, type AccountAggregatorLinkDto } from '../api/endpoints';
import { toUserMessage } from '../lib/apiError';
import { useSingleFlight } from '../lib/useSingleFlight';
import { radius, spacing, useTheme } from '../theme';
import type { MoreStackParamList } from '../navigation/types';

const AA_FI_TYPE_LABELS: Record<string, string> = { DEPOSIT: 'Bank Account', CREDIT_CARD: 'Credit Card' };
function aaFiTypeLabel(fiType: string): string { return AA_FI_TYPE_LABELS[fiType] ?? fiType; }

function aaStatusCopy(status: string): string {
  switch (status) {
    case 'ACTIVE': return 'Connected';
    case 'PAUSED': return "Your plan no longer includes Bank Sync -- reconnects automatically once you upgrade";
    case 'CONSENT_PENDING': return 'Waiting for you to approve this in your banking app';
    case 'PENDING_ACCOUNT_CONFIRMATION': return 'Waiting for you to confirm which account this is';
    case 'REJECTED': return 'Declined -- try again';
    case 'LINK_FAILED': return "Couldn't connect -- try again";
    case 'EXPIRED': return 'Your bank connection has expired -- reconnect';
    case 'REVOKED': return 'Disconnected';
    default: return status;
  }
}

const AA_TERMINAL_STATUSES = new Set(['REVOKED', 'EXPIRED', 'REJECTED', 'LINK_FAILED']);

export function SettingsBankSyncScreen() {
  const c = useTheme();
  const navigation = useNavigation<NativeStackNavigationProp<MoreStackParamList>>();
  const queryClient = useQueryClient();
  const singleFlight = useSingleFlight();
  const [connecting, setConnecting] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);
  const [disconnectingId, setDisconnectingId] = useState<string | null>(null);
  const [confirmingDisconnectId, setConfirmingDisconnectId] = useState<string | null>(null);

  const linksQ = useQuery({ queryKey: ['aa-links'], queryFn: () => accountAggregatorApi.list() });
  const links = linksQ.data ?? [];

  async function handleConnect() {
    setActionError(null);
    await singleFlight(async () => {
      setConnecting(true);
      try {
        const idempotencyKey = `${Date.now()}-${Math.random().toString(36).slice(2)}`;
        const { redirectUrl } = await accountAggregatorApi.initiate('DEPOSIT', idempotencyKey);
        if (redirectUrl) await Linking.openURL(redirectUrl);
        else setActionError('This connection attempt is already in progress.');
      } catch (e) {
        setActionError(toUserMessage(e, "Couldn't start connecting your bank."));
      } finally {
        setConnecting(false);
      }
    });
  }

  async function handleDisconnect(linkId: string) {
    setActionError(null);
    setDisconnectingId(linkId);
    try {
      await accountAggregatorApi.disconnect(linkId);
      setConfirmingDisconnectId(null);
      void queryClient.invalidateQueries({ queryKey: ['aa-links'] });
    } catch (e) {
      setActionError(toUserMessage(e, "Couldn't disconnect."));
    } finally {
      setDisconnectingId(null);
    }
  }

  return (
    <ScrollView style={{ backgroundColor: c.bg }} contentContainerStyle={styles.content}>
      <SectionCard title="Bank Sync" subtitle="Automatically sync transactions from your linked bank accounts">
        {linksQ.isLoading ? (
          <ActivityIndicator color={c.primary} />
        ) : (
          <View>
            {links.length === 0 ? (
              <Text style={[styles.hint, { color: c.mutedInk }]}>No bank accounts linked yet.</Text>
            ) : (
              links.map((link: AccountAggregatorLinkDto) => (
                <View key={link.id} style={[styles.linkRow, { borderColor: c.border }]}>
                  <Text style={[styles.rowTitle, { color: c.ink }]}>{aaFiTypeLabel(link.fiType)} · {link.status.replace(/_/g, ' ')}</Text>
                  <Text style={[styles.hint, { color: c.mutedInk }]}>{aaStatusCopy(link.status)}</Text>
                  {link.status === 'PENDING_ACCOUNT_CONFIRMATION' && (
                    <Button label="Confirm Account" onPress={() => navigation.navigate('SettingsBankSyncConfirm', { linkId: link.id })} />
                  )}
                  {!AA_TERMINAL_STATUSES.has(link.status) && confirmingDisconnectId !== link.id && (
                    <Button label="Disconnect" variant="link" onPress={() => setConfirmingDisconnectId(link.id)} />
                  )}
                  {confirmingDisconnectId === link.id && (
                    <View style={styles.confirmBlock}>
                      <Text style={[styles.hint, { color: c.mutedInk }]}>
                        This does not cancel your consent at your banking app -- it only stops Fynora from syncing this account.
                      </Text>
                      <Button label="Confirm Disconnect" loading={disconnectingId === link.id} onPress={() => void handleDisconnect(link.id)} />
                      <Button label="Cancel" variant="link" onPress={() => setConfirmingDisconnectId(null)} />
                    </View>
                  )}
                </View>
              ))
            )}
            {actionError ? <Text style={[styles.hint, { color: c.danger }]}>{actionError}</Text> : null}
            <Button label="Connect a Bank Account" onPress={() => void handleConnect()} loading={connecting} />
          </View>
        )}
      </SectionCard>
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  content: { padding: spacing.md, paddingBottom: spacing.xl },
  linkRow: { borderWidth: 1, borderRadius: radius.md, padding: spacing.sm, marginBottom: spacing.sm },
  rowTitle: { fontSize: 14, fontWeight: '600' },
  hint: { fontSize: 12, marginTop: 4 },
  confirmBlock: { marginTop: spacing.sm, gap: spacing.xs },
});
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd mobile && npx jest src/screens/SettingsBankSyncScreen.test.tsx`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add mobile/src/screens/SettingsBankSyncScreen.tsx mobile/src/screens/SettingsBankSyncScreen.test.tsx
git commit -m "feat(mobile): add Bank Sync screen, closing the mobile/web parity gap"
```

---

## Task 8: `SettingsBankSyncConfirmScreen` (new, ports web's `AccountAggregatorConfirm.tsx`)

**Files:**
- Create: `mobile/src/screens/SettingsBankSyncConfirmScreen.tsx`
- Create: `mobile/src/screens/SettingsBankSyncConfirmScreen.test.tsx`

**Interfaces:**
- Consumes: `route.params.linkId` (typed via `MoreStackParamList['SettingsBankSyncConfirm']`, added in Task 10); `accountsApi.list()` (already exists on mobile, `mobile/src/api/endpoints.ts:127-132`); `accountAggregatorApi.confirmExistingAccount`/`confirmNewAccount` (Task 1).
- Produces: `export function SettingsBankSyncConfirmScreen()`.

- [ ] **Step 1: Write the failing test**

```tsx
// mobile/src/screens/SettingsBankSyncConfirmScreen.test.tsx
import { render, screen, fireEvent, waitFor } from '@testing-library/react-native';
import { SettingsBankSyncConfirmScreen } from './SettingsBankSyncConfirmScreen';
import { accountsApi, accountAggregatorApi } from '../api/endpoints';

jest.mock('../api/endpoints', () => ({
  accountsApi: { list: jest.fn() },
  accountAggregatorApi: { confirmExistingAccount: jest.fn(), confirmNewAccount: jest.fn() },
}));
const mockGoBack = jest.fn();
jest.mock('@react-navigation/native', () => ({
  useNavigation: () => ({ goBack: mockGoBack }),
  useRoute: () => ({ params: { linkId: 'l1' } }),
}));

test('confirming an existing account calls confirmExistingAccount with the selected id', async () => {
  (accountsApi.list as jest.Mock).mockResolvedValue([
    { id: 'acc1', name: 'HDFC Savings', bank: { shortName: 'HDFC' }, accountNumberMasked: '••1234' },
  ]);
  (accountAggregatorApi.confirmExistingAccount as jest.Mock).mockResolvedValue(undefined);
  render(<SettingsBankSyncConfirmScreen />);
  await screen.findByText('HDFC Savings · HDFC ••1234');
  fireEvent.press(screen.getByText('Yes, this is my account'));
  await waitFor(() => expect(accountAggregatorApi.confirmExistingAccount).toHaveBeenCalledWith('l1', 'acc1'));
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd mobile && npx jest src/screens/SettingsBankSyncConfirmScreen.test.tsx`
Expected: FAIL — module not found

- [ ] **Step 3: Write the implementation** — ports `frontend/src/pages/AccountAggregatorConfirm.tsx` (see its own doc comment, lines 7-21, for why this offers a picker over the user's existing accounts rather than a suggested match — same reasoning applies unchanged on mobile):

```tsx
// mobile/src/screens/SettingsBankSyncConfirmScreen.tsx
import { useEffect, useState } from 'react';
import { ActivityIndicator, ScrollView, StyleSheet, Text, View } from 'react-native';
import { useNavigation, useRoute } from '@react-navigation/native';
import { Button } from '../components/Button';
import { OptionPickerModal } from '../components/OptionPickerModal';
import { accountsApi, accountAggregatorApi } from '../api/endpoints';
import type { Account } from '../api/endpoints';
import { toUserMessage } from '../lib/apiError';
import { spacing, useTheme } from '../theme';

export function SettingsBankSyncConfirmScreen() {
  const c = useTheme();
  const navigation = useNavigation();
  const { params } = useRoute<{ params: { linkId: string } } & Record<string, unknown>>();
  const linkId = params.linkId;

  const [accounts, setAccounts] = useState<Account[]>([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState(false);
  const [selectedAccountId, setSelectedAccountId] = useState('');
  const [pickerOpen, setPickerOpen] = useState(false);
  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);

  useEffect(() => {
    accountsApi.list()
      .then((list) => { setAccounts(list); if (list.length > 0) setSelectedAccountId(list[0].id); })
      .catch(() => setLoadError(true))
      .finally(() => setLoading(false));
  }, []);

  async function confirmExisting() {
    if (!selectedAccountId) return;
    setBusy(true);
    setActionError(null);
    try {
      await accountAggregatorApi.confirmExistingAccount(linkId, selectedAccountId);
      navigation.goBack();
    } catch (e) {
      setActionError(toUserMessage(e, "Couldn't confirm this account."));
      setBusy(false);
    }
  }

  async function confirmNew() {
    setBusy(true);
    setActionError(null);
    try {
      await accountAggregatorApi.confirmNewAccount(linkId);
      navigation.goBack();
    } catch (e) {
      setActionError(toUserMessage(e, "Couldn't set this up as a new account."));
      setBusy(false);
    }
  }

  const selected = accounts.find((a) => a.id === selectedAccountId);
  const accountLabel = (a: Account) => `${a.name} · ${a.bank.shortName}${a.accountNumberMasked ? ` ${a.accountNumberMasked}` : ''}`;

  return (
    <ScrollView style={{ backgroundColor: c.bg }} contentContainerStyle={styles.content}>
      <Text style={[styles.title, { color: c.ink }]}>Confirm your bank account</Text>
      <Text style={[styles.hint, { color: c.mutedInk }]}>
        We connected a bank account through Account Aggregator, but couldn't automatically match it to one of your existing accounts.
      </Text>
      {loading ? (
        <ActivityIndicator color={c.primary} />
      ) : (
        <View>
          {loadError ? (
            <Text style={[styles.hint, { color: c.danger }]}>
              Couldn't load your existing accounts, but you can still set this up as a new one below.
            </Text>
          ) : accounts.length > 0 && selected ? (
            <View style={styles.field}>
              <Text style={[styles.label, { color: c.mutedInk }]}>Which of your accounts is this?</Text>
              <Button label={accountLabel(selected)} variant="link" onPress={() => setPickerOpen(true)} />
            </View>
          ) : null}
          {actionError ? <Text style={[styles.hint, { color: c.danger }]}>{actionError}</Text> : null}
          {!loadError && accounts.length > 0 && (
            <Button label="Yes, this is my account" onPress={() => void confirmExisting()} loading={busy} />
          )}
          <Button label="This is a different/new account" variant="link" onPress={() => void confirmNew()} loading={busy} />
        </View>
      )}
      <OptionPickerModal
        visible={pickerOpen}
        title="Which account?"
        options={accounts.map(accountLabel)}
        selected={selected ? accountLabel(selected) : ''}
        onSelect={(label) => { setSelectedAccountId(accounts.find((a) => accountLabel(a) === label)?.id ?? selectedAccountId); setPickerOpen(false); }}
        onClose={() => setPickerOpen(false)}
      />
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  content: { padding: spacing.md, paddingBottom: spacing.xl },
  title: { fontSize: 20, fontWeight: '700', marginBottom: spacing.xs },
  hint: { fontSize: 13, marginBottom: spacing.md },
  field: { marginBottom: spacing.md },
  label: { fontSize: 12, marginBottom: spacing.xs },
});
```

Before implementing, confirm `Account`'s exact field names (`bank.shortName`, `accountNumberMasked`) against `mobile/src/api/endpoints.ts`'s own `Account` type — this sketch assumes the same shape web's `frontend/src/types.ts` uses; if mobile's type differs, use mobile's real field names instead.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd mobile && npx jest src/screens/SettingsBankSyncConfirmScreen.test.tsx`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add mobile/src/screens/SettingsBankSyncConfirmScreen.tsx mobile/src/screens/SettingsBankSyncConfirmScreen.test.tsx
git commit -m "feat(mobile): add Bank Sync account-confirmation screen"
```

---

## Task 9: `SettingsAccountScreen`

**Files:**
- Create: `mobile/src/screens/SettingsAccountScreen.tsx` — move `endSessionAfterLifecycleAction()` (lines 128-137), `contactSupportForAccountAction()` (lines 139-163), `deactivateOpen`/`deleteOpen` state, the Manage Your Account `<SectionCard>` JSX (lines 540-558), and the `DeactivateAccountSheet`/`DeleteAccountSheet` renders (lines 591-607).
- Create: `mobile/src/screens/SettingsAccountScreen.test.tsx` — move the deactivate/delete test cases.

**Interfaces:**
- Consumes: `useQuery(['user-settings'], ...)` for `signInMethod`; `useAuth().logout`; `useNavigation<NativeStackNavigationProp<MoreStackParamList>>()` for the `SupportTickets` redirect inside `contactSupportForAccountAction`.
- Produces: `export function SettingsAccountScreen()`.

- [ ] **Step 1: Write the failing test**

```tsx
// mobile/src/screens/SettingsAccountScreen.test.tsx
import { render, screen, fireEvent } from '@testing-library/react-native';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { SettingsAccountScreen } from './SettingsAccountScreen';
import { userApi } from '../api/endpoints';

jest.mock('../api/endpoints', () => ({ userApi: { get: jest.fn() } }));
jest.mock('../context/AuthContext', () => ({ useAuth: () => ({ logout: jest.fn() }) }));
const mockNavigate = jest.fn();
jest.mock('@react-navigation/native', () => ({ useNavigation: () => ({ navigate: mockNavigate }) }));

test('opens the Deactivate Account sheet', async () => {
  (userApi.get as jest.Mock).mockResolvedValue({ signInMethod: 'PASSWORD' });
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(<QueryClientProvider client={queryClient}><SettingsAccountScreen /></QueryClientProvider>);
  await screen.findByText('Deactivate Account');
  fireEvent.press(screen.getAllByText('Deactivate Account')[1]); // [0] is the row title, [1] the button
  expect(await screen.findByText(/temporarily disable|deactivate/i)).toBeTruthy();
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd mobile && npx jest src/screens/SettingsAccountScreen.test.tsx`
Expected: FAIL — module not found

- [ ] **Step 3: Write the implementation** — verbatim move, `userQ` as a standalone `useQuery`.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd mobile && npx jest src/screens/SettingsAccountScreen.test.tsx`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add mobile/src/screens/SettingsAccountScreen.tsx mobile/src/screens/SettingsAccountScreen.test.tsx
git commit -m "feat(mobile): extract Settings Account screen"
```

---

## Task 10: Navigation wiring

**Files:**
- Modify: `mobile/src/navigation/types.ts`
- Modify: `mobile/src/navigation/AppTabs.tsx`

**Interfaces:**
- Produces: 8 new keys on `MoreStackParamList` and 8 new `<MoreStack.Screen>` registrations.

- [ ] **Step 1: Add to `MoreStackParamList`** (insert after the existing `Settings: undefined;` line):

```ts
  SettingsGeneral: undefined;
  SettingsSecurity: undefined;
  SettingsCategorization: undefined;
  SettingsData: undefined;
  SettingsConnectedApps: undefined;
  SettingsBankSync: undefined;
  SettingsBankSyncConfirm: { linkId: string };
  SettingsAccount: undefined;
```

- [ ] **Step 2: Register each screen in `AppTabs.tsx`**, directly after the existing `<MoreStack.Screen name="Settings" component={SettingsScreen} />` line, importing all 8 new components at the top of the file alongside the existing screen imports:

```tsx
<MoreStack.Screen name="SettingsGeneral" component={SettingsGeneralScreen} options={{ title: 'General' }} />
<MoreStack.Screen name="SettingsSecurity" component={SettingsSecurityScreen} options={{ title: 'Security' }} />
<MoreStack.Screen name="SettingsCategorization" component={SettingsCategorizationScreen} options={{ title: 'Categorization' }} />
<MoreStack.Screen name="SettingsData" component={SettingsDataScreen} options={{ title: 'Data' }} />
<MoreStack.Screen name="SettingsConnectedApps" component={SettingsConnectedAppsScreen} options={{ title: 'Connected Apps' }} />
<MoreStack.Screen name="SettingsBankSync" component={SettingsBankSyncScreen} options={{ title: 'Bank Sync' }} />
<MoreStack.Screen name="SettingsBankSyncConfirm" component={SettingsBankSyncConfirmScreen} options={{ title: 'Confirm Account' }} />
<MoreStack.Screen name="SettingsAccount" component={SettingsAccountScreen} options={{ title: 'Account' }} />
```

- [ ] **Step 3: Run the navigation test suite**

Run: `cd mobile && npx jest src/navigation/AppTabs.test.tsx`
Expected: PASS — this file already asserts every registered route name resolves to a mocked component; a typo in any of the 8 new names surfaces here before it surfaces at runtime.

- [ ] **Step 4: Commit**

```bash
git add mobile/src/navigation/types.ts mobile/src/navigation/AppTabs.tsx
git commit -m "feat(mobile): register 8 new Settings category routes"
```

---

## Task 11: Rewrite `SettingsScreen.tsx` as the root grouped list

**Files:**
- Modify: `mobile/src/screens/SettingsScreen.tsx` — replace entirely.
- Modify: `mobile/src/screens/SettingsScreen.test.tsx` — replace entirely (root-list navigation tests only).

**Interfaces:**
- Consumes: `SETTINGS_CATEGORIES`-equivalent local array (7 entries, same keys/labels as the web plan's `SettingsNav.tsx` for naming consistency, but this file defines its own copy — mobile and web don't share a module); `useNavigation<NativeStackNavigationProp<MoreStackParamList>>()`.
- Produces: `export function SettingsScreen()` (same export `AppTabs.tsx:73` already imports — no registration change needed for this route itself).

- [ ] **Step 1: Write the failing test**

```tsx
// mobile/src/screens/SettingsScreen.test.tsx
import { render, screen, fireEvent } from '@testing-library/react-native';
import { SettingsScreen } from './SettingsScreen';

const mockNavigate = jest.fn();
jest.mock('@react-navigation/native', () => ({ useNavigation: () => ({ navigate: mockNavigate }) }));

test('every category row pushes its own screen', () => {
  render(<SettingsScreen />);
  fireEvent.press(screen.getByText('Security'));
  expect(mockNavigate).toHaveBeenCalledWith('SettingsSecurity');
});

test('Help & Support and Legal stay as standalone rows, not folded into a category', () => {
  render(<SettingsScreen />);
  expect(screen.getByText('My Tickets')).toBeTruthy();
  expect(screen.getByText('Privacy Policy')).toBeTruthy();
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd mobile && npx jest src/screens/SettingsScreen.test.tsx`
Expected: FAIL — old `SettingsScreen.tsx` renders the full monolith, no navigable rows named "Security" exist yet in this shape.

- [ ] **Step 3: Write the implementation** — 7 category rows (icon + label + one-line description, chevron — same row visual language the original file already used for Help & Support/Legal at lines 460-483/513-534) each calling `navigation.navigate(<route>)`, plus the untouched Help & Support and Legal `<SectionCard>`s moved down verbatim from the original file:

```tsx
// mobile/src/screens/SettingsScreen.tsx
import { Linking, Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';
import { useNavigation } from '@react-navigation/native';
import type { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { SectionCard } from '../components/AccountUI';
import { spacing, useTheme } from '../theme';
import { webUrl } from '../lib/webUrl';
import type { MoreStackParamList } from '../navigation/types';

const CATEGORIES: { route: keyof MoreStackParamList; label: string; description: string }[] = [
  { route: 'SettingsGeneral', label: 'General', description: 'Preferences, timezone, theme' },
  { route: 'SettingsSecurity', label: 'Security', description: 'Password, verification, active sessions' },
  { route: 'SettingsCategorization', label: 'Categorization', description: 'How confident a suggestion must be to apply on its own' },
  { route: 'SettingsData', label: 'Data', description: 'Your imported statements and transaction history' },
  { route: 'SettingsConnectedApps', label: 'Connected Apps', description: 'Link external accounts Fynora can read transactions from' },
  { route: 'SettingsBankSync', label: 'Bank Sync', description: 'Automatically sync transactions from your linked bank accounts' },
  { route: 'SettingsAccount', label: 'Account', description: 'Deactivate or permanently delete your Fynora account' },
];

export function SettingsScreen() {
  const c = useTheme();
  const navigation = useNavigation<NativeStackNavigationProp<MoreStackParamList>>();

  return (
    <ScrollView style={{ backgroundColor: c.bg }} contentContainerStyle={styles.content}>
      <SectionCard title="Settings" subtitle="Manage your preferences, security, and account data">
        {CATEGORIES.map((cat) => (
          <Pressable
            key={cat.route}
            onPress={() => navigation.navigate(cat.route as never)}
            style={[styles.row, { borderBottomColor: c.border }]}
            accessibilityRole="button"
          >
            <View style={styles.rowMain}>
              <Text style={[styles.rowTitle, { color: c.ink }]}>{cat.label}</Text>
              <Text style={[styles.rowMeta, { color: c.mutedInk }]}>{cat.description}</Text>
            </View>
            <Text style={[styles.chevron, { color: c.muted }]} accessibilityElementsHidden importantForAccessibility="no">›</Text>
          </Pressable>
        ))}
      </SectionCard>

      {/* Unchanged from the pre-redesign file, verbatim: Help & Support and Legal are static
          links/tickets, not settings that get changed -- a one-item category pane for either
          would be worse than a direct row. See this plan's Global Constraints. */}
      <SectionCard title="Help & Support" subtitle="File a ticket, check on one, or tell us what's on your mind">
        <Pressable onPress={() => navigation.navigate('SupportTickets')} style={[styles.row, { borderBottomColor: c.border }]} accessibilityRole="button">
          <View style={styles.rowMain}>
            <Text style={[styles.rowTitle, { color: c.ink }]}>My Tickets</Text>
            <Text style={[styles.rowMeta, { color: c.mutedInk }]}>File a new one, or check on an existing one</Text>
          </View>
          <Text style={[styles.chevron, { color: c.muted }]} accessibilityElementsHidden importantForAccessibility="no">›</Text>
        </Pressable>
      </SectionCard>

      <SectionCard title="Legal" subtitle="How Fynora handles your data">
        <Pressable onPress={() => Linking.openURL(webUrl('/privacy'))} style={[styles.row, { borderBottomColor: c.border }]} accessibilityRole="link">
          <View style={styles.rowMain}><Text style={[styles.rowTitle, { color: c.ink }]}>Privacy Policy</Text></View>
          <Text style={[styles.chevron, { color: c.muted }]} accessibilityElementsHidden importantForAccessibility="no">›</Text>
        </Pressable>
        <Pressable onPress={() => Linking.openURL(webUrl('/terms'))} style={styles.row} accessibilityRole="link">
          <View style={styles.rowMain}><Text style={[styles.rowTitle, { color: c.ink }]}>Terms of Service</Text></View>
          <Text style={[styles.chevron, { color: c.muted }]} accessibilityElementsHidden importantForAccessibility="no">›</Text>
        </Pressable>
      </SectionCard>
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  content: { padding: spacing.md, paddingBottom: spacing.xl },
  row: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', paddingVertical: 12, borderBottomWidth: StyleSheet.hairlineWidth, minHeight: 44 },
  rowMain: { flex: 1, marginRight: spacing.sm },
  rowTitle: { fontSize: 15, fontWeight: '600' },
  rowMeta: { fontSize: 12, marginTop: 2 },
  chevron: { fontSize: 20, lineHeight: 20 },
});
```

Note: this drops `SendFeedback`/`FeedbackSheet` from the visible root list — cross-check against the original file (lines 472-482, `FeedbackSheet`) before finalizing; if it's meant to stay as a third standalone row alongside My Tickets, add it back exactly as it existed (state + `FeedbackSheet` render), don't drop functionality silently.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd mobile && npx jest src/screens/SettingsScreen.test.tsx`
Expected: PASS

- [ ] **Step 5: Run the full settings screen suite together**

Run: `cd mobile && npx jest src/screens/Settings`
Expected: all PASS — catches any leftover prop mismatch or stale import across the 9 files this plan touches.

- [ ] **Step 6: Commit**

```bash
git add mobile/src/screens/SettingsScreen.tsx mobile/src/screens/SettingsScreen.test.tsx
git commit -m "refactor(mobile): replace Settings monolith with grouped-list root + pushed screens"
```

---

## Task 12: Manual verification

- [ ] **Step 1:** `cd mobile && npx tsc --noEmit` (or the project's typecheck script) — zero errors across every file this plan touched.
- [ ] **Step 2:** `cd mobile && npx jest` — full suite green, not just the new Settings files.
- [ ] **Step 3:** Boot the iOS Simulator (or Android emulator), launch the app, navigate More → Settings, tap through all 7 category rows plus Help & Support and Legal, confirm each pushed screen loads its data and the native back button returns to the root list. Specifically exercise Bank Sync: Connect a Bank Account (expect it to open a URL — can't complete the real Setu consent flow in a simulator, but confirm the button fires `Linking.openURL` without crashing), and if a `PENDING_ACCOUNT_CONFIRMATION` fixture is reachable in a dev/staging account, confirm the Confirm Account push works end to end.
- [ ] **Step 4:** Confirm every new row hits the 44pt minimum touch target (Apple HIG `accessibility.md`, "mobile default control size 44×44pt") — visually inspect via a screenshot, or check computed frame heights with `native-describe-screen` if using Argent.
- [ ] **Step 5:** Re-read the full diff for anything the per-task steps didn't catch (stray unused imports from the moved blocks, a `FeedbackSheet` regression per Task 11's note, a console warning) — per this repo's standing verification rule, "nothing I can find is broken" is the bar.
