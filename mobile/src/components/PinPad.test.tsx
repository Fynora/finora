import { fireEvent, render, screen } from '@testing-library/react-native';
import { PinPad } from './PinPad';
import { ThemeProvider } from '../theme';

function renderPad(props: Partial<Parameters<typeof PinPad>[0]> = {}) {
  const onComplete = jest.fn();
  render(
    <ThemeProvider>
      <PinPad title="Enter your Fynora PIN" onComplete={onComplete} {...props} />
    </ThemeProvider>
  );
  return { onComplete };
}

function press(...keys: string[]) {
  for (const k of keys) fireEvent.press(screen.getByLabelText(k));
}

describe('PinPad', () => {
  it('hands over the PIN once the fourth digit is entered', () => {
    const { onComplete } = renderPad();

    press('1', '2', '3');
    expect(onComplete).not.toHaveBeenCalled();
    press('4');

    expect(onComplete).toHaveBeenCalledWith('1234');
  });

  it('starts empty again after handing over a PIN', () => {
    const { onComplete } = renderPad();

    press('1', '2', '3', '4', '5', '6', '7', '8');

    expect(onComplete).toHaveBeenNthCalledWith(1, '1234');
    expect(onComplete).toHaveBeenNthCalledWith(2, '5678');
  });

  it('deletes the last digit', () => {
    const { onComplete } = renderPad();

    press('1', '2', 'Delete', '9', '8', '7');

    expect(onComplete).toHaveBeenCalledWith('1987');
  });

  it('says how many digits are entered, without saying which', () => {
    renderPad();
    press('7', '3');

    expect(screen.getByLabelText('2 of 4 digits entered')).toBeTruthy();
    expect(screen.queryByText('73')).toBeNull();
  });

  it('shows the error and the title', () => {
    renderPad({ error: 'Wrong PIN. 2 tries left.' });

    expect(screen.getByText('Enter your Fynora PIN')).toBeTruthy();
    expect(screen.getByText('Wrong PIN. 2 tries left.')).toBeTruthy();
  });

  it('offers the biometric key only when given a handler', () => {
    const onBiometric = jest.fn();
    renderPad({ onBiometric });
    fireEvent.press(screen.getByLabelText('Unlock with Face ID or fingerprint'));
    expect(onBiometric).toHaveBeenCalledTimes(1);
  });

  it('has no biometric key without a handler', () => {
    renderPad();
    expect(screen.queryByLabelText('Unlock with Face ID or fingerprint')).toBeNull();
  });

  it('ignores digits while busy', () => {
    const { onComplete } = renderPad({ busy: true });
    press('1', '2', '3', '4');
    expect(onComplete).not.toHaveBeenCalled();
  });
});
