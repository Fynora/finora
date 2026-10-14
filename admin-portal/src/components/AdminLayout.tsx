import { useCallback, useEffect, useRef, useState } from 'react';
import type { ReactNode } from 'react';
import { Menu, X } from 'lucide-react';
import { ErrorBoundary } from './ErrorBoundary';
import { Sidebar } from './Sidebar';
import { GlobalSearch } from './GlobalSearch';
import { ThemeToggle } from './ThemeToggle';
import { NotificationBell } from './NotificationBell';
import { useAdminAuth } from '../context/AdminAuthContext';
import { initials } from '../lib/initials';

/**
 * Deliberately simpler than the user app's AppShell + TopBar (finora/frontend/src/App.tsx,
 * TopBar.tsx). This app now has a header search box (GlobalSearch, Admin Portal Phase 2) fanning
 * out across Users/Merchants/Banks/Global Rules server-side (AdminSearchController), a theme
 * toggle (ThemeContext, local-only), an operational alerts bell (NotificationBell, dashboard
 * redesign PR3 -- reuses the same overview data Dashboard.tsx renders, not a new notification
 * platform), and an initials avatar (same helper Sidebar's own footer avatar uses) alongside the
 * existing email pill.
 */
export function AdminLayout({ title, subtitle, children }: { title: string; subtitle?: string; children: ReactNode }) {
  const { email, fullName } = useAdminAuth();
  const [navOpen, setNavOpen] = useState(false);
  const menuButtonRef = useRef<HTMLButtonElement>(null);
  const closeNav = useCallback(() => {
    setNavOpen(false);
    menuButtonRef.current?.focus();
  }, []);

  return (
    <div className="min-h-screen bg-bg flex">
      {/* Below lg the fixed 256px sidebar took most of a phone screen and pushed every page
          sideways, so there it moves behind the menu button instead. */}
      <div className="hidden lg:flex">
        <Sidebar />
      </div>
      {navOpen && <NavDrawer onClose={closeNav} />}
      {/* min-w-0: a flex item's minimum width defaults to its content's, so one wide table used to
          widen <main> and with it the whole page. With it, wide content scrolls in its own box. */}
      <main className="flex-1 min-w-0 p-4 sm:p-6 lg:p-8 max-w-[1600px]">
        <div className="flex flex-wrap xl:flex-nowrap items-start justify-between mb-6 lg:mb-8 gap-x-6 gap-y-3">
          {/* One row from xl, where the title gives way first as it did before this header could wrap,
              and only a very long email truncates. Narrower, title and actions take a row each: one
              row there either overflowed the page or cut the email to a couple of letters. */}
          <div className="flex items-start gap-3 min-w-0 xl:min-w-[10rem] xl:shrink-[1000]">
            <button
              ref={menuButtonRef}
              type="button"
              onClick={() => setNavOpen(true)}
              aria-label="Open navigation"
              aria-expanded={navOpen}
              className="lg:hidden w-9 h-9 -ml-1 flex-shrink-0 rounded-lg flex items-center justify-center text-muted hover:text-ink hover:bg-black/5"
            >
              <Menu size={20} />
            </button>
            <div className="min-w-0">
              <h1 className="text-xl font-bold text-ink break-words">{title}</h1>
              {subtitle && <p className="text-sm text-muted mt-1">{subtitle}</p>}
            </div>
          </div>
          <div className="flex flex-wrap xl:flex-nowrap items-center gap-3 min-w-0 max-w-full">
            <GlobalSearch />
            <ThemeToggle />
            <NotificationBell />
            <span title={email ?? undefined} className="min-w-0 truncate text-xs text-muted bg-card border border-border rounded-full px-3 py-1.5 shadow-card whitespace-nowrap">
              {email}
            </span>
            <div
              className="w-9 h-9 rounded-full bg-primary flex items-center justify-center text-on-primary text-xs font-semibold flex-shrink-0"
              title={fullName ?? undefined}
            >
              {initials(fullName)}
            </div>
          </div>
        </div>
        {/* Inside the layout, not around it: a page that throws is contained to the content area
            while the sidebar and global search keep rendering, so an admin can navigate away
            instead of being stranded on a blank screen. See App.tsx for the outer boundary that
            covers the routes which don't use this layout (login, setup, reset-password). */}
        <ErrorBoundary context="admin-page">{children}</ErrorBoundary>
      </main>
    </div>
  );
}

/**
 * The sidebar as a slide-over below lg. Same Escape-and-backdrop closing as EntityDrawer, plus
 * focus moved in on open (AdminLayout returns it to the menu button) and Tab kept inside while it
 * is open, since aria-modal tells a screen reader the page behind is unavailable.
 */
function NavDrawer({ onClose }: { onClose: () => void }) {
  const panelRef = useRef<HTMLDivElement>(null);
  const closeButtonRef = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    closeButtonRef.current?.focus();
    function handleKeyDown(e: KeyboardEvent) {
      if (e.key === 'Escape') {
        onClose();
        return;
      }
      if (e.key !== 'Tab' || !panelRef.current) return;
      const focusable = panelRef.current.querySelectorAll<HTMLElement>('a[href], button:not([disabled])');
      if (focusable.length === 0) return;
      const first = focusable[0];
      const last = focusable[focusable.length - 1];
      if (e.shiftKey && document.activeElement === first) {
        e.preventDefault();
        last.focus();
      } else if (!e.shiftKey && document.activeElement === last) {
        e.preventDefault();
        first.focus();
      }
    }
    document.addEventListener('keydown', handleKeyDown);
    // The page behind must not scroll under a finger dragging the drawer's own list.
    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => {
      document.removeEventListener('keydown', handleKeyDown);
      document.body.style.overflow = previousOverflow;
    };
  }, [onClose]);

  return (
    <div className="fixed inset-0 z-50 lg:hidden" role="dialog" aria-modal="true" aria-label="Navigation">
      <div className="absolute inset-0 bg-black/50" onClick={onClose} aria-hidden="true" />
      <div ref={panelRef} className="relative h-full w-64 max-w-[85vw] overflow-y-auto overflow-x-hidden">
        <button
          ref={closeButtonRef}
          type="button"
          onClick={onClose}
          aria-label="Close navigation"
          className="absolute top-5 right-3 z-10 w-8 h-8 rounded-lg flex items-center justify-center text-gray-400 hover:text-gray-200 hover:bg-sidebar-hover"
        >
          <X size={18} />
        </button>
        <Sidebar onNavigate={onClose} />
      </div>
    </div>
  );
}
