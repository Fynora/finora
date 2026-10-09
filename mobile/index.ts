import { registerRootComponent } from 'expo';

import App from './App';
import { registerSystemNotificationEvents } from './src/lib/systemNotification';

// Outside any component, at start-up, as the notification library requires (see the function's own doc).
void registerSystemNotificationEvents();

// registerRootComponent calls AppRegistry.registerComponent('main', () => App);
// It also ensures that whether you load the app in Expo Go or in a native build,
// the environment is set up appropriately
registerRootComponent(App);
