import { act, fireEvent, render, screen } from '@testing-library/react-native';
import { ConsentScreen } from './ConsentScreen';
import type { UserSettings } from '../api/endpoints';

// Its own file covers it; here it only has to open.
jest.mock('../screens/settings/DeleteAccountSheet', () => ({
  DeleteAccountSheet: () => {
    const { Text } = require('react-native');
    return <Text testID="delete-account-sheet">DeleteAccountSheet</Text>;
  },
}));

function profile(overrides: Partial<UserSettings> = {}): UserSettings {
  return {
    email: 'consent@example.com', fullName: 'Consent Screen', lowBalanceThreshold: 0, theme: 'system',
    timezone: 'Asia/Kolkata', phoneNumber: null, phoneVerified: true, createdAt: '2026-10-01T00:00:00Z',
    passwordChangedAt: null, signInMethod: 'GOOGLE',
    termsAcceptanceRequired: true, currentTermsVersion: '2026-10', aiProcessingConsent: null,
    ...overrides,
  };
}

function renderScreen(p: UserSettings, onSubmit = jest.fn().mockResolvedValue(undefined), onOutdated = jest.fn().mockResolvedValue(undefined)) {
  const onSignOut = jest.fn();
  render(<ConsentScreen profile={p} onSubmit={onSubmit} onOutdated={onOutdated} onSignOut={onSignOut} />);
  return { onSubmit, onOutdated, onSignOut };
}

describe('ConsentScreen', () => {
  it('shows the notice and both boxes unticked, and Continue does nothing until the required one is ticked', () => {
    const { onSubmit } = renderScreen(profile());

    expect(screen.getByTestId('consent-notice')).toBeTruthy();
    expect(screen.getByTestId('consent-terms-box').props.accessibilityState).toEqual({ checked: false });
    expect(screen.getByTestId('consent-ai-box').props.accessibilityState).toEqual({ checked: false });
    fireEvent.press(screen.getByText('Continue'));
    expect(onSubmit).not.toHaveBeenCalled();
    expect(screen.queryByText(/skip/i)).toBeNull();
  });

  it('records acceptance of the current version and the AI answer', async () => {
    const { onSubmit } = renderScreen(profile());

    fireEvent.press(screen.getByTestId('consent-terms-box'));
    fireEvent.press(screen.getByTestId('consent-ai-box'));
    await act(async () => { fireEvent.press(screen.getByText('Continue')); });

    expect(onSubmit).toHaveBeenCalledWith({ acceptTermsVersion: '2026-10', aiProcessing: true });
  });

  /** Accepted by the old notice, never asked about AI: the AI question only, and no is an answer. */
  it('asks only the AI question when the current Terms are already accepted', async () => {
    const { onSubmit } = renderScreen(profile({ termsAcceptanceRequired: false }));

    expect(screen.getByText('One choice about AI features')).toBeTruthy();
    expect(screen.queryByTestId('consent-terms-box')).toBeNull();
    await act(async () => { fireEvent.press(screen.getByText('Continue')); });

    expect(onSubmit).toHaveBeenCalledWith({ aiProcessing: false });
  });

  it('starts from an earlier yes to AI when only the Terms moved on', () => {
    renderScreen(profile({ aiProcessingConsent: true }));

    expect(screen.getByTestId('consent-ai-box').props.accessibilityState).toEqual({ checked: true });
  });

  it('reloads and asks again when the documents changed while it was open', async () => {
    const onSubmit = jest.fn().mockRejectedValue({ response: { status: 409, data: { errorCode: 'PRIVACY_002' } } });
    const { onOutdated } = renderScreen(profile(), onSubmit);

    fireEvent.press(screen.getByTestId('consent-terms-box'));
    await act(async () => { fireEvent.press(screen.getByText('Continue')); });

    expect(screen.getByText(/changed just now/)).toBeTruthy();
    expect(onOutdated).toHaveBeenCalled();
    expect(screen.getByTestId('consent-terms-box').props.accessibilityState).toEqual({ checked: false });
  });

  it('keeps the step up with a retry message when saving fails', async () => {
    const onSubmit = jest.fn().mockRejectedValue(new Error('network down'));
    renderScreen(profile({ termsAcceptanceRequired: false }), onSubmit);

    await act(async () => { fireEvent.press(screen.getByText('Continue')); });

    expect(screen.getByText(/Check your connection/)).toBeTruthy();
  });

  it('offers sign out and account deletion as the ways out', () => {
    const { onSignOut, onSubmit } = renderScreen(profile());

    fireEvent.press(screen.getByText('Sign out'));
    expect(onSignOut).toHaveBeenCalled();
    fireEvent.press(screen.getByText('Delete my account'));
    expect(screen.getByTestId('delete-account-sheet')).toBeTruthy();
    expect(onSubmit).not.toHaveBeenCalled();
  });
});
