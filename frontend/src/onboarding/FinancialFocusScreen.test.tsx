import { render, screen, fireEvent } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import { FinancialFocusScreen } from './FinancialFocusScreen';

describe('FinancialFocusScreen', () => {
  // Nothing personalises from these answers yet, so the screen no longer promises it does.
  it('says what the answers are for, without promising personalisation', () => {
    render(<FinancialFocusScreen onContinue={vi.fn()} />);
    expect(screen.getByText('Select all that apply. It helps us understand what you want from Fynora.')).toBeInTheDocument();
    expect(screen.queryByText(/personali[sz]e/i)).toBeNull();
  });

  it('calls onContinue with the selected keys', () => {
    const onContinue = vi.fn();
    render(<FinancialFocusScreen onContinue={onContinue} />);
    fireEvent.click(screen.getByText(/Track my spending/));
    fireEvent.click(screen.getByText(/Reduce debt/));
    fireEvent.click(screen.getByRole('button', { name: 'Continue' }));
    expect(onContinue).toHaveBeenCalledWith(['TRACK_SPENDING', 'REDUCE_DEBT']);
  });

  it('allows continuing with nothing selected', () => {
    const onContinue = vi.fn();
    render(<FinancialFocusScreen onContinue={onContinue} />);
    fireEvent.click(screen.getByRole('button', { name: 'Continue' }));
    expect(onContinue).toHaveBeenCalledWith([]);
  });

  it('selecting "Just exploring" clears every other selection', () => {
    const onContinue = vi.fn();
    render(<FinancialFocusScreen onContinue={onContinue} />);
    fireEvent.click(screen.getByText(/Track my spending/));
    fireEvent.click(screen.getByText(/Just exploring/));
    fireEvent.click(screen.getByRole('button', { name: 'Continue' }));
    expect(onContinue).toHaveBeenCalledWith(['EXPLORING']);
  });

  it('selecting a normal option after "Just exploring" clears the exploring flag', () => {
    const onContinue = vi.fn();
    render(<FinancialFocusScreen onContinue={onContinue} />);
    fireEvent.click(screen.getByText(/Just exploring/));
    fireEvent.click(screen.getByText(/Track my spending/));
    fireEvent.click(screen.getByRole('button', { name: 'Continue' }));
    expect(onContinue).toHaveBeenCalledWith(['TRACK_SPENDING']);
  });

  it('shows the error message when passed one', () => {
    render(<FinancialFocusScreen onContinue={vi.fn()} error="Something went wrong." />);
    expect(screen.getByText('Something went wrong.')).toBeInTheDocument();
  });

  it('shows no error text when error is not passed', () => {
    render(<FinancialFocusScreen onContinue={vi.fn()} />);
    expect(screen.queryByText(/went wrong/i)).not.toBeInTheDocument();
  });
});
