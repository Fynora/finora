import { render, screen } from '@testing-library/react-native';
import { Toast } from './Toast';

it('floats just above the tab pill', () => {
  render(<Toast title="Saved" />);
  // Safe-area insets are 0 under the test setup: pill cover 76 (64 + 12) plus spacing.sm (8).
  expect(screen.getByTestId('toast')).toHaveStyle({ bottom: 84 });
});
