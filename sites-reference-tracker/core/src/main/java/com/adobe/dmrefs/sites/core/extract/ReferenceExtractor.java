package com.adobe.dmrefs.sites.core.extract;

import com.adobe.dmrefs.sites.core.model.Ref;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pulls DM asset references out of arbitrary string property values (component props, RTE markup, JSON, URLs).
 * Pure Java so it can be unit tested without an AEM runtime.
 *
 * <p>Every configured pattern must declare exactly one of the named groups {@code uuid} or {@code vanity}.</p>
 */
public final class ReferenceExtractor {

    /** Upper bound on characters scanned per property value; protects against huge text blobs. */
    public static final int MAX_SCAN_CHARS = 200_000;

    static final String URN_PREFIX = "urn:aaid:aem:";

    private static final Pattern UUID_SHAPE =
            Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    private static final Pattern VANITY_SHAPE = Pattern.compile("^[A-Za-z0-9._~-]{1,200}$");

    private final List<Pattern> uuidPatterns;
    private final List<Pattern> vanityPatterns;

    public ReferenceExtractor(String[] uuidPatterns, String[] vanityPatterns) {
        this.uuidPatterns = compile(uuidPatterns, "uuid");
        this.vanityPatterns = compile(vanityPatterns, "vanity");
    }

    private static List<Pattern> compile(String[] patterns, String group) {
        if (patterns == null) {
            return Collections.emptyList();
        }
        List<Pattern> out = new ArrayList<>();
        for (String p : patterns) {
            if (p == null || p.trim().isEmpty()) {
                continue;
            }
            if (!p.contains("(?<" + group + ">")) {
                throw new IllegalArgumentException("Pattern must declare named group '" + group + "': " + p);
            }
            out.add(Pattern.compile(p));
        }
        return Collections.unmodifiableList(out);
    }

    /** Adds every reference found in {@code text} to {@code out}, also matching its unescaped / URL-decoded form. */
    public void extract(String text, Set<Ref> out) {
        if (text == null || text.isEmpty()) {
            return;
        }
        String s = text.length() > MAX_SCAN_CHARS ? text.substring(0, MAX_SCAN_CHARS) : text;
        match(s, out);
        String n = normalise(s);
        if (!n.equals(s)) {
            match(n, out);
        }
    }

    /** Turns JSON-escaped slashes and percent-encoding (e.g. urn%3Aaaid%3Aaem%3A...) back into plain text. */
    static String normalise(String s) {
        String r = s.indexOf("\\/") >= 0 ? s.replace("\\/", "/") : s;
        if (r.indexOf('%') >= 0) {
            try {
                // A literal '+' must stay a '+', not become a space.
                r = URLDecoder.decode(r.replace("+", "%2B"), "UTF-8");
            } catch (UnsupportedEncodingException | IllegalArgumentException e) {
                // keep the text as it was
            }
        }
        return r;
    }

    private void match(String s, Set<Ref> out) {
        for (Pattern p : uuidPatterns) {
            Matcher m = p.matcher(s);
            while (m.find()) {
                String uuid = m.group("uuid");
                if (uuid != null) {
                    String norm = uuid.toLowerCase(Locale.ROOT);
                    if (UUID_SHAPE.matcher(norm).matches()) {
                        out.add(new Ref(Ref.Type.UUID, URN_PREFIX + norm));
                    }
                }
            }
        }
        for (Pattern p : vanityPatterns) {
            Matcher m = p.matcher(s);
            while (m.find()) {
                String v = m.group("vanity");
                if (v != null && VANITY_SHAPE.matcher(v).matches()) {
                    out.add(new Ref(Ref.Type.VANITY, v));
                }
            }
        }
    }
}
