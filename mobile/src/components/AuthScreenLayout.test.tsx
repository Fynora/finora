import { render, screen } from '@testing-library/react-native';
import { KeyboardAvoidingView, Platform, Text } from 'react-native';
import { AuthScreenLayout } from './AuthScreenLayout';
import { ThemeProvider } from '../theme';

function renderLayout() {
  return render(
    <ThemeProvider>
      <AuthScreenLayout title="Sign in">
        <Text>form fields</Text>
      </AuthScreenLayout>
    </ThemeProvider>
  );
}

describe('AuthScreenLayout', () => {
  it('sets KeyboardAvoidingView to padding behavior on Android too, not just iOS', () => {
    // Regression guard for the same bug fixed in FynScreen (#1641): this layout used to follow
    // the app-wide `behavior={Platform.OS === 'ios' ? 'padding' : undefined}` convention, which
    // leaves KeyboardAvoidingView a no-op on Android (it relies entirely on the OS's own
    // windowSoftInputMode="resize" instead). This layout is the literal outermost element on
    // every auth screen (sign in, register, forgot password, verify phone/email change) with no
    // Modal in between, so it reproduces the same full-screen structure as FynScreen -- confirmed
    // on a real Android emulator (Pixel_10, Expo SDK 57) via a minimal repro mirroring this exact
    // structure: with the old code the password field vanished completely behind the keyboard;
    // with `behavior="padding"` unconditionally it stayed visible above it.
    //
    // Platform.OS is forced to 'android' here (jest-expo's own test default is iOS) -- without
    // this, the OLD, buggy `Platform.OS === 'ios' ? 'padding' : undefined` code would ALSO
    // evaluate to 'padding' under the test runner's default platform, and this test would pass
    // whether or not the real bug was fixed.
    //
    // UNSAFE_getByType, not getByTestId: KeyboardAvoidingView destructures `behavior` out of its
    // own props before spreading the rest onto the host View it renders, so a host-node query
    // would always read `undefined` here regardless of what was actually passed in.
    const originalOS = Platform.OS;
    Platform.OS = 'android';
    try {
      renderLayout();
      expect(screen.UNSAFE_getByType(KeyboardAvoidingView).props.behavior).toBe('padding');
    } finally {
      Platform.OS = originalOS;
    }
  });

  it('still renders its children and title', () => {
    renderLayout();
    expect(screen.getByText('Sign in')).toBeTruthy();
    expect(screen.getByText('form fields')).toBeTruthy();
  });
});
