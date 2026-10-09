import { render, screen } from '@testing-library/react-native';
import { Button } from './Button';
import { ThemeProvider } from '../theme';

// The secondary variant exists so AuthEntry's Continue can sit beneath Google/Apple without
// competing with them. Its fill is near the card colour, so the border is what draws the edge.
describe('Button variants', () => {
  it('primary keeps the dark fill and light label', () => {
    render(<ThemeProvider><Button label="Go" onPress={() => {}} testID="b" /></ThemeProvider>);
    expect(screen.getByTestId('b')).toHaveStyle({ backgroundColor: '#262A33' });
    expect(screen.getByText('Go')).toHaveStyle({ color: '#FFFFFF' });
  });

  it('secondary is the tonal fill with an ink label and a visible ink-alpha border', () => {
    render(<ThemeProvider><Button label="Continue" variant="secondary" onPress={() => {}} testID="b" /></ThemeProvider>);
    expect(screen.getByTestId('b')).toHaveStyle({
      backgroundColor: '#F4F1EC',
      borderWidth: 1,
      borderColor: 'rgba(15,23,42,0.5)',
      minHeight: 48,
    });
    expect(screen.getByText('Continue')).toHaveStyle({ color: '#0F172A' });
  });

  it('secondary reports disabled and busy the same way primary does', () => {
    render(<ThemeProvider><Button label="Continue" variant="secondary" onPress={() => {}} loading testID="b" /></ThemeProvider>);
    expect(screen.getByTestId('b')).toBeDisabled();
    expect(screen.getByTestId('b')).toBeBusy();
    expect(screen.queryByText('Continue')).toBeNull();
  });
});
