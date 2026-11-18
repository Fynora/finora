import { useEffect, useId, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { MoreVertical } from 'lucide-react';

export type RowMenuItem =
  | { label: string; onSelect: () => void; danger?: boolean; to?: never }
  | { label: string; to: string; danger?: boolean; onSelect?: never };

/**
 * A small actions menu behind a three-dot button. Keyboard: Enter/Space opens and focuses the first
 * item, arrow keys move, Escape closes and puts focus back on the button; a click outside closes.
 */
export function RowMenu({ label, items, disabled = false }: { label: string; items: RowMenuItem[]; disabled?: boolean }) {
  const [open, setOpen] = useState(false);
  const buttonRef = useRef<HTMLButtonElement>(null);
  const menuRef = useRef<HTMLDivElement>(null);
  const menuId = useId();

  const itemEls = () => Array.from(menuRef.current?.querySelectorAll<HTMLElement>('[role="menuitem"]') ?? []);

  function close(returnFocus: boolean) {
    setOpen(false);
    if (returnFocus) buttonRef.current?.focus();
  }

  useEffect(() => {
    if (!open) return;
    itemEls()[0]?.focus();
    function onPointerDown(e: MouseEvent) {
      const target = e.target as Node;
      if (!menuRef.current?.contains(target) && !buttonRef.current?.contains(target)) setOpen(false);
    }
    document.addEventListener('mousedown', onPointerDown);
    return () => document.removeEventListener('mousedown', onPointerDown);
  }, [open]);

  function onMenuKeyDown(e: React.KeyboardEvent) {
    const els = itemEls();
    const i = els.indexOf(document.activeElement as HTMLElement);
    if (e.key === 'Escape') {
      e.preventDefault();
      close(true);
    } else if (e.key === 'ArrowDown') {
      e.preventDefault();
      els[(i + 1) % els.length]?.focus();
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      els[(i - 1 + els.length) % els.length]?.focus();
    } else if (e.key === 'Tab') {
      setOpen(false);
    }
  }

  const itemClass = (danger?: boolean) =>
    `block w-full px-4 py-2.5 text-left text-sm font-medium hover:bg-surface focus:bg-surface focus:outline-none ${danger ? 'text-danger' : 'text-ink'}`;

  return (
    <div className="relative flex-shrink-0">
      <button
        ref={buttonRef}
        type="button"
        aria-label={label}
        aria-haspopup="menu"
        aria-expanded={open}
        aria-controls={open ? menuId : undefined}
        disabled={disabled}
        onClick={() => setOpen((o) => !o)}
        className="flex h-8 w-8 items-center justify-center rounded-lg text-muted hover:bg-surface hover:text-ink disabled:cursor-not-allowed disabled:opacity-40"
      >
        <MoreVertical size={18} aria-hidden="true" />
      </button>
      {open && (
        <div
          ref={menuRef}
          id={menuId}
          role="menu"
          aria-label={label}
          onKeyDown={onMenuKeyDown}
          className="absolute right-0 top-9 z-20 w-56 overflow-hidden rounded-xl border border-border bg-card py-1.5 shadow-lg"
        >
          {items.map((item) =>
            item.to ? (
              <Link key={item.label} role="menuitem" to={item.to} className={itemClass(item.danger)} onClick={() => setOpen(false)}>
                {item.label}
              </Link>
            ) : (
              <button
                key={item.label}
                type="button"
                role="menuitem"
                className={itemClass(item.danger)}
                onClick={() => {
                  // Focus goes back to the button first: a dialog this opens remembers what had
                  // focus, and this item is about to disappear.
                  close(true);
                  item.onSelect?.();
                }}
              >
                {item.label}
              </button>
            ),
          )}
        </div>
      )}
    </div>
  );
}
