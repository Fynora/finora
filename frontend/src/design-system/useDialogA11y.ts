import { useEffect, useRef, useState } from 'react';

/** Tab stops inside the dialog. `:not([disabled])` matters because a busy dialog disables its
 *  buttons, which takes them out of the tab order and can leave the panel with nothing focusable in it. */
export const FOCUSABLE_SELECTOR =
  'button:not([disabled]), [href], input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])';

/**
 * Keyboard behaviour every modal dialog needs, in one place (it used to be copied by hand into
 * ConfirmDialog and Billing's FeatureComparisonModal, and missing from every other modal):
 *
 *  - focus moves into the dialog when it opens, and back to whatever opened it when it closes;
 *  - Escape closes it, unless `closeDisabled` (mirror whatever condition disables the backdrop
 *    click, e.g. a request in flight or a final "sent" step that must be acknowledged);
 *  - Tab and Shift+Tab stay inside it. Without this a dialog is only visually modal: the backdrop
 *    swallows mouse clicks, but Tab walks straight out into the page it is covering. That is not
 *    hypothetical: it is how a discard confirmation once ended up stacked over the import summary
 *    screen, with "Discard" then firing against an already-finalized session (see the step gating
 *    in pages/Import.tsx, which remains as defence in depth).
 *
 * Attach the returned ref to the panel element, and give that element `role="dialog"` (or
 * `alertdialog`), `aria-modal="true"`, an `aria-labelledby`, and `tabIndex={-1}` -- the last is
 * where focus parks when nothing inside is focusable.
 *
 * Only the topmost open dialog reacts to keys. That is decided from the DOM (the last
 * `[aria-modal="true"]` element in document order) rather than a mount-order stack, because React
 * runs a child's effects before its parent's, so a dialog opened together with a nested one would
 * otherwise register in the wrong order.
 */
export function useDialogA11y<T extends HTMLElement = HTMLDivElement>({
  onClose,
  closeDisabled = false,
}: {
  onClose: () => void;
  closeDisabled?: boolean;
}) {
  const panelRef = useRef<T>(null);
  // Captured during the first render, before this dialog's own children mount: a child with
  // autoFocus takes focus in the commit phase, before any effect here runs, so reading
  // document.activeElement in an effect would record that child instead of the real opener.
  const [opener] = useState(() => document.activeElement as HTMLElement | null);

  useEffect(() => {
    const panel = panelRef.current;
    // Respect a child that already took focus (autoFocus on the first field); otherwise land on
    // the first focusable control, or the panel itself.
    if (panel && !panel.contains(document.activeElement)) {
      (panel.querySelector<HTMLElement>(FOCUSABLE_SELECTOR) ?? panel).focus();
    }
    return () => opener?.focus?.();
  }, [opener]);

  useEffect(() => {
    function isTopmost(panel: HTMLElement) {
      const open = document.querySelectorAll('[aria-modal="true"]');
      return open.length === 0 || open[open.length - 1] === panel;
    }

    function handleKeyDown(e: KeyboardEvent) {
      const panel = panelRef.current;
      if (!panel || !isTopmost(panel)) return;

      if (e.key === 'Escape') {
        // Inert while closing is disabled -- an action already in flight is not cancelled by
        // dismissing its dialog. The Tab trap below deliberately gets no such exemption.
        if (!closeDisabled) onClose();
        return;
      }
      if (e.key !== 'Tab') return;

      const focusable = Array.from(panel.querySelectorAll<HTMLElement>(FOCUSABLE_SELECTOR));
      if (focusable.length === 0) {
        e.preventDefault();
        panel.focus();
        return;
      }

      const first = focusable[0];
      const last = focusable[focusable.length - 1];
      const active = document.activeElement;
      const outside = !panel.contains(active);

      if (e.shiftKey && (active === first || outside)) {
        e.preventDefault();
        last.focus();
      } else if (!e.shiftKey && (active === last || outside)) {
        e.preventDefault();
        first.focus();
      }
    }
    document.addEventListener('keydown', handleKeyDown);
    return () => document.removeEventListener('keydown', handleKeyDown);
  }, [onClose, closeDisabled]);

  return panelRef;
}
