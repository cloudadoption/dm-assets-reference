package com.adobe.dmrefs.sites.core.util;

import java.net.URI;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure helpers for the IMS client-credentials exchange. */
public final class Ims {

    /** Fixed on purpose: not derived from configuration, so a bad config cannot send the secret elsewhere. */
    private static final Set<String> HOSTS = new HashSet<>(Arrays.asList(
            "ims-na1.adobelogin.com", "ims-eu1.adobelogin.com", "ims-na1-stg1.adobelogin.com"));

    private static final Pattern ACCESS_TOKEN = Pattern.compile("\"access_token\"\\s*:\\s*\"([^\"\\\\]{1,8192})\"");
    private static final Pattern EXPIRES_IN = Pattern.compile("\"expires_in\"\\s*:\\s*(\\d{1,12})");

    private Ims() {
    }

    /** True only for https on a known IMS host, without credentials, on the token path. */
    public static boolean isAllowedTokenUrl(String url) {
        if (url == null) {
            return false;
        }
        try {
            URI u = new URI(url.trim());
            return "https".equalsIgnoreCase(u.getScheme()) && u.getHost() != null
                    && HOSTS.contains(u.getHost().toLowerCase(Locale.ROOT))
                    && (u.getPort() == -1 || u.getPort() == 443)
                    && u.getUserInfo() == null && u.getRawQuery() == null && u.getRawFragment() == null
                    && u.getRawPath() != null && u.getRawPath().startsWith("/ims/token");
        } catch (java.net.URISyntaxException e) {
            return false;
        }
    }

    /** @return the token and lifetime, or null if the body has no access token. */
    public static Caches.TokenCache.Fetched parseTokenResponse(String body) {
        if (body == null) {
            return null;
        }
        Matcher t = ACCESS_TOKEN.matcher(body);
        if (!t.find()) {
            return null;
        }
        Matcher e = EXPIRES_IN.matcher(body);
        long seconds = e.find() ? Long.parseLong(e.group(1)) : 3600L;
        return new Caches.TokenCache.Fetched(t.group(1), seconds);
    }
}
