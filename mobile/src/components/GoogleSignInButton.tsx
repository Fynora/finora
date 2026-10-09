import { useState } from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import Svg, { Path } from 'react-native-svg';
import {
  GoogleSignin,
  isErrorWithCode,
  isSuccessResponse,
  statusCodes,
} from '@react-native-google-signin/google-signin';
import { signOutOfGoogle } from '../lib/googleSession';
import { reportHandledEvent } from '../lib/monitoring';
import { radius } from '../theme';

/**
 * D-23 Phase 2. Native counterpart to frontend/src/components/GoogleSignInButton.tsx -- same
 * "unconfigured is a supported state, degrade silently" posture (see that component's own doc
 * comment), same onCredential/onError contract, but the sign-in mechanics are entirely different:
 * there's no Google Identity Services script to load, no DOM button to render into. The button
 * itself is drawn here, not the library's GoogleSigninButton: that one pins the "G" to the left
 * edge and the label beside it, which sat oddly above the centred Sign in with Apple button
 * (2026-10-09). Google's sign-in branding guidelines allow a custom button as long as it keeps
 * the official "G", the "Sign in with Google" wording and the light-theme colours (white fill,
 * #747775 outline, #1F1F1F label), which this does; the centred layout, 48pt height, corner
 * radius and the dimmed loading state match AppleSignInButton so the two read as a pair.
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
    <View style={[styles.wrap, loading && styles.loading]} pointerEvents={loading ? 'none' : 'auto'}>
      <Pressable
        accessibilityRole="button"
        accessibilityLabel="Sign in with Google"
        accessibilityState={{ disabled: loading }}
        disabled={loading}
        onPress={handlePress}
        testID="google-sign-in-button"
        style={({ pressed }) => [styles.button, pressed && styles.pressed]}
      >
        <GoogleG />
        <Text style={styles.label}>Sign in with Google</Text>
      </Pressable>
    </View>
  );
}

/** The official four-colour "G" (Google's 48x48 sign-in branding asset), drawn at the 20pt the
 *  guidelines size it for a 48pt button. */
function GoogleG() {
  return (
    <Svg width={20} height={20} viewBox="0 0 48 48" accessible={false}>
      <Path fill="#EA4335" d="M24 9.5c3.54 0 6.71 1.22 9.21 3.6l6.85-6.85C35.9 2.38 30.47 0 24 0 14.62 0 6.51 5.38 2.56 13.22l7.98 6.19C12.43 13.72 17.74 9.5 24 9.5z" />
      <Path fill="#4285F4" d="M46.98 24.55c0-1.57-.15-3.09-.38-4.55H24v9.02h12.94c-.58 2.96-2.26 5.48-4.78 7.18l7.73 6c4.51-4.18 7.09-10.36 7.09-17.65z" />
      <Path fill="#FBBC05" d="M10.53 28.59c-.48-1.45-.76-2.99-.76-4.59s.27-3.14.76-4.59l-7.98-6.19C.92 16.46 0 20.12 0 24c0 3.88.92 7.54 2.56 10.78l7.97-6.19z" />
      <Path fill="#34A853" d="M24 48c6.48 0 11.93-2.13 15.89-5.81l-7.73-6c-2.15 1.45-4.92 2.3-8.16 2.3-6.26 0-11.57-4.22-13.47-9.91l-7.98 6.19C6.51 42.62 14.62 48 24 48z" />
    </Svg>
  );
}

// White in both themes, like the web's 'outline' button and the Apple button's dark-theme WHITE
// style; the colours are Google's light-theme button spec, so they are fixed, not theme tokens.
const styles = StyleSheet.create({
  wrap: {
    alignItems: 'center',
  },
  loading: {
    opacity: 0.5,
  },
  button: {
    width: '100%',
    minHeight: 48,
    paddingHorizontal: 12,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: 8,
    borderRadius: radius.md,
    borderWidth: 1,
    borderColor: '#747775',
    backgroundColor: '#ffffff',
  },
  pressed: {
    backgroundColor: '#f2f2f2',
  },
  label: {
    fontSize: 17,
    fontWeight: '600',
    color: '#1F1F1F',
    // At accessibility text sizes the label wraps; without these the row overflowed and pushed
    // the G past the button's left edge (seen on the iOS 26.5 simulator at AX extra-large).
    flexShrink: 1,
    textAlign: 'center',
  },
});
