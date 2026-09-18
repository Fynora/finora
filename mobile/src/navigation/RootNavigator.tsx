import { ActivityIndicator, StyleSheet, View } from 'react-native';
import { NavigationContainer, DefaultTheme, DarkTheme, useNavigationContainerRef } from '@react-navigation/native';
import { createNativeStackNavigator } from '@react-navigation/native-stack';
import { AuthEntryScreen } from '../screens/AuthEntryScreen';
import { LoginScreen } from '../screens/LoginScreen';
import { RegisterScreen } from '../screens/RegisterScreen';
import { ForgotPasswordScreen } from '../screens/ForgotPasswordScreen';
import { VerifyPhoneScreen } from '../screens/VerifyPhoneScreen';
import { AppTabs } from './AppTabs';
import { OnboardingNavigator } from '../onboarding/OnboardingNavigator';
import { useOnboardingStep } from '../onboarding/OnboardingStepContext';
import { TourTargetProvider } from '../onboarding/TourTargetRegistry';
import { TourOverlay } from '../onboarding/TourOverlay';
import { TOUR_STEPS, type TourStep } from '../onboarding/tourSteps';
import { useAuth } from '../context/AuthContext';
import { useTheme, useThemeSetting } from '../theme';
import { useAuthStackInitialRoute } from './useAuthStackInitialRoute';
import { useAppPathDeepLink } from './useAppPathDeepLink';
import { useEmailChangeDeepLink } from './useEmailChangeDeepLink';
import { useEmailVerificationDeepLink } from './useEmailVerificationDeepLink';
import { useReferralDeepLink } from './useReferralDeepLink';
import { usePushNotificationNavigation } from './usePushNotificationNavigation';
import { useNavigationStatePersistence } from './useNavigationStatePersistence';
import type { AuthStackParamList, RootParamList } from './types';

const AuthStack = createNativeStackNavigator<AuthStackParamList>();
const AppStack = createNativeStackNavigator();

/**
 * Phase 4's first deep-link consumer: EmailChangeService emails a confirmation link to the new
 * address, and tapping it needs to land on VerifyEmailChangeScreen with sessionId/token intact.
 *
 * Emailed links are plain https URLs ("https://app.fynora.net/email-change-verify?..."), so on a
 * phone with the app installed the OS only opens the app instead of the browser when the app has
 * claimed that domain: `ios.associatedDomains` + `android.intentFilters` (autoVerify) in
 * app.config.ts, and the apple-app-site-association / assetlinks.json files the web app hosts
 * under /.well-known/. The paths claimed are listed once, in appLinks.config.js; a test
 * (lib/appLinks.seam.test.ts) keeps that list, app.config.ts and the hosted files in agreement. The same links still work in a browser
 * for anyone without the app, since the OS only diverts them when it can verify the association.
 *
 * Actual routing is imperative (useEmailChangeDeepLink / useAppPathDeepLink below), not React
 * Navigation's own declarative `linking.config` -- see the former's doc comment for why: these
 * screens only exist inside the AppTabs tree, but the link can arrive while any of RootNavigator's
 * three mutually-exclusive trees is mounted, including while signed out.
 */
const linkingPrefixes = ['finora://'];

/**
 * The mobile counterpart of the web app's ProtectedRoute, expressed the way React Navigation
 * intends: which stack exists at all is derived from auth state, rather than every screen
 * checking a guard and redirecting. A signed-out user has no route to the app stack to navigate
 * to -- it isn't mounted -- so there's no "flash of authenticated UI" to defend against, and no
 * imperative navigate() call after login/register/verify. Flipping state is the navigation.
 *
 * As on the web, this is UX only. The backend is the real enforcement: PhoneVerificationFilter
 * rejects an unverified account's requests with 403 PHONE_VERIFICATION_REQUIRED regardless of
 * what the client renders.
 */
