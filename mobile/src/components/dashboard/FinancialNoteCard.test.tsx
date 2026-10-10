import { fireEvent, render, screen } from '@testing-library/react-native';
import { StyleSheet } from 'react-native';
import { FinancialNoteCard } from './FinancialNoteCard';
import { ThemeProvider } from '../../theme';
import { withAlpha } from '../../theme/glass';
import { light } from '../../theme/palette';

// null = "not known yet", which every glass surface renders as its solid look. Pinned because the
// real hook flips from null to false a tick after the first render in a file.
const mockReduce = { value: null as boolean | null };
jest.mock('../../lib/useReduceTransparency', () => ({ useReduceTransparency: () => mockReduce.value }));
afterEach(() => { mockReduce.value = null; });

const renderNote = () =>
  render(<ThemeProvider><FinancialNoteCard factor="Emergency Fund" potentialGain={14} onCreateGoal={jest.fn()} /></ThemeProvider>);

describe('FinancialNoteCard', () => {
  it('renders nothing without a real opportunity', () => {
    const { toJSON } = render(<ThemeProvider><FinancialNoteCard factor={null} potentialGain={null} onCreateGoal={jest.fn()} /></ThemeProvider>);
    expect(toJSON()).toBeNull();
  });

  it("states the opportunity and potential gain, using the server's own computed factor", () => {
    const onCreateGoal = jest.fn();
    render(<ThemeProvider><FinancialNoteCard factor="Emergency Fund" potentialGain={14} onCreateGoal={onCreateGoal} /></ThemeProvider>);

    expect(screen.getByText('Emergency Fund has the most room to improve right now.')).toBeTruthy();
    expect(screen.getByText('+14 points')).toBeTruthy();

    fireEvent.press(screen.getByText('Create Goal'));
    expect(onCreateGoal).toHaveBeenCalled();
  });

  it.each([true, null])('is opaque brass with a brass edge when Reduce Transparency is %s', (reduce) => {
    mockReduce.value = reduce;
    renderNote();
    const style = StyleSheet.flatten(screen.getByTestId('financial-note').props.style);
    expect(style.backgroundColor).toBe(light.brassBg);
    expect(style.borderColor).toBe(light.brass);
  });

  it('is brass glass when transparency is allowed: the wash at the glass alpha, on the tint layer', () => {
    mockReduce.value = false;
    renderNote();
    expect(screen.getByTestId('glass-tint')).toHaveStyle({ backgroundColor: withAlpha(light.brassBg, light.glassAlpha) });
    // The brass edge is the note's signal; the glass edge must not paint over it.
    expect(StyleSheet.flatten(screen.getByTestId('financial-note').props.style).borderColor).toBe(light.brass);
  });

  it('gives Create Goal a 44 point target', () => {
    renderNote();
    expect(StyleSheet.flatten(screen.getByRole('button').props.style).minHeight).toBe(44);
  });
});
