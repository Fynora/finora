import { render, screen } from '@testing-library/react-native';
import { TrustNote } from './TrustNote';
import { ThemeProvider } from '../theme';

const COPY = 'Statements you upload are stored encrypted. Your data is never sold.';

it('is one accessibility element whose label is the sentence, not the glyph plus the sentence', () => {
  render(<ThemeProvider><TrustNote /></ThemeProvider>);
  // getByLabelText finds the container by its accessibilityLabel; the glyph has no label of its
  // own, so the simulator's tree used to read ", Statements you upload…" for this element.
  const note = screen.getByLabelText(COPY);
  expect(note.props.accessible).toBe(true);
  expect(note.props.accessibilityRole).toBe('text');
  expect(screen.getByText(COPY)).toBeTruthy();
});
