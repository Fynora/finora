import { Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';
import { AppAlert } from '../lib/appAlert';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import type { BottomTabNavigationProp } from '@react-navigation/bottom-tabs';
import type { NativeStackScreenProps } from '@react-navigation/native-stack';
import { MenuGroup, MenuRow, type MenuIcon } from '../components/MenuGroup';
import { useAuth } from '../context/AuthContext';
import { spacing, useTheme } from '../theme';
import { useRegisterTourTarget } from '../onboarding/TourTargetRegistry';
import { trackNavigation } from '../lib/trackNavigation';
import { NAV_TAXONOMY, type NavGroupId } from '../navigation/taxonomy';
import type { AppTabParamList, MoreStackParamList } from '../navigation/types';
import { GlassScreen } from '../components/GlassScreen';

type Props = NativeStackScreenProps<MoreStackParamList, 'MoreHome'>;

/**
 * The More menu is the shared navigation taxonomy (src/navigation/taxonomy.ts) rendered as a
 * grouped list: the same five groups, in the same order, with the same membership the web sidebar
 * is specified to carry. See docs/superpowers/specs/2026-09-23-shared-nav-taxonomy-design.md.
 *
 * Which destinations exist, their labels, their group and their order all come from the taxonomy.
 * This file adds only what the taxonomy deliberately leaves out -- how mobile reaches each one,
 * and the icon beside it.
 */
const GROUP_ORDER: Exclude<NavGroupId, 'root'>[] = ['money', 'statements', 'planning', 'analysis', 'your-account'];

const GROUP_LABEL: Record<Exclude<NavGroupId, 'root'>, string> = {
  money: 'Money',
  statements: 'Statements',
  planning: 'Planning',
  analysis: 'Analysis',
  'your-account': 'Your Account',
};

// A literal union of exactly the routes below, not `keyof Omit<MoreStackParamList, ...>` --
// that wider type used to work, but as of the Settings redesign (8 new param-list entries)
// TypeScript's overload resolution for navigation.navigate() below stops matching once the
// union of possible route names gets large enough (a known React Navigation/TS limitation, not
// a logic error). Every member takes no params, which is what lets one zero-argument navigate()
// call serve them all; 'SupportTicketDetail' and 'MoreHome' are not reachable that way.
type MenuRoute =
  | 'Accounts' | 'Investments' | 'Budgets' | 'Goals' | 'Reports' | 'AdvancedReports' | 'Fyn'
  | 'CategoryReview' | 'Statements' | 'FinancialMemory' | 'Subscription' | 'Referrals' | 'Settings'
  | 'Profile' | 'SupportTickets';

// The three destinations that are also tabs (Import is the centre button). They stay listed in
// their groups: a shortcut never removes an item from its group, or the two clients' group contents
// drift apart again -- which is the failure the taxonomy exists to prevent.
type PromotedTab = 'Transactions' | 'Import' | 'Insights';

type Destination = { icon: MenuIcon } & ({ screen: MenuRoute } | { tab: PromotedTab });

// Keyed by taxonomy id. Home is absent on purpose: it is the taxonomy's ungrouped root, not a
// member of any group. MoreScreen.test.tsx fails if any other taxonomy destination has no entry
// here, so a destination added to the taxonomy cannot go quietly missing from this menu.
const DESTINATIONS: Record<string, Destination> = {
  accounts: { screen: 'Accounts', icon: 'wallet-outline' },
  transactions: { tab: 'Transactions', icon: 'swap-horizontal-outline' },

  'import-statement': { tab: 'Import', icon: 'cloud-upload-outline' },
  'statement-history': { screen: 'Statements', icon: 'document-text-outline' },
  'review-categories': { screen: 'CategoryReview', icon: 'pricetags-outline' },
  'financial-memory': { screen: 'FinancialMemory', icon: 'bulb-outline' },

  budgets: { screen: 'Budgets', icon: 'pie-chart-outline' },
  goals: { screen: 'Goals', icon: 'flag-outline' },
  investments: { screen: 'Investments', icon: 'trending-up-outline' },

  insights: { tab: 'Insights', icon: 'stats-chart-outline' },
  reports: { screen: 'Reports', icon: 'bar-chart-outline' },
  'advanced-reports': { screen: 'AdvancedReports', icon: 'analytics-outline' },
  'ask-fyn': { screen: 'Fyn', icon: 'chatbubbles-outline' },

  profile: { screen: 'Profile', icon: 'person-circle-outline' },
  subscription: { screen: 'Subscription', icon: 'card-outline' },
  referrals: { screen: 'Referrals', icon: 'gift-outline' },
  settings: { screen: 'Settings', icon: 'settings-outline' },
  // Also reachable from Settings' own Help & Support section, kept as a second way in.
  support: { screen: 'SupportTickets', icon: 'help-buoy-outline' },
};

export function MoreScreen({ navigation }: Props) {
  const c = useTheme();
  const insets = useSafeAreaInsets();
  const { email, fullName, logout } = useAuth();
  // Tour target refs (tourSteps.ts) for the 3 rows the mobile tour spotlights on this screen --
  // hooks can't be called inside the map() below, so these are registered once here and looked up
  // per row by taxonomy id. Insights used to be a 4th entry here, until it was promoted to its own
  // top-level tab (see AppTabs.tsx's own registerInsights), swapping with Goals, which moved the
  // other way and is now registered here instead.
  const registerAccounts = useRegisterTourTarget('accounts');
  const registerBudgets = useRegisterTourTarget('budgets');
  const registerGoals = useRegisterTourTarget('goals');
  const registerById: Partial<Record<string, (node: View | null) => void>> = {
    accounts: registerAccounts,
    budgets: registerBudgets,
    goals: registerGoals,
  };

  // Profile used to be a card above the menu showing who is signed in. It is a row in Your Account
  // now, like its peers, and carries that same name and email under its label so the screen still
  // says whose account this is. One per line, as the card had them: joined on one line, a longer
  // name pushed the email to the next line and left the separator dangling at the end of the first.
  const signedInAs = [fullName, email].filter((part): part is string => Boolean(part));

  function open(id: string, destination: Destination) {
    trackNavigation(id, 'group');
    if ('tab' in destination) {
      // getParent() reaches the tab navigator this stack sits inside, the same way BudgetsScreen
      // and StatementHistoryScreen jump to a tab.
      navigation.getParent<BottomTabNavigationProp<AppTabParamList>>()?.navigate(destination.tab);
    } else {
      navigation.navigate(destination.screen);
    }
  }

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

      {GROUP_ORDER.map((group) => (
        <MenuGroup key={group} label={GROUP_LABEL[group]}>
          {NAV_TAXONOMY.filter((entry) => entry.group === group).map(({ id, label }) => {
            const destination = DESTINATIONS[id];
            if (!destination) return null;
            const who = id === 'profile' && signedInAs.length > 0 ? signedInAs : undefined;
            return (
              <MenuRow
                key={id}
                ref={registerById[id]}
                icon={destination.icon}
                label={label}
                description={who?.join('\n')}
                accessibilityLabel={who ? `${label}: ${who.join(', ')}` : label}
                onPress={() => open(id, destination)}
              />
            );
          })}
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
  // MenuGroup brings its own top margin, so the title carries none below it.
  title: { fontSize: 22, fontWeight: '700' },
  signOutRow: { marginTop: spacing.lg, alignItems: 'center' },
  signOut: { fontSize: 14, fontWeight: '600' },
});
