import { Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';
import { AppAlert } from '../lib/appAlert';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import Ionicons from '@expo/vector-icons/Ionicons';
import type { NativeStackScreenProps } from '@react-navigation/native-stack';
import { Card } from '../components/Card';
import { MenuGroup, MenuRow, type MenuIcon } from '../components/MenuGroup';
import { useAuth } from '../context/AuthContext';
import { initials } from '../lib/format';
import { spacing, useTheme } from '../theme';
import { useRegisterTourTarget } from '../onboarding/TourTargetRegistry';
import { trackNavigation } from '../lib/trackNavigation';
import type { MoreStackParamList } from '../navigation/types';
import { GlassScreen } from '../components/GlassScreen';

type Props = NativeStackScreenProps<MoreStackParamList, 'MoreHome'>;

/**
 * Grouped by what the destination is about, and within a group ordered roughly by how often it's
 * opened: the money you hold and the plans against it, then the reporting surfaces, then where the
 * data came from, then the plan you're on. Typed against the stack's own param list, so deleting or
 * renaming a route breaks this at compile time rather than at the tap.
 */
// A literal union of exactly the routes below, not `keyof Omit<MoreStackParamList, ...>` --
// that wider type used to work, but as of the Settings redesign (8 new param-list entries)
// TypeScript's overload resolution for navigation.navigate() below stops matching once the
// union of possible route names gets large enough (a known React Navigation/TS limitation, not
// a logic error). None of MENU_GROUPS' entries are Settings sub-routes anyway -- Settings still
// has its own single "Settings" entry, unchanged -- so the precise type was always this narrower
// list; the wide `keyof Omit<...>` was looser than the data ever needed.
//
// 'SupportTicketDetail' and 'MoreHome' were never members of this narrower type in the first
// place (their params make them unreachable from a zero-argument navigate() call the way every
// other entry here is) -- Support has its own entry point in Settings instead (see
// SettingsScreen's "Help & Support" section), not this generic menu.
type MenuRoute =
  | 'Accounts' | 'Investments' | 'Budgets' | 'Goals' | 'Reports' | 'AdvancedReports' | 'Fyn'
  | 'CategoryReview' | 'Statements' | 'FinancialMemory' | 'Subscription' | 'Referrals' | 'Settings';

// `id` is the shared taxonomy id (src/navigation/taxonomy.ts), so a destination reports the same
// name here as it does from the web sidebar. Stored rather than derived from the route: the route
// is a rendering detail that can change without the destination changing.
//
// Settings sits alone in its own group on purpose: it is the one row that is about the app rather
// than about your money, and folding it into "Plan" would hide it under a label that isn't it.
const MENU_GROUPS: { label: string; items: { id: string; label: string; route: MenuRoute; icon: MenuIcon }[] }[] = [
  {
    label: 'Money',
    items: [
      { id: 'accounts', label: 'Accounts', route: 'Accounts', icon: 'wallet-outline' },
      { id: 'investments', label: 'Investments', route: 'Investments', icon: 'trending-up-outline' },
      { id: 'budgets', label: 'Budgets', route: 'Budgets', icon: 'pie-chart-outline' },
      { id: 'goals', label: 'Goals', route: 'Goals', icon: 'flag-outline' },
    ],
  },
  {
    label: 'Insights',
    items: [
      { id: 'reports', label: 'Reports', route: 'Reports', icon: 'bar-chart-outline' },
      { id: 'advanced-reports', label: 'Advanced Reports', route: 'AdvancedReports', icon: 'analytics-outline' },
      { id: 'ask-fyn', label: 'Ask Fyn', route: 'Fyn', icon: 'chatbubbles-outline' },
      { id: 'financial-memory', label: 'Financial Memory', route: 'FinancialMemory', icon: 'bulb-outline' },
    ],
  },
  {
    label: 'Statements',
    items: [
      { id: 'statement-history', label: 'Statement History', route: 'Statements', icon: 'document-text-outline' },
      { id: 'review-categories', label: 'Review Categories', route: 'CategoryReview', icon: 'pricetags-outline' },
    ],
  },
  {
    label: 'Plan',
    items: [
      { id: 'subscription', label: 'Subscription', route: 'Subscription', icon: 'card-outline' },
      { id: 'referrals', label: 'Refer & Earn', route: 'Referrals', icon: 'gift-outline' },
    ],
  },
  {
    label: 'App',
    items: [
      { id: 'settings', label: 'Settings', route: 'Settings', icon: 'settings-outline' },
    ],
  },
];

export function MoreScreen({ navigation }: Props) {
  const c = useTheme();
  const insets = useSafeAreaInsets();
  const { email, fullName, logout } = useAuth();
  // Tour target refs (tourSteps.ts) for the 3 rows the mobile tour spotlights on this screen --
  // hooks can't be called inside the MENU_GROUPS.map() below, so these are registered once here
  // and looked up per row by route name. Insights used to be a 4th entry here, until it was
  // promoted to its own top-level tab (see AppTabs.tsx's own registerInsights), swapping with
  // Goals, which moved the other way and is now registered here instead.
  const registerAccounts = useRegisterTourTarget('accounts');
  const registerBudgets = useRegisterTourTarget('budgets');
  const registerGoals = useRegisterTourTarget('goals');
  const registerByRoute: Partial<Record<string, (node: View | null) => void>> = {
    Accounts: registerAccounts,
    Budgets: registerBudgets,
    Goals: registerGoals,
  };

  function confirmSignOut() {
    AppAlert.alert('Sign out?', 'You’ll need to sign in again to access your account.', [
      { text: 'Cancel', style: 'cancel' },
      { text: 'Sign out', style: 'destructive', onPress: logout },
    ]);
  }

  return (
    <GlassScreen style={styles.glassRoot}>
    <ScrollView
      contentContainerStyle={[styles.content, { paddingTop: insets.top + spacing.md }]}
    >
      <Text style={[styles.title, { color: c.ink }]}>More</Text>

      {/* The whole card opens Profile -- tapping your own name and photo to edit them is the
          convention on every phone, and it saves a menu row for the same destination. */}
      <Pressable
        onPress={() => { trackNavigation('profile', 'group'); navigation.navigate('Profile'); }}
        accessibilityRole="button"
        accessibilityLabel={`Profile: ${fullName ?? email ?? 'your account'}`}
        accessibilityHint="Opens your profile"
      >
        <Card style={styles.profileCard}>
          {/* Decorative initial -- the name and email are read out right beside it, so announcing
              a lone "S" first is pure noise. */}
          <View
            style={[styles.avatar, { backgroundColor: c.primary }]}
            accessibilityElementsHidden
            importantForAccessibility="no-hide-descendants"
          >
            <Text style={[styles.avatarText, { color: c.onPrimary }]}>{initials(fullName ?? email)}</Text>
          </View>
          <View style={styles.profileText}>
            <Text style={[styles.name, { color: c.ink }]} numberOfLines={1}>
              {fullName ?? 'Your account'}
            </Text>
            <Text style={[styles.email, { color: c.muted }]} numberOfLines={1}>
              {email}
            </Text>
          </View>
          <Ionicons name="chevron-forward" size={18} color={c.muted} accessibilityElementsHidden importantForAccessibility="no" />
        </Card>
      </Pressable>

      {MENU_GROUPS.map((group) => (
        <MenuGroup key={group.label} label={group.label}>
          {group.items.map(({ id, label, route, icon }) => (
            <MenuRow
              key={route}
              ref={registerByRoute[route]}
              icon={icon}
              label={label}
              accessibilityLabel={label}
              onPress={() => { trackNavigation(id, 'group'); navigation.navigate(route); }}
            />
          ))}
        </MenuGroup>
      ))}

      <Pressable onPress={confirmSignOut} style={styles.signOutRow} hitSlop={12} accessibilityRole="button">
        <Text style={[styles.signOut, { color: c.dangerInk }]}>Sign out</Text>
      </Pressable>
    </ScrollView>
    </GlassScreen>
  );
}

const styles = StyleSheet.create({
  glassRoot: { flex: 1 },
  content: { padding: spacing.md, paddingBottom: spacing.xl },
  title: { fontSize: 22, fontWeight: '700', marginBottom: spacing.md },
  profileCard: { flexDirection: 'row', alignItems: 'center' },
  avatar: {
    width: 44,
    height: 44,
    borderRadius: 22,
    alignItems: 'center',
    justifyContent: 'center',
    marginRight: spacing.sm,
  },
  avatarText: { fontWeight: '700', fontSize: 18 },
  profileText: { flex: 1 },
  name: { fontSize: 15, fontWeight: '600' },
  email: { fontSize: 12, marginTop: 2 },
  signOutRow: { marginTop: spacing.lg, alignItems: 'center' },
  signOut: { fontSize: 14, fontWeight: '600' },
});
