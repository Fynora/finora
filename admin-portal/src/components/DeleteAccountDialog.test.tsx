import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { DeleteAccountDialog } from './DeleteAccountDialog';

describe('DeleteAccountDialog', () => {
  it('never enables delete with an empty box, even for an account with no email', () => {
    const onConfirm = vi.fn();
    render(<DeleteAccountDialog name="Someone" email="" busy={false} onConfirm={onConfirm} onCancel={vi.fn()} />);

    const confirm = screen.getByRole('button', { name: 'Delete permanently' });
    expect(confirm).toBeDisabled();
    fireEvent.change(screen.getByLabelText(/Type the account's email/), { target: { value: '   ' } });
    expect(confirm).toBeDisabled();
    fireEvent.click(confirm);
    expect(onConfirm).not.toHaveBeenCalled();
  });
});
