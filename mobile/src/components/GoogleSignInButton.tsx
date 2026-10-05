import { useState } from 'react';
import { StyleSheet, View } from 'react-native';
import {
  GoogleSignin,
  GoogleSigninButton,
  isErrorWithCode,
  isSuccessResponse,
  statusCodes,
} from '@react-native-google-signin/google-signin';
import { useThemeSetting } from '../theme';
import { signOutOfGoogle } from '../lib/googleSession';
import { reportHandledEvent } from '../lib/monitoring';

/**
 * D-23 Phase 2. Native counterpart to frontend/src/components/GoogleSignInButton.tsx -- same
 * "unconfigured is a supported state, degrade silently" posture (see that component's own doc
 * comment), same onCredential/onError contract, but the sign-in mechanics are entirely different:
 * there's no Google Identity Services script to load, no DOM button to render into. This renders
 * the library's own official GoogleSigninButton (Google's brand guidelines require a specific
 * look; using their component is the simplest way to stay compliant, same reasoning as using
 * expo-apple-authentication's AppleAuthenticationButton for its sibling).
 *
 * GoogleSignin.configure() reads webClientId from EXPO_PUBLIC_GOOGLE_WEB_CLIENT_ID -- the OAuth
 * "Web client" id Google issues alongside the iOS/Android native ones, needed because that's the
 * client whose audience the BACKEND verifies against (GoogleIdTokenVerifierService), not the
 * platform-specific native client id. See mobile/.env.example.
 */
let configured = false;

function ensureConfigured(webClientId: string) {
  if (configured) return;
  GoogleSignin.configure({ webClientId });
  configured = true;
}

export function isGoogleSignInConfigured(): boolean {
  return Boolean(process.env.EXPO_PUBLIC_GOOGLE_WEB_CLIENT_ID);
}

/** Where in handlePress a failure happened: Play Services check, Google's own sign-in, or our
 *  onCredential handing the token to the backend. */
type SignInStage = 'play-services' | 'sign-in' | 'credential';

// The library's codes are fixed labels -- a Google status number ('10', '12500') or a constant
// ('NULL_PRESENTER', 'PLAY_SERVICES_NOT_AVAILABLE'). Anything else is reported as 'other' rather
// than sent, so a free-text value can never reach Sentry through this field.
function errorCodeLabel(err: unknown): string {
  if (!isErrorWithCode(err)) return 'none';
  const code = String(err.code);
  return /^[A-Za-z0-9_:-]{1,40}$/.test(code) ? code : 'other';
}

/**
 * Every failure below shows the user one generic message, so without this nothing records why.
 * Added 2026-10-05 after a Play Store build showed "unavailable" twice on a tester's phone and then
 * worked: neither attempt reached the backend, and the phone's log held no Google error either.
 * An event, not reportHandledError: the caught value's message is not sent, only the code.
 */
function reportGoogleSignInFailure(stage: SignInStage, code: string) {
  reportHandledEvent('Google sign-in failed', 'google-sign-in', { stage, code });
}

interface Props {
  onCredential: (idToken: string) => void | Promise<void>;
  onError: (message: string) => void;
}

export function GoogleSignInButton({ onCredential, onError }: Props) {
  const { resolved } = useThemeSetting();
  const [loading, setLoading] = useState(false);

  const webClientId = process.env.EXPO_PUBLIC_GOOGLE_WEB_CLIENT_ID;
  if (!webClientId) return null;

  async function handlePress() {
    ensureConfigured(webClientId!);
    setLoading(true);
    let stage: SignInStage = 'play-services';
    try {
      // Android-only in practice (resolves true immediately on iOS) -- see the library's own
      // docs. Surfaces Play Services' own "update Play Services" dialog rather than a confusing
      // downstream failure when a device's copy is missing or out of date.
      await GoogleSignin.hasPlayServices({ showPlayServicesUpdateDialog: true });
      stage = 'sign-in';
      // Drop any cached account first, or the SDK signs the previous one in silently and never
      // offers the picker -- see signOutOfGoogle's own comment for what that costs.
      await signOutOfGoogle();
      const response = await GoogleSignin.signIn();
      if (!isSuccessResponse(response)) return; // user cancelled -- not an error state
      if (!response.data.idToken) {
        reportGoogleSignInFailure(stage, 'NO_ID_TOKEN');
        onError('Google sign-in did not return a credential. Please try again.');
        return;
      }
      stage = 'credential';
      await onCredential(response.data.idToken);
    } catch (err) {
      if (isErrorWithCode(err) && err.code === statusCodes.SIGN_IN_CANCELLED) return;
      reportGoogleSignInFailure(stage, errorCodeLabel(err));
      onError('Sign in with Google is unavailable right now. Please try again later.');
    } finally {
      setLoading(false);
    }
  }

  return (
    <View style={styles.wrap}>
      <GoogleSigninButton
        size={GoogleSigninButton.Size.Wide}
        color={resolved === 'dark' ? GoogleSigninButton.Color.Dark : GoogleSigninButton.Color.Light}
        onPress={handlePress}
        disabled={loading}
        style={styles.button}
      />
    </View>
  );
}

const styles = StyleSheet.create({
  wrap: {
    alignItems: 'center',
  },
  button: {
    width: '100%',
    height: 48,
  },
});
