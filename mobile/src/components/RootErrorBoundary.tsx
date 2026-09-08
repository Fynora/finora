import { Component, type ErrorInfo, type ReactNode } from 'react';
import { StyleSheet, Text, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { Button } from './Button';
import { reportHandledError } from '../lib/monitoring';
import { radius, spacing, useTheme } from '../theme';

interface Props {
  children: ReactNode;
}

interface State {
  hasError: boolean;
}

/**
 * Mobile counterpart to frontend/src/components/ErrorBoundary.tsx -- catches a render error
 * anywhere below it and shows a recovery panel instead of the app going down with no fallback UI
 * at all. Confirmed there was none anywhere in this tree before this file: App.tsx's own
 * `withMonitoring` is `Sentry.wrap`, which (per @sentry/react-native's sdk.js) only adds a
 * TouchEventBoundary/Profiler/FeedbackFormProvider around the root -- no componentDidCatch, no
 * fallback, so a render-time throw in any screen took out the whole app.
 *
 * Wrapped around RootNavigator itself in App.tsx, not scoped to individual screens the way web
 * scopes its boundary to route content only: mobile has no persistent chrome (sidebar/top nav)
 * living outside the navigator worth preserving on a crash, so one boundary here already covers
 * every screen the navigator can render -- auth stack, onboarding, and the app tabs alike.
 *
 * No stale-chunk special case (compare web's componentDidCatch): Metro bundles the whole app into
 * one JS bundle with no lazy-loaded, separately-fetched route chunks that can go stale between app
 * opens, so that specific failure mode doesn't exist on mobile.
 */
export class RootErrorBoundary extends Component<Props, State> {
  state: State = { hasError: false };

  static getDerivedStateFromError(): State {
    return { hasError: true };
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    // Only the component stack is attached, never the error message as `extra` -- see
    // reportHandledError's own comment on why a caught value's message can quote user data.
    reportHandledError(error, 'root-navigator');
    // Kept so the error is still visible during local development, where no DSN is configured and
    // reportHandledError is a no-op.
    console.error('Render error below RootNavigator:', error, info.componentStack);
  }

  private reset = () => {
    this.setState({ hasError: false });
  };

  render() {
    if (!this.state.hasError) return this.props.children;
    return <ErrorFallback onReset={this.reset} />;
  }
}

function ErrorFallback({ onReset }: { onReset: () => void }) {
  const c = useTheme();
  const insets = useSafeAreaInsets();
  return (
    <View style={[styles.flex, { backgroundColor: c.bg, paddingTop: insets.top, paddingBottom: insets.bottom }]}>
      <View style={styles.center}>
        <View style={[styles.card, { backgroundColor: c.card, borderColor: c.border }]} accessibilityRole="alert">
          <Text style={[styles.title, { color: c.ink }]}>This screen didn&apos;t load correctly</Text>
          <Text style={[styles.body, { color: c.muted }]}>
            Nothing has been lost — your accounts and transactions are unaffected. Try again.
          </Text>
          <View style={styles.action}>
            <Button label="Try again" onPress={onReset} />
          </View>
        </View>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  flex: { flex: 1 },
  center: { flex: 1, alignItems: 'center', justifyContent: 'center', padding: spacing.lg },
  card: {
    width: '100%', maxWidth: 360, borderWidth: 1, borderRadius: radius.lg,
    padding: spacing.lg, alignItems: 'center',
  },
  title: { fontSize: 16, fontWeight: '700', textAlign: 'center' },
  body: { fontSize: 13, textAlign: 'center', marginTop: spacing.xs },
  action: { marginTop: spacing.md, alignSelf: 'stretch' },
});
