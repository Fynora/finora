package com.finora.activation;

/**
 * The bucket a signup's source falls in on the activation page's "by source" table. The
 * declaration order is the row order there (UTM rows first, one per tag value). See
 * {@link SignupSources#classify} for the rules.
 */
public enum SignupSourceKind {
    /** The landing link carried a {@code utm_source}; the row is per value. */
    UTM,
    /** Google search: google.&lt;country&gt;, www.google.&lt;country&gt;, or the Google app. */
    GOOGLE,
    /** Bing: bing.com and its subdomains. */
    BING,
    /** Another search engine (DuckDuckGo, Yahoo search, Yandex, Baidu, Ecosia, Brave, ...). */
    OTHER_SEARCH,
    /** A social or messaging site (Facebook, Instagram, X, LinkedIn, YouTube, Reddit, WhatsApp, ...). */
    SOCIAL,
    /** Any other site that linked here. */
    OTHER_SITE,
    /** Nothing in the attribution row says where from, but the account redeemed a friend's code
     *  ({@code referrals}, since V101 -- so known for accounts older than V286 too). */
    REFERRAL,
    /** Nothing recorded for an account made after tracking began: typed in, a bookmark, or the
     *  mobile apps, which send no attribution. */
    DIRECT,
    /** Nothing recorded for an account made before tracking began (V286). Not direct: nobody was
     *  recording then, so where they came from is not known. */
    UNKNOWN_BEFORE_TRACKING
}
