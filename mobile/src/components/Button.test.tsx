import { fireEvent, render, screen } from '@testing-library/react-native';
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

  it('secondary darkens to the border tone while pressed, primary to primaryDark', () => {
    // Pressable's pressed flag is set by the responder system, not by fireEvent('pressIn'), so the
    // touch is delivered as a responder grant (release is deferred by Pressable's minimum press
    // duration, so only the down state is asserted).
    const grant = () => ({ persist: () => {}, nativeEvent: { touches: [], changedTouches: [], pageX: 1, pageY: 1, locationX: 1, locationY: 1, timestamp: Date.now(), identifier: 1, target: 1 } });
    render(<ThemeProvider><Button label="Continue" variant="secondary" onPress={() => {}} testID="s" /><Button label="Go" onPress={() => {}} testID="p" /></ThemeProvider>);
    expect(screen.getByTestId('s')).toHaveStyle({ backgroundColor: '#F4F1EC' });
    fireEvent(screen.getByTestId('s'), 'responderGrant', grant());
    expect(screen.getByTestId('s')).toHaveStyle({ backgroundColor: '#E6EAF2' });
    expect(screen.getByTestId('p')).toHaveStyle({ backgroundColor: '#262A33' });
    fireEvent(screen.getByTestId('p'), 'responderGrant', grant());
    expect(screen.getByTestId('p')).toHaveStyle({ backgroundColor: '#15171C' });
  });

  it('secondary reports disabled and busy the same way primary does', () => {
    render(<ThemeProvider><Button label="Continue" variant="secondary" onPress={() => {}} loading testID="b" /></ThemeProvider>);
    expect(screen.getByTestId('b')).toBeDisabled();
    expect(screen.getByTestId('b')).toBeBusy();
    expect(screen.queryByText('Continue')).toBeNull();
  });
});
