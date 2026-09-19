import { render, screen } from '@testing-library/react-native';
import { KeyboardAvoidingView, Platform, Text } from 'react-native';
import { AccountActionSheet } from './AccountActionSheet';
import { ThemeProvider } from '../../theme';

function renderSheet() {
  return render(
    <ThemeProvider>
      <AccountActionSheet onClose={jest.fn()} dismissable closeLabel="Close test sheet">
        <Text>sheet body</Text>
      </AccountActionSheet>
    </ThemeProvider>
  );
}

describe('AccountActionSheet', () => {
  // The chrome behind Delete Account, Deactivate Account and Export My Data, each of which asks for
  // a password and/or a 6-digit code. Same Modal structure as ChangePasswordSheet, which was measured
  // sitting entirely under the keyboard on a real Android 11 phone. Platform.OS is forced to
  // 'android' because jest-expo defaults to iOS, where the old iOS-only expression also gives 'padding'.
  it('lifts the sheet above the keyboard on Android too', () => {
    const originalOS = Platform.OS;
    Platform.OS = 'android';
    try {
      renderSheet();
      expect(screen.UNSAFE_getByType(KeyboardAvoidingView).props.behavior).toBe('padding');
    } finally {
      Platform.OS = originalOS;
    }
  });

  it('still renders its children', () => {
    renderSheet();
    expect(screen.getByText('sheet body')).toBeTruthy();
  });
});
