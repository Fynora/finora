-- "Your account was just signed in to" (SignInAlertNotifier), sent on every sign-in.
--
-- Until now nothing told a customer about a sign-in they did not make. The device list in Settings
-- showed it, but only to someone who went and looked.
--
-- {{device}} and {{browser}} are fixed labels worked out from the request ("iPhone", "Chrome",
-- "Fynora app" for the phone app),
-- {{ip}} is the address the request came from, {{time}} is India time. Nothing the person signing
-- in typed appears in either message.
--
-- The PUSH row carries no address: it shows on a lock screen. It says what to do in a few words
-- because a push cannot hold a link to follow later; the email is where the detail is.
INSERT INTO notification_templates (id, type, channel, title_template, body_template) VALUES
    (gen_random_uuid(), 'NEW_SIGN_IN', 'EMAIL',
     'New sign-in to your Fynora account',
     'Your Fynora account was signed in to on {{time}}. Device: {{device}}. App or browser: {{browser}}. '
     'IP address: {{ip}} (shared networks and VPNs can make an address look unfamiliar). '
     'If this was you, there is nothing to do. If it was not you, change your password now, then '
     'open Settings, Security, and sign out any device you do not recognise.'),
    (gen_random_uuid(), 'NEW_SIGN_IN', 'PUSH',
     'New sign-in to your account',
     'Your Fynora account was just signed in to on {{device}}. Not you? Change your password.');
