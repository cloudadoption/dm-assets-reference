package com.adobe.dmrefs.sites.core.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.regex.Pattern;

/** Small helpers for safe node naming and path validation. */
public final class Hashes {

    /** Content paths we are willing to track. Rejects "..", control chars and anything outside /content. */
    private static final Pattern CONTENT_PATH =
            Pattern.compile("^/content(/[^/\\p{Cntrl}\\[\\]|*]+)+$");

    private Hashes() {
    }

    /** Hex SHA-256, used for node names so arbitrary page paths never become JCR path segments. */
    public static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
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
        return path != null
                && path.length() <= 1024
                && CONTENT_PATH.matcher(path).matches()
                && !path.contains("/../")
                && !path.endsWith("/..")
                && !path.contains("/./");
    }

    public static boolean isUnderAnyRoot(String path, String[] roots) {
        if (path == null || roots == null) {
            return false;
        }
        for (String r : roots) {
            if (r != null && (path.equals(r) || path.startsWith(r.endsWith("/") ? r : r + "/"))) {
                return true;
            }
        }
        return false;
    }

    private static final Pattern AUTHOR_HOST = Pattern.compile("^[A-Za-z0-9.-]+$");
    private static final Pattern AUTHOR_PATH = Pattern.compile("^(/[A-Za-z0-9._~-]+)*$");

    /**
     * @return {@code https://host[:port][/path]} without a trailing slash, or null if the value is not an https URL
     *         without credentials, query or fragment. Must match the receiver's check.
     */
    public static String normalizeAuthorUrl(String url) {
        if (url == null || url.trim().isEmpty() || url.length() > 200) {
            return null;
        }
        try {
            java.net.URI u = new java.net.URI(url.trim());
            String path = u.getRawPath() == null ? "" : u.getRawPath().replaceAll("/+$", "");
            if (!"https".equalsIgnoreCase(u.getScheme()) || u.getHost() == null
                    || !AUTHOR_HOST.matcher(u.getHost()).matches() || u.getUserInfo() != null
                    || u.getRawQuery() != null || u.getRawFragment() != null
                    || !AUTHOR_PATH.matcher(path).matches()) {
                return null;
            }
            return "https://" + u.getHost().toLowerCase(java.util.Locale.ROOT)
                    + (u.getPort() == -1 ? "" : ":" + u.getPort()) + path;
        } catch (java.net.URISyntaxException e) {
            return null;
        }
    }
}
