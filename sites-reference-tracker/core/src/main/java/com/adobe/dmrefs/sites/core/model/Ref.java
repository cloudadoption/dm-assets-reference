package com.adobe.dmrefs.sites.core.model;

import java.util.Locale;
import java.util.Objects;

/**
 * A single Dynamic Media asset reference, exactly as it appears in page content.
 * UUID refs are normalised to {@code urn:aaid:aem:<lower-case uuid>}; vanity refs are kept verbatim.
 */
public final class Ref implements Comparable<Ref> {

    public enum Type { UUID, VANITY }

    private static final char SEP = '|';

    private final Type type;
    private final String value;

    public Ref(Type type, String value) {
        this.type = Objects.requireNonNull(type, "type");
        this.value = Objects.requireNonNull(value, "value");
    }

    public Type getType() {
        return type;
    }

    public String getValue() {
        return value;
    }

    /** Stable text form, used for fingerprints: {@code uuid|urn:aaid:aem:...} or {@code vanity|summer-hero}. */
    public String encode() {
        return type.name().toLowerCase(Locale.ROOT) + SEP + value;
    }

    @Override
    public int compareTo(Ref o) {
        int c = type.compareTo(o.type);
        return c != 0 ? c : value.compareTo(o.value);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Ref)) {
            return false;
        }
        Ref r = (Ref) o;
        return type == r.type && value.equals(r.value);
    }

    @Override
    public int hashCode() {
        return 31 * type.hashCode() + value.hashCode();
    }

    @Override
    public String toString() {
        return encode();
    }
}
