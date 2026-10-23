import { act, render } from '@testing-library/react-native';
import { Linking, Text } from 'react-native';
import {
  BaseNavigationContainer, createNavigatorFactory, useNavigationBuilder, useNavigationContainerRef,
} from '@react-navigation/core';
import { StackRouter } from '@react-navigation/routers';
import { useAppPathDeepLink } from './useAppPathDeepLink';
import type { RootParamList } from './types';

// Same stand-in as useResetPasswordDeepLink.integration.test.tsx -- see the comment there.
jest.mock('decode-uri-component', () => (value: string) => decodeURIComponent(value), { virtual: true });

// The unit tests fake the navigation ref, so they cannot see what the user is left with once the
// link lands: which screens are in the More stack, and so whether there is anything to go back to.
// This drives the real container and routers. Native-stack cannot load under Jest (see
// RootNavigator.test.tsx), so a minimal StackRouter navigator stands in for it, and a second one for
// the tab bar: what matters here is the nested router's behaviour, which is the same.

function TestNavigator({ children }: { children: React.ReactNode }) {
  const { state, descriptors, NavigationContent } = useNavigationBuilder(StackRouter, { children });
  const focused = state.routes[state.index];
  return <NavigationContent>{descriptors[focused.key].render()}</NavigationContent>;
}
const createTestNavigator = createNavigatorFactory(TestNavigator);
const Root = createTestNavigator();
const MoreStack = createTestNavigator();

const Blank = () => <Text>screen</Text>;

// Mounted lazily, like the More tab: nothing of it exists until the first navigation into it.
function MoreNavigator() {
  return (
    <MoreStack.Navigator initialRouteName="MoreHome">
      <MoreStack.Screen name="MoreHome" component={Blank} />
      <MoreStack.Screen name="SupportTickets" component={Blank} />
      <MoreStack.Screen name="SupportTicketDetail" component={Blank} />
    </MoreStack.Navigator>
  );
}

let navigationRef: ReturnType<typeof useNavigationContainerRef<RootParamList>>;

function Harness() {
  navigationRef = useNavigationContainerRef<RootParamList>();
  const { onNavigationReady } = useAppPathDeepLink(navigationRef, true, true);
  return (
    <BaseNavigationContainer ref={navigationRef} onReady={onNavigationReady}>
      <Root.Navigator>
        <Root.Screen name="Home" component={Blank} />
        <Root.Screen name="More" component={MoreNavigator} />
      </Root.Navigator>
    </BaseNavigationContainer>
  );
}

const getInitialURLSpy = jest.spyOn(Linking, 'getInitialURL');
const addEventListenerSpy = jest.spyOn(Linking, 'addEventListener');
let urlListener: ((event: { url: string }) => void) | null = null;

const TICKET_ID = '3f0c9a52-7e1b-4d6a-9c34-2b8e5a1f7d90';

/** The route names in the More stack, bottom to top, or null if that stack never mounted. */
function moreStack() {
  return navigationRef.getRootState()?.routes.find((r) => r.name === 'More')?.state?.routes;
}

function moreStackRoutes(): string[] | null {
  return moreStack()?.map((r) => r.name) ?? null;
}

describe('a support link against the real React Navigation container', () => {
  beforeEach(() => {
    urlListener = null;
    getInitialURLSpy.mockReset().mockResolvedValue(null);
    addEventListenerSpy.mockReset().mockImplementation((_e, listener) => {
      urlListener = listener as (event: { url: string }) => void;
      return { remove: jest.fn() } as never;
    });
  });

  // The More tab has never been opened when the email link is tapped, so its stack is built from the
  // link alone. Left to its default the target is the ONLY screen: no header back button, nothing to
  // go back to. The stack must start from More's home underneath it.
  it('opens the ticket with More home beneath it, so there is somewhere to go back to', async () => {
    render(<Harness />);
    await act(async () => {});

    await act(async () => { urlListener?.({ url: `https://app.fynora.net/app/support/${TICKET_ID}` }); });

    expect(moreStackRoutes()).toEqual(['MoreHome', 'SupportTicketDetail']);
    expect(moreStack()?.at(-1)?.params).toEqual({ ticketId: TICKET_ID });
  });

  it('opens My Tickets with More home beneath it too', async () => {
    render(<Harness />);
    await act(async () => {});

    await act(async () => { urlListener?.({ url: 'https://app.fynora.net/app/support' }); });

    expect(moreStackRoutes()).toEqual(['MoreHome', 'SupportTickets']);
  });
});
