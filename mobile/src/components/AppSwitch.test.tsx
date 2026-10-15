import { fireEvent, render, screen } from '@testing-library/react-native';
import { AppSwitch } from './AppSwitch';
import { expectHaptics } from '../test/haptics';
import { __setHapticsStateForTests } from '../lib/haptics';

it('fires one selection when the person changes the value, after the change is handled', () => {
  const onValueChange = jest.fn();
  render(<AppSwitch accessibilityLabel="x" value={false} onValueChange={onValueChange} />);
  fireEvent(screen.getByLabelText('x'), 'valueChange', true);
  expect(onValueChange).toHaveBeenCalledWith(true);
  expectHaptics({ selection: 1 });
});

it('fires nothing when silent', () => {
  render(<AppSwitch accessibilityLabel="x" value={false} onValueChange={jest.fn()} silent />);
  fireEvent(screen.getByLabelText('x'), 'valueChange', true);
  expectHaptics({});
});

it('fires nothing when haptics are off', () => {
  __setHapticsStateForTests('off');
  render(<AppSwitch accessibilityLabel="x" value={false} onValueChange={jest.fn()} />);
  fireEvent(screen.getByLabelText('x'), 'valueChange', true);
  expectHaptics({});
});