export function RootNavigator() {
  const { bootstrapping, token, phoneVerified, onboardingCompleted } = useAuth();
  const { step: onboardingStep, setStep: setOnboardingStep } = useOnboardingStep();
  const authInitialRoute = useAuthStackInitialRoute(token);
  const c = useTheme();
  const { resolved } = useThemeSetting();
  const navigationRef = useNavigationContainerRef<RootParamList>();
  // AppTabs is actually the mounted tree -- token alone isn't enough, since a
  // signed-in-but-unverified account gets the single-screen VerifyPhone AppStack instead, which
  // has no route to More.VerifyEmailChange either, and a verified-but-not-yet-onboarded account
  // gets OnboardingNavigator instead (see the render logic below) UNLESS the onboarding step is
  // specifically 'tour' -- that step renders the REAL AppTabs (plus TourOverlay on top), not a
  // substitute, so it counts as active too. Shared below by the deep-link hook (its own "ready"
  // gate) and the nav-state-persistence hook (its own "which tree does this state belong to"
  // gate) -- both need exactly this condition, not a slightly different one.
  const isAppTabsActive = token !== null && phoneVerified && (onboardingCompleted || onboardingStep === 'tour');
  const { onNavigationReady: onEmailChangeReady } = useEmailChangeDeepLink(navigationRef, isAppTabsActive, token !== null);
  // AuthStack -- and Register within it -- is mounted exactly when signed out; see this hook's
  // own doc comment for why that single condition is enough, unlike isAppTabsActive above.
  const { onNavigationReady: onReferralReady } = useReferralDeepLink(navigationRef, token === null);
  // Same gate as the email-change link (isAppTabsActive/token !== null), not the referral link's
  // stricter one -- see usePushNotificationNavigation's own doc comment on why a tapped push
  // should survive a transient ready dip the same way an email-change link does.
  const { onNavigationReady: onPushNotificationReady } =
    usePushNotificationNavigation(navigationRef, isAppTabsActive, token !== null);
  const { onNavigationReady: onAppPathReady } = useAppPathDeepLink(navigationRef, isAppTabsActive, token !== null);
  // Needs no navigator or auth state -- see the hook's own doc comment.
  useEmailVerificationDeepLink();
  const navPersistence = useNavigationStatePersistence(bootstrapping, isAppTabsActive);

  function onNavigationReady() {
    onEmailChangeReady();
    onReferralReady();
    onPushNotificationReady();
    onAppPathReady();
  }

  function navigateToTab(tab: TourStep['tab']) {
    if (!navigationRef.current || !navigationRef.isReady()) return;
    if (tab === 'More') {
      navigationRef.navigate('More', { screen: 'MoreHome' });
    } else {
      navigationRef.navigate(tab);
    }
  }

  // Neither the tour finishing nor being skipped completes onboarding by itself -- only
  // SuccessScreen's own buttons do that (OnboardingNavigator.finishOnboarding). This just
  // advances the shared step to 'success', which is what makes OnboardingNavigator render it --
  // same sequencing as web's ProtectedRoute/TourOverlay wiring.
  function onTourEnd() {
    setOnboardingStep('success');
  }

  // Session restore reads SecureStore asynchronously (see AuthContext). Rendering anything
  // route-dependent before it resolves would show Login to an already-signed-in user for a frame.
  // Also waits on navPersistence: reading its one AsyncStorage key is comparably fast, and folding
  // it into the same spinner avoids a second, separate loading flash right after this one clears.
  if (bootstrapping || !navPersistence.isReady) {
    return (
      <View style={[styles.splash, { backgroundColor: c.bg }]}>
        <ActivityIndicator size="large" color={c.primary} />
      </View>
    );
  }

  // Stock DefaultTheme/DarkTheme's own `colors.primary` is React Navigation's iOS-blue default,
  // independent of (and previously left to clash with) the app's own palette -- spread the stock
  // theme (it already carries the `fonts` object v7 requires) and override just the colors that
  // matter, so anything React Navigation draws unprompted (native header default, back-gesture
  // tint) matches the rest of the app instead of standing out as a different product. Based on
  // `resolved` from useThemeSetting() rather than the raw OS scheme, so this can't disagree with
  // the theme every other screen is already painted in when the user has picked a manual
  // light/dark override that differs from the system setting.
  const base = resolved === 'dark' ? DarkTheme : DefaultTheme;
  const navTheme = {
    ...base,
    dark: resolved === 'dark',
    colors: {
      ...base.colors,
      primary: c.primary,
      background: c.bg,
      card: c.card,
      text: c.ink,
      border: c.border,
    },
  };

  return (
    <NavigationContainer
      ref={navigationRef}
      theme={navTheme}
      linking={{ prefixes: linkingPrefixes }}
      onReady={onNavigationReady}
      // Both undefined whenever isAppTabsActive is false: navPersistence never populates
      // initialState outside that condition (see the hook's own doc comment), and onStateChange
      // itself no-ops via the same activeRef check. Passing them unconditionally rather than only
      // inside the AppTabs branch below because NavigationContainer is the one component instance
      // wrapping all three conditionally-rendered trees -- it can't take different props per
      // child.
      initialState={navPersistence.initialState}
      onStateChange={navPersistence.onStateChange}
    >
      {/* Always mounted, not just around the tour branch below: AppTabs (and MoreScreen inside
          it) unconditionally call useRegisterTourTarget now, so the ordinary post-onboarding
          <AppTabs /> branch needs a provider in scope too, not only the tour's. Free for every
          other branch (Auth/VerifyPhone/OnboardingNavigator never call useRegisterTourTarget or
          useTourTarget), so wrapping it around everything here is simpler and safer than
          threading a second copy into just the branches that need it. */}
      <TourTargetProvider>
      {token === null ? (
        // initialRouteName -- not just AuthEntry listed first -- because which screen this stack
        // should open on differs by how it got here: a cold, never-signed-in launch starts on
        // AuthEntry (Phase 3B fronts Login/Register the same way web's /auth does, without
        // removing direct access to either); a sign-out from a previously-authenticated session
        // starts on Login directly, per useAuthStackInitialRoute's own doc comment.
        <AuthStack.Navigator screenOptions={{ headerShown: false }} initialRouteName={authInitialRoute}>
          <AuthStack.Screen name="AuthEntry" component={AuthEntryScreen} />
          <AuthStack.Screen name="Login" component={LoginScreen} />
          <AuthStack.Screen name="Register" component={RegisterScreen} />
          <AuthStack.Screen name="ForgotPassword" component={ForgotPasswordScreen} />
        </AuthStack.Navigator>
      ) : !phoneVerified ? (
        // Single screen by design: an unverified account can't reach any other protected
        // endpoint, so there's nowhere else to go until this completes. VerifyPhoneScreen offers
        // sign-out as the way back.
        <AppStack.Navigator screenOptions={{ headerShown: false }}>
          <AppStack.Screen name="VerifyPhone" component={VerifyPhoneScreen} />
        </AppStack.Navigator>
      ) : !onboardingCompleted ? (
        onboardingStep === 'tour' ? (
          // Real AppTabs, not a substitute -- see isAppTabsActive's own comment above and the
          // design spec's §7 addendum. Both read/write the same TourTargetProvider instance
          // wrapped around this whole tree (see its own comment above).
          <>
            <AppTabs />
            <TourOverlay steps={TOUR_STEPS} navigateToTab={navigateToTab} onFinish={onTourEnd} onSkip={onTourEnd} />
          </>
        ) : (
          <OnboardingNavigator />
        )
      ) : (
        <AppTabs />
      )}
      </TourTargetProvider>
    </NavigationContainer>
  );
}

const styles = StyleSheet.create({
  splash: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    padding: 24,
  },
});
