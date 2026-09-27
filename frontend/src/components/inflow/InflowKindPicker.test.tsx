import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { InflowKindPicker } from './InflowKindPicker';
import type { InflowKind } from '../../api/endpoints';

const kinds: InflowKind[] = [
  { id: 'k1', name: 'Income', countsAsIncome: true, builtIn: 'INCOME' },
  { id: 'k2', name: 'Family support', countsAsIncome: true, builtIn: 'FAMILY_SUPPORT' },
  { id: 'k6', name: 'Rent from tenant', countsAsIncome: true, builtIn: null },
];

describe('InflowKindPicker', () => {
  it('picks a kind', async () => {
    const onPick = vi.fn();
    render(<InflowKindPicker kinds={kinds} selectedId={null} onPick={onPick} onCreate={vi.fn()} />);
    await userEvent.click(screen.getByRole('button', { name: /Family support/ }));
    expect(onPick).toHaveBeenCalledWith(kinds[1]);
  });

  it('creates a kind only after the income question is answered', async () => {
    const onCreate = vi.fn().mockResolvedValue(undefined);
    render(<InflowKindPicker kinds={kinds} selectedId={null} onPick={vi.fn()} onCreate={onCreate} />);
    await userEvent.click(screen.getByRole('button', { name: '+ New kind…' }));
    await userEvent.type(screen.getByLabelText('Name'), 'Split with flatmate');
    expect(screen.getByRole('button', { name: 'Create' })).toBeDisabled();
    await userEvent.click(screen.getByRole('radio', { name: "No, don't count it" }));
    await userEvent.click(screen.getByRole('button', { name: 'Create' }));
    expect(onCreate).toHaveBeenCalledWith('Split with flatmate', false);
  });

  it('shows the server message when a name is taken', async () => {
    const onCreate = vi.fn().mockRejectedValue({ response: { data: { message: 'You already have a kind with this name.' } } });
    render(<InflowKindPicker kinds={kinds} selectedId={null} onPick={vi.fn()} onCreate={onCreate} />);
    await userEvent.click(screen.getByRole('button', { name: '+ New kind…' }));
    await userEvent.type(screen.getByLabelText('Name'), 'Income');
    await userEvent.click(screen.getByRole('radio', { name: 'Yes, count it' }));
    await userEvent.click(screen.getByRole('button', { name: 'Create' }));
    expect(await screen.findByText('You already have a kind with this name.')).toBeInTheDocument();
  });
});
