import { fireEvent, render, screen } from '@testing-library/react-native';
import { Dimensions, Platform, StyleSheet } from 'react-native';
import { FinancialNoteCard } from './FinancialNoteCard';
import { shadows, ThemeProvider } from '../../theme';
import { withAlpha } from '../../theme/glass';
import { light } from '../../theme/palette';

// null = "not known yet", which every glass surface renders as its solid look. Pinned because the
// real hook flips from null to false a tick after the first render in a file.
const mockReduce = { value: null as boolean | null };
jest.mock('../../lib/useReduceTransparency', () => ({ useReduceTransparency: () => mockReduce.value }));
afterEach(() => { mockReduce.value = null; });

const renderNote = () =>
  render(<ThemeProvider><FinancialNoteCard factor="Emergency Fund" potentialGain={14} onCreateGoal={jest.fn()} /></ThemeProvider>);

const dimensionsGetSpy = jest.spyOn(Dimensions, 'get');
beforeEach(() => {
  dimensionsGetSpy.mockReturnValue({ width: 390, height: 844, scale: 2, fontScale: 1 });
});

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

  // The approved design lifts the note off the page like every other card.
  it('casts the card shadow on iOS and keeps its brass tint and edge while doing so', () => {
    mockReduce.value = false;
    renderNote();
    const note = StyleSheet.flatten(screen.getByTestId('financial-note').props.style);
    expect(note.boxShadow).toEqual(shadows.card);
    expect(note.borderColor).toBe(light.brass);
    // A surface with a shadow keeps its fill on the tint layer, not on the view that casts it.
    expect(note.backgroundColor).toBeUndefined();
    expect(screen.getByTestId('glass-tint')).toHaveStyle({ backgroundColor: withAlpha(light.brassBg, light.glassAlpha) });
  });

  it('declares no box shadow on Android', () => {
    const originalOS = Platform.OS;
    (Platform as { OS: string }).OS = 'android';
    try {
      renderNote();
      expect(StyleSheet.flatten(screen.getByTestId('financial-note').props.style).boxShadow).toBeUndefined();
    } finally {
      (Platform as { OS: string }).OS = originalOS;
    }
  });

  // Found on an iPhone 17 Pro simulator at an accessibility text size: icon, text and button
  // shared one row, the text was left a column about 90 points wide, and the sentence ran one
  // word per line with words broken in the middle ("Savin / gs", "impro / ve").
  it('stacks the text above Create Goal under large Dynamic Type, and keeps one row otherwise', () => {
    renderNote();
    expect(StyleSheet.flatten(screen.getByTestId('financial-note').props.style).flexDirection).toBe('row');

    dimensionsGetSpy.mockReturnValue({ width: 390, height: 844, scale: 2, fontScale: 1.3 });
    renderNote();
    const note = StyleSheet.flatten(screen.getByTestId('financial-note').props.style);
    expect(note.flexDirection).toBe('column');
    expect(note.alignItems).toBe('flex-start');
    // In a column the text must size to its content: a zero flex basis would collapse it.
    const text = StyleSheet.flatten(screen.getByTestId('financial-note-text').props.style);
    expect(text.flexBasis).toBe('auto');
    expect(text.alignSelf).toBe('stretch');
  });

  it('gives Create Goal a 44 point target', () => {
    renderNote();
    expect(StyleSheet.flatten(screen.getByRole('button').props.style).minHeight).toBe(44);
  });
});
