import { render, screen } from '@testing-library/react-native';
import { ComingUpSection } from './ComingUpSection';
import type { RecurringItem } from '../../api/endpoints';

const item = (merchant: string, nextEstimate: string, averageAmount: number): RecurringItem => ({
  merchant, label: 'Monthly', averageAmount, occurrences: 4, lastDate: '2026-09-01', nextEstimate,
});

describe('ComingUpSection', () => {
  it('shows what is ahead, what may already be paid, and what is still to go out', () => {
    render(
      <ComingUpSection
        rows={[{ item: item('rent', '2026-10-05', 15000), past: true }, { item: item('tv', '2026-10-20', 649), past: false }]}
        stillToGoOut={649}
        latestStatementEnd="2026-09-30"
      />,
    );
    expect(screen.getByText('Coming up')).toBeTruthy();
    expect(screen.getByText('expected around 5 Oct')).toBeTruthy();
    expect(screen.getByText('around 20 Oct')).toBeTruthy();
    expect(screen.getByText('Still to go out this month')).toBeTruthy();
    expect(screen.getAllByText('₹649').length).toBeGreaterThanOrEqual(2);
    expect(screen.getByText("Your latest statement ends 30 Sep. Upload October's to see if these were paid.")).toBeTruthy();
    expect(screen.getByLabelText('rent, ₹15,000, expected around 5 Oct, may already be paid')).toBeTruthy();
    expect(screen.getByLabelText('tv, ₹649, around 20 Oct')).toBeTruthy();
  });

  it('writes September as Sep', () => {
    render(<ComingUpSection rows={[{ item: item('rent', '2026-09-05', 15000), past: true }]} stillToGoOut={0} latestStatementEnd="2026-08-31" />);
    expect(screen.getByText('expected around 5 Sep')).toBeTruthy();
    expect(screen.getByText("Your latest statement ends 31 Aug. Upload September's to see if these were paid.")).toBeTruthy();
  });

  it('asks for the latest statements when no statement date is known', () => {
    render(<ComingUpSection rows={[{ item: item('rent', '2026-10-05', 15000), past: true }]} stillToGoOut={0} latestStatementEnd={null} />);
    expect(screen.getByText('Upload your latest statements to see if these were paid.')).toBeTruthy();
  });

  it('adds no note when nothing is past', () => {
    render(<ComingUpSection rows={[{ item: item('tv', '2026-10-20', 649), past: false }]} stillToGoOut={649} latestStatementEnd="2026-09-30" />);
    expect(screen.queryByText(/to see if these were paid/)).toBeNull();
  });

  it('renders nothing with nothing coming up', () => {
    const { toJSON } = render(<ComingUpSection rows={[]} stillToGoOut={0} latestStatementEnd={null} />);
    expect(toJSON()).toBeNull();
  });
});
