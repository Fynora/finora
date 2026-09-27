import { fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { InflowKindPicker } from './InflowKindPicker';
import type { InflowKind } from '../types';

const kinds: InflowKind[] = [
  { id: 'k1', name: 'Income', countsAsIncome: true, builtIn: 'INCOME' },
  { id: 'k2', name: 'Family support', countsAsIncome: true, builtIn: 'FAMILY_SUPPORT' },
];

describe('InflowKindPicker', () => {
  it('picks a kind', () => {
    const onPick = jest.fn();
    render(<InflowKindPicker kinds={kinds} selectedId={null} onPick={onPick} onCreate={jest.fn()} />);
    fireEvent.press(screen.getByText('Family support'));
    expect(onPick).toHaveBeenCalledWith(kinds[1]);
  });

  it('creates a kind only after the income question is answered', async () => {
    const onCreate = jest.fn().mockResolvedValue(undefined);
    render(<InflowKindPicker kinds={kinds} selectedId={null} onPick={jest.fn()} onCreate={onCreate} />);
    fireEvent.press(screen.getByText('+ New kind…'));
    fireEvent.changeText(screen.getByLabelText('Name'), 'Split with flatmate');
    fireEvent.press(screen.getByText('Create'));
    expect(onCreate).not.toHaveBeenCalled();
    fireEvent.press(screen.getByText("○ No, don't count it"));
    fireEvent.press(screen.getByText('Create'));
    await waitFor(() => expect(onCreate).toHaveBeenCalledWith('Split with flatmate', false));
  });

  it('shows the server message when a name is taken', async () => {
    const onCreate = jest.fn().mockRejectedValue(new Error('taken'));
    render(<InflowKindPicker kinds={kinds} selectedId={null} onPick={jest.fn()} onCreate={onCreate} />);
    fireEvent.press(screen.getByText('+ New kind…'));
    fireEvent.changeText(screen.getByLabelText('Name'), 'Income');
    fireEvent.press(screen.getByText('○ Yes, count it'));
    fireEvent.press(screen.getByText('Create'));
    expect(await screen.findByText('Could not create this kind.')).toBeOnTheScreen();
  });
});
