import { useSearchParams } from 'react-router-dom';
import { useAccountUser } from './settings/useAccountUser';
import { SettingsNav, SETTINGS_CATEGORIES } from './settings/SettingsNav';
import { GeneralPane } from './settings/GeneralPane';
import { SecurityPane } from './settings/SecurityPane';
import { CategorizationPane } from './settings/CategorizationPane';
import { DataPane } from './settings/DataPane';
import { ConnectedAppsPane } from './settings/ConnectedAppsPane';
import { BankSyncPane } from './settings/BankSyncPane';
import { AccountPane } from './settings/AccountPane';
import type { AccountUserState } from './settings/useAccountUser';

const DEFAULT_TAB = 'general';

export default function Settings() {
  const [searchParams, setSearchParams] = useSearchParams();
  const rawTab = searchParams.get('tab');
  // Below md, an absent ?tab= means "show the list, nothing selected yet" -- at md and up it
  // means "show General in the pane column." Both are satisfied by keeping `tab` possibly-null
  // here and letting the two responsive branches below interpret it differently.
  const tab = rawTab && SETTINGS_CATEGORIES.some((c) => c.key === rawTab) ? rawTab : null;
  const { user, loading, loadError, setUser } = useAccountUser();

  function selectTab(key: string) {
    const next = new URLSearchParams(searchParams);
    next.set('tab', key);
    setSearchParams(next);
  }

  function backToList() {
    const next = new URLSearchParams(searchParams);
    next.delete('tab');
    setSearchParams(next);
  }

  const activePane = renderPane(tab ?? DEFAULT_TAB, { user, loading, loadError, setUser });

  // Each of SettingsNav and the pane renders exactly once in the DOM -- visibility for the two
  // responsive layouts (desktop: both side by side; narrow: list-then-pane) is CSS-only, driven
  // by these two class strings, not by rendering either element a second time. Rendering the pane
  // twice (once per responsive branch) was the first version of this component's actual bug: it
  // fired every pane's data fetch twice on every load.
  const navClassName = tab === null ? 'block' : 'hidden md:block';
  const paneClassName = tab === null ? 'hidden md:block' : 'block';

  return (
    <div>
      <div className="mb-6">
        <h1 className="text-2xl font-semibold text-ink">Settings</h1>
        <p className="text-sm text-muted mt-1">Manage your preferences, security, and account data.</p>
      </div>

      <div className="flex gap-8 items-start">
        <div className={navClassName}>
          <SettingsNav active={tab ?? DEFAULT_TAB} onSelect={selectTab} />
        </div>
        <div className={`flex-1 min-w-0 max-w-2xl ${paneClassName}`}>
          <button type="button" onClick={backToList} className="text-sm text-muted mb-4 md:hidden">‹ Settings</button>
          {activePane}
        </div>
      </div>
    </div>
  );
}

function renderPane(
  key: string,
  props: { user: AccountUserState | null; loading: boolean; loadError: boolean; setUser: (u: AccountUserState) => void }
) {
  switch (key) {
    case 'security':
      return <SecurityPane user={props.user} loading={props.loading} loadError={props.loadError} onUserUpdate={props.setUser} />;
    case 'categorization':
      return <CategorizationPane />;
    case 'data':
      return <DataPane loading={props.loading} loadError={props.loadError} signInMethod={props.user?.signInMethod ?? 'PASSWORD'} />;
    case 'connected-apps':
      return <ConnectedAppsPane />;
    case 'bank-sync':
      return <BankSyncPane />;
    case 'account':
      return <AccountPane loading={props.loading} loadError={props.loadError} signInMethod={props.user?.signInMethod ?? 'PASSWORD'} />;
    default:
      return <GeneralPane user={props.user} loading={props.loading} loadError={props.loadError} onUserUpdate={props.setUser} />;
  }
}
