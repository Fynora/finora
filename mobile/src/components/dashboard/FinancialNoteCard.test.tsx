import { fireEvent, render, screen } from '@testing-library/react-native';
import { FinancialNoteCard } from './FinancialNoteCard';
import { ThemeProvider } from '../../theme';

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
});
