import { act, fireEvent, render, screen } from '@testing-library/react-native';
import { RegisterScreen } from './RegisterScreen';
import { ThemeProvider } from '../theme';
import type { AuthStackParamList } from '../navigation/types';
import type { NativeStackScreenProps } from '@react-navigation/native-stack';

/**
 * Scoped to the Phase 3B prefill behaviour -- the one piece of RegisterScreen's logic this test
 * file exists for. Field validation and submission have no behaviour of their own beyond what
 * AuthContext.test.tsx already covers on the register() side.
 */

const mockRegister = jest.fn();
const mockLoginWithGoogle = jest.fn();
const mockLoginWithApple = jest.fn();
jest.mock('../context/AuthContext', () => ({
  useAuth: () => ({
    register: mockRegister,
    loginWithGoogle: mockLoginWithGoogle,
    loginWithApple: mockLoginWithApple,
  }),
}));

type Props = NativeStackScreenProps<AuthStackParamList, 'Register'>;

const mockNavigate = jest.fn();

function renderScreen(params?: { email?: string; phoneNumber?: string; referralCode?: string }) {
  const navigation = { navigate: mockNavigate } as unknown as Props['navigation'];
  const route = { key: 'Register', name: 'Register', params } as Props['route'];
  return render(
    <ThemeProvider>
      <RegisterScreen navigation={navigation} route={route} />
    </ThemeProvider>
  );
}

// Phase 3B: AuthEntryScreen sends whichever field the identifier looked like once it learns
// nextAction is CONTINUE (no existing account) -- prefilled here so the user doesn't have to
// retype what they already entered on the entry screen.
describe('RegisterScreen prefill from AuthEntry', () => {
  it('prefills the email field when arriving with an email in route params', () => {
    renderScreen({ email: 'jane@example.com' });

    expect(screen.getByLabelText('Email').props.value).toBe('jane@example.com');
  });

  it('prefills the mobile number field, stripped to its local 10 digits, when arriving with a phone number in route params', () => {
    const fakePhone = '+919876543210'; // synthetic-ok: same fake sequential number used throughout this app's test fixtures
    renderScreen({ phoneNumber: fakePhone });

    expect(screen.getByLabelText('Mobile number').props.value).toBe('9876543210' /* synthetic-ok */);
  });

  it('leaves both fields empty on an ordinary direct visit with no route params', () => {
    renderScreen();

    expect(screen.getByLabelText('Email').props.value).toBe('');
    expect(screen.getByLabelText('Mobile number').props.value).toBe('');
  });
});

// Refer & Earn MVP, extended Phase 5 with useReferralDeepLink's "finora://register?ref=CODE"
// (see that hook's own file) as a second way to arrive with a code already in hand -- typing it
// in by hand stays the only way for someone who doesn't have the app installed yet, or whose
// friend just read the code aloud. Uppercased as typed to match the backend's own stored format
// (ReferralService.generateUniqueCode) and sent as undefined, not '', when left blank -- see
// RegisterScreen's own comment on that call site.
describe('RegisterScreen referral code field', () => {
  it('prefills the referral code field when arriving via the referral deep link', () => {
    renderScreen({ referralCode: 'FRIEND123' });

    expect(screen.getByLabelText('Referral code (optional)').props.value).toBe('FRIEND123');
  });

  it('uppercases the referral code as it is typed', () => {
    renderScreen();

    fireEvent.changeText(screen.getByLabelText('Referral code (optional)'), 'ab12cd34');

    expect(screen.getByLabelText('Referral code (optional)').props.value).toBe('AB12CD34');
  });

  it('passes the typed referral code through to register() on submit', async () => {
    renderScreen();
    fillValidForm();
    fireEvent.changeText(screen.getByLabelText('Referral code (optional)'), 'ab12cd34');

    fireEvent.press(screen.getByText('Create account'));
    await settle();

    expect(mockRegister).toHaveBeenCalledWith(
      'jane@example.com', 'Str0ng!Pass', 'Jane Doe', '+919876543210' /* synthetic-ok */, 'AB12CD34'
    );
  });

  it('passes undefined, not an empty string, when the referral code is left blank', async () => {
    renderScreen();
    fillValidForm();

    fireEvent.press(screen.getByText('Create account'));
    await settle();

    expect(mockRegister).toHaveBeenCalledWith(
      'jane@example.com', 'Str0ng!Pass', 'Jane Doe', '+919876543210' /* synthetic-ok */, undefined
    );
  });
});

// Store readiness (ST1/S4): the screen must disclose terms/privacy consent, but as an implicit
// notice rather than an explicit checkbox gate -- covering the Google/Apple buttons too, which an
// explicit gate on the password path's own Create Account button never could (see the JSX's own
// comment on this screen for why that changed).
describe('RegisterScreen terms consent notice', () => {
  it('shows the consent notice with working Terms of Service and Privacy Policy links', () => {
    renderScreen();

    expect(screen.getByText(/By continuing, you agree to/)).toBeTruthy();
    expect(screen.getByText('Terms of Service')).toBeTruthy();
    expect(screen.getByText('Privacy Policy')).toBeTruthy();
  });

  it('calls register() once every field is valid, with no separate consent step required', async () => {
    renderScreen();
    fillValidForm();

    fireEvent.press(screen.getByText('Create account'));
    await settle();

    expect(mockRegister).toHaveBeenCalled();
  });
});

function fillValidForm() {
  fireEvent.changeText(screen.getByLabelText('Full name'), 'Jane Doe');
  fireEvent.changeText(screen.getByLabelText('Email'), 'jane@example.com');
  fireEvent.changeText(screen.getByLabelText('Mobile number'), '9876543210' /* synthetic-ok */);
  fireEvent.changeText(screen.getByLabelText('Password (min 8 characters)'), 'Str0ng!Pass');
  fireEvent.changeText(screen.getByLabelText('Confirm password'), 'Str0ng!Pass');
}

/** Lets handleSubmit's `finally` setState land before assertions run -- same helper as
 *  LoginScreen.test.tsx's own settle(). */
async function settle() {
  await act(async () => {});
}
