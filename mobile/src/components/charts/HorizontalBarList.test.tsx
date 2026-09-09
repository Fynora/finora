import { render, screen } from '@testing-library/react-native';
import { HorizontalBarList } from './HorizontalBarList';

describe('HorizontalBarList', () => {
  it('shows the empty message when there are no rows', () => {
    render(<HorizontalBarList rows={[]} valueLabel={(v) => `${v}`} emptyMessage="Nothing here yet." />);

    expect(screen.getByText('Nothing here yet.')).toBeTruthy();
  });

  it('renders every row with its label, sub-label, and formatted value', () => {
    render(
      <HorizontalBarList
        rows={[
          { key: 'a', label: 'Swiggy', sub: '12 txns', value: 4500 },
          { key: 'b', label: 'Amazon', sub: '8 txns', value: 3000 },
        ]}
        valueLabel={(v) => `₹${v}`}
        emptyMessage="Nothing here yet."
      />
    );

    expect(screen.getByText('Swiggy')).toBeTruthy();
    expect(screen.getByText('12 txns')).toBeTruthy();
    expect(screen.getByText('₹4500')).toBeTruthy();
    expect(screen.getByText('Amazon')).toBeTruthy();
    expect(screen.getByText('₹3000')).toBeTruthy();
  });
});
