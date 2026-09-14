import { useEffect, useState } from 'react';
import { userApi } from '../../api/endpoints';

export interface AccountUserState {
  phoneNumber: string;
  phoneVerified: boolean;
  passwordChangedAt: string | null;
  signInMethod: 'PASSWORD' | 'GOOGLE';
  lowBalanceThreshold: number;
  timezone: string;
}

/**
 * The one userApi.get() call General, Security, Data, and Account all need a slice of -- fetched
 * once here instead of once per pane. Categorization/Connected Apps/Bank Sync each only ever
 * needed their own independent endpoint, so they keep fetching for themselves inside their own
 * pane component; this hook exists only for the genuinely shared slice.
 */
export function useAccountUser() {
  const [user, setUser] = useState<AccountUserState | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState(false);

  useEffect(() => {
    userApi.get().then((u) => {
      // '' not null: a Google Sign-In account has no phone number on file at all -- see
      // AuthService.createGoogleUserRecord's own doc comment on the backend.
      setUser({
        phoneNumber: u.phoneNumber ?? '',
        phoneVerified: u.phoneVerified,
        passwordChangedAt: u.passwordChangedAt,
        signInMethod: u.signInMethod,
        lowBalanceThreshold: u.lowBalanceThreshold,
        timezone: u.timezone,
      });
      setLoading(false);
    }).catch(() => {
      setLoadError(true);
      setLoading(false);
    });
  }, []);

  return { user, loading, loadError, setUser };
}
