package com.finora.invite;

/** What the apps report about the invite-a-friend card. The fixed list: an unknown name is a 400. */
public enum InvitePromptEvent {
    /** The card was shown on the import summary. */
    SHOWN,
    /** Share was tapped (the share sheet opened, or the link was copied). Whether a message was then
     *  sent is never known: the share sheets do not say. */
    SHARE_TAPPED,
    /** "Not now" was tapped. */
    DISMISSED
}
