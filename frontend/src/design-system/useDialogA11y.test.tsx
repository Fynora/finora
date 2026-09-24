import { describe, it, expect, vi } from 'vitest';
import { useState } from 'react';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useDialogA11y } from './useDialogA11y';

function Dialog({ onClose, closeDisabled, autoFocusField, label = 'Dialog', children }: {
  onClose: () => void;
  closeDisabled?: boolean;
  autoFocusField?: boolean;
  label?: string;
  children?: React.ReactNode;
}) {
  const panelRef = useDialogA11y({ onClose, closeDisabled });
  return (
    <div ref={panelRef} role="dialog" aria-modal="true" aria-label={label} tabIndex={-1}>
      <button type="button">{label} first</button>
      <input aria-label={`${label} field`} autoFocus={autoFocusField} />
      <button type="button">{label} last</button>
      {children}
    </div>
  );
}

function Page({ closeDisabled, autoFocusField }: { closeDisabled?: boolean; autoFocusField?: boolean }) {
  const [open, setOpen] = useState(false);
  return (
    <>
      <button type="button" onClick={() => setOpen(true)}>Open</button>
      <button type="button">Behind</button>
      {open && <Dialog onClose={() => setOpen(false)} closeDisabled={closeDisabled} autoFocusField={autoFocusField} />}
    </>
  );
}

describe('useDialogA11y', () => {
  it('moves focus to the first control on open and back to the opener on close', async () => {
    const user = userEvent.setup();
    render(<Page />);

    await user.click(screen.getByRole('button', { name: 'Open' }));
    expect(screen.getByRole('button', { name: 'Dialog first' })).toHaveFocus();

    await user.keyboard('{Escape}');
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Open' })).toHaveFocus();
  });

  it('leaves focus on a field that autofocused itself, and still restores the real opener', async () => {
    const user = userEvent.setup();
    render(<Page autoFocusField />);

    await user.click(screen.getByRole('button', { name: 'Open' }));
    expect(screen.getByRole('textbox', { name: 'Dialog field' })).toHaveFocus();

    await user.keyboard('{Escape}');
    expect(screen.getByRole('button', { name: 'Open' })).toHaveFocus();
  });

  it('does not close on Escape while closing is disabled', async () => {
    const user = userEvent.setup();
    render(<Page closeDisabled />);

    await user.click(screen.getByRole('button', { name: 'Open' }));
    await user.keyboard('{Escape}');

    expect(screen.getByRole('dialog')).toBeInTheDocument();
  });

  it('keeps Tab and Shift+Tab inside the dialog, never reaching the page behind', async () => {
    const user = userEvent.setup();
    render(<Page />);
    await user.click(screen.getByRole('button', { name: 'Open' }));

    await user.tab();
    expect(screen.getByRole('textbox', { name: 'Dialog field' })).toHaveFocus();
    await user.tab();
    expect(screen.getByRole('button', { name: 'Dialog last' })).toHaveFocus();
    await user.tab();
    expect(screen.getByRole('button', { name: 'Dialog first' })).toHaveFocus();
    await user.tab({ shift: true });
    expect(screen.getByRole('button', { name: 'Dialog last' })).toHaveFocus();
  });

  it('keeps the trap active while closing is disabled', async () => {
    const user = userEvent.setup();
    render(<Page closeDisabled />);
    await user.click(screen.getByRole('button', { name: 'Open' }));

    await user.tab({ shift: true });
    expect(screen.getByRole('button', { name: 'Dialog last' })).toHaveFocus();
  });

  it('only the topmost of two stacked dialogs reacts to Escape and Tab', async () => {
    const user = userEvent.setup();
    const outerClose = vi.fn();
    const innerClose = vi.fn();
    render(
      <Dialog onClose={outerClose} label="Outer">
        <Dialog onClose={innerClose} label="Inner" />
      </Dialog>,
    );

    screen.getByRole('button', { name: 'Inner last' }).focus();
    await user.tab();
    expect(screen.getByRole('button', { name: 'Inner first' })).toHaveFocus();

    await user.keyboard('{Escape}');
    expect(innerClose).toHaveBeenCalledTimes(1);
    expect(outerClose).not.toHaveBeenCalled();
  });
});
