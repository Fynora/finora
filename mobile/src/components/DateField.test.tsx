import { fireEvent, render, screen } from '@testing-library/react-native';
import { Platform } from 'react-native';
import { DateTimePickerAndroid } from '@react-native-community/datetimepicker';
import { DateField } from './DateField';
import { AppCoveredProvider } from './AppModal';
import { ThemeProvider } from '../theme';

const open = DateTimePickerAndroid.open as jest.Mock;
const dismiss = DateTimePickerAndroid.dismiss as jest.Mock;
const originalOS = Platform.OS;

function tree(covered: boolean) {
  return (
    <ThemeProvider>
      <AppCoveredProvider value={covered}>
        <DateField label="Target date" value="2026-12-01" onChange={jest.fn()} />
      </AppCoveredProvider>
    </ThemeProvider>
  );
}

beforeEach(() => {
  open.mockReset();
  dismiss.mockReset();
  dismiss.mockResolvedValue(true);
  Platform.OS = 'android';
});
afterEach(() => {
  Platform.OS = originalOS;
});

// Android's picker is an imperative native dialog: it floats above the lock screen, and the only
// way to take it down is to ask.
describe('DateField on Android while the app locks', () => {
  it('closes an open picker when the app becomes covered', () => {
    const view = render(tree(false));
    fireEvent.press(screen.getByRole('button', { name: /Target date: .*Change/ }));
    expect(open).toHaveBeenCalledTimes(1);

    view.rerender(tree(true));

    expect(dismiss).toHaveBeenCalledWith('date');
  });

  it('does nothing when no picker is open', () => {
    const view = render(tree(false));

    view.rerender(tree(true));

    expect(dismiss).not.toHaveBeenCalled();
  });

  it('does not try to close a picker the user already closed', () => {
    const view = render(tree(false));
    fireEvent.press(screen.getByRole('button', { name: /Target date: .*Change/ }));
    const { onChange } = open.mock.calls[0][0];
    onChange({ type: 'dismissed' });

    view.rerender(tree(true));

    expect(dismiss).not.toHaveBeenCalled();
  });

  it('survives the native dismiss call failing', () => {
    dismiss.mockRejectedValue(new Error('no picker'));
    const view = render(tree(false));
    fireEvent.press(screen.getByRole('button', { name: /Target date: .*Change/ }));

    expect(() => view.rerender(tree(true))).not.toThrow();
  });
});
