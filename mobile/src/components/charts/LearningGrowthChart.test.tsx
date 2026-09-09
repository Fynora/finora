import { render, screen } from '@testing-library/react-native';
import { LearningGrowthChart } from './LearningGrowthChart';

describe('LearningGrowthChart', () => {
  it('shows an empty state with no points', () => {
    render(<LearningGrowthChart points={[]} width={320} />);

    expect(screen.getByText('No learning history yet.')).toBeTruthy();
  });

  it('renders the Learned/Corrected legend', () => {
    render(
      <LearningGrowthChart
        points={[
          { label: 'Jul 26', learnedCount: 8, correctedCount: 2 },
          { label: 'Aug 26', learnedCount: 15, correctedCount: 3 },
        ]}
        width={320}
      />
    );

    expect(screen.getByText('Learned')).toBeTruthy();
    expect(screen.getByText('Corrected')).toBeTruthy();
  });

  it('carries the counts as an accessibility label, for assistive tech that cannot read the SVG', () => {
    render(
      <LearningGrowthChart
        points={[{ label: 'Jul 26', learnedCount: 8, correctedCount: 2 }]}
        width={320}
      />
    );

    expect(screen.getByLabelText(/Learning growth over 1 months/)).toBeTruthy();
    expect(screen.getByLabelText(/Jul 26: 8 learned, 2 corrected/)).toBeTruthy();
  });

  // Regression coverage for a zero-total month, which would otherwise divide 0/0 computing the
  // learned/corrected split within that month's own bar. render() itself throws if that math
  // produces NaN geometry react-native-svg rejects, so a clean render is the assertion.
  it('does not crash on a month with zero of both counts', () => {
    render(
      <LearningGrowthChart
        points={[{ label: 'Jul 26', learnedCount: 0, correctedCount: 0 }]}
        width={320}
      />
    );

    expect(screen.getByLabelText(/Jul 26: 0 learned, 0 corrected/)).toBeTruthy();
  });
});
