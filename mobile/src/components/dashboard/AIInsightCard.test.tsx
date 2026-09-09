import { fireEvent, render, screen } from '@testing-library/react-native';
import { AIInsightCard } from './AIInsightCard';
import { ThemeProvider } from '../../theme';

describe('AIInsightCard', () => {
  it('renders nothing without a real opportunity', () => {
    const { toJSON } = render(<ThemeProvider><AIInsightCard factor={null} potentialGain={null} onCreateGoal={jest.fn()} /></ThemeProvider>);
    expect(toJSON()).toBeNull();
  });

  it("states the opportunity and potential gain, using the server's own computed factor", () => {
    const onCreateGoal = jest.fn();
    render(<ThemeProvider><AIInsightCard factor="Emergency Fund" potentialGain={14} onCreateGoal={onCreateGoal} /></ThemeProvider>);

    expect(screen.getByText('Your emergency fund is the biggest opportunity to improve your score.')).toBeTruthy();
    expect(screen.getByText('+14 points')).toBeTruthy();

    fireEvent.press(screen.getByText('Create Goal'));
    expect(onCreateGoal).toHaveBeenCalled();
  });
});
