package com.adobe.dmrefs.assets.core.util;

import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.regex.Pattern;

/** Validation and hashing helpers. All repository node names derive from validated or hashed input. */
public final class Paths {

    public static final String URN_PREFIX = "urn:aaid:aem:";

    public static final Pattern UUID =
            Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    public static final Pattern VANITY = Pattern.compile("^[A-Za-z0-9._~-]{1,200}$");
    public static final Pattern INSTANCE_ID = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$");
    private static final Pattern CONTENT_PATH = Pattern.compile("^/content(/[^/\\p{Cntrl}\\[\\]|*]+)+$");

    private Paths() {
    }

    public static String sha256(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public static boolean isValidContentPath(String path) {
        return path != null && path.length() <= 1024 && CONTENT_PATH.matcher(path).matches()
                && !path.contains("/../") && !path.endsWith("/..") && !path.contains("/./");
    }

    public static boolean isValidDamPath(String path) {
        return isValidContentPath(path) && path.startsWith("/content/dam/");
    }

    private static final Pattern AUTHOR_HOST = Pattern.compile("^[A-Za-z0-9.-]+$");
    private static final Pattern AUTHOR_PATH = Pattern.compile("^(/[A-Za-z0-9._~-]+)*$");

    /**
     * @return {@code https://host[:port][/path]} without a trailing slash, or null if the value is not an https URL
     *         without credentials, query or fragment. Must match the Sites tracker's check.
     */
    public static String normalizeAuthorUrl(String url) {
        if (url == null || url.trim().isEmpty() || url.length() > 200) {
            return null;
        }
        try {
            URI u = new URI(url.trim());
            String path = u.getRawPath() == null ? "" : u.getRawPath().replaceAll("/+$", "");
            if (!"https".equalsIgnoreCase(u.getScheme()) || u.getHost() == null
                    || !AUTHOR_HOST.matcher(u.getHost()).matches() || u.getUserInfo() != null
                    || u.getRawQuery() != null || u.getRawFragment() != null
                    || !AUTHOR_PATH.matcher(path).matches()) {
                return null;
            }
            return "https://" + u.getHost().toLowerCase(Locale.ROOT)
                    + (u.getPort() == -1 ? "" : ":" + u.getPort()) + path;
        } catch (URISyntaxException e) {
            return null;
        }
    }

    /** Page URL on the given author, or null if either input is invalid. */
    public static String pageUrl(String authorUrl, String pagePath) {
        String base = normalizeAuthorUrl(authorUrl);
        if (base == null || !isValidContentPath(pagePath)) {
            return null;
        }
        try {
            return base + new URI(null, null, pagePath, null).getRawPath() + ".html";
        } catch (URISyntaxException e) {
            return null;
        }
    }

    /** @return lower-case bare UUID from {@code urn:aaid:aem:<uuid>}, or null if malformed. */
    public static String uuidFromUrn(String urn) {
        if (urn == null || urn.length() != URN_PREFIX.length() + 36) {
            return null;
        }
        String lower = urn.toLowerCase(Locale.ROOT);
        if (!lower.startsWith(URN_PREFIX)) {
            return null;
        }
        String uuid = lower.substring(URN_PREFIX.length());
        return UUID.matcher(uuid).matches() ? uuid : null;
    }
}
