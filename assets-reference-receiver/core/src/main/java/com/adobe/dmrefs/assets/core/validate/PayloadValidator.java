package com.adobe.dmrefs.assets.core.validate;

import com.adobe.dmrefs.assets.core.dto.IngestRequest;
import com.adobe.dmrefs.assets.core.dto.IngestRequest.PageUpdate;
import com.adobe.dmrefs.assets.core.dto.IngestRequest.RefDto;
import com.adobe.dmrefs.assets.core.util.Paths;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Validates an ingest request in full before anything is written (all-or-nothing).
 * Error messages reference field positions only, never echo submitted values.
 */
public final class PayloadValidator {

    public static final int SCHEMA_VERSION = 1;

    private final int maxPages;
    private final int maxRefsPerPage;

    public PayloadValidator(int maxPages, int maxRefsPerPage) {
        this.maxPages = maxPages;
        this.maxRefsPerPage = maxRefsPerPage;
    }

    public enum RefType { UUID, VANITY }

    /** Validated reference. For UUID refs, {@code value} is the bare lower-case UUID. */
    public static final class ValidRef {
        public final RefType type;
        public final String value;

        ValidRef(RefType type, String value) {
            this.type = type;
            this.value = value;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof ValidRef && ((ValidRef) o).type == type && ((ValidRef) o).value.equals(value);
        }

        @Override
        public int hashCode() {
            return type.hashCode() * 31 + value.hashCode();
        }
    }

    public static final class ValidPage {
        public final String pagePath;
        public final long seq;
        public final boolean deleted;
        public final Set<ValidRef> author;
        public final Set<ValidRef> published;

        ValidPage(String pagePath, long seq, boolean deleted, Set<ValidRef> author, Set<ValidRef> published) {
            this.pagePath = pagePath;
            this.seq = seq;
            this.deleted = deleted;
            this.author = Collections.unmodifiableSet(author);
            this.published = Collections.unmodifiableSet(published);
        }
    }

    public static final class Result {
        public final List<String> errors = new ArrayList<>();
        public final List<ValidPage> pages = new ArrayList<>();
        public String instanceId;
        public String authorUrl;

        public boolean ok() {
            return errors.isEmpty();
        }
    }

    public Result validate(IngestRequest req) {
        Result r = new Result();
        if (req == null) {
            r.errors.add("body: required");
            return r;
        }
        if (req.schemaVersion == null || req.schemaVersion != SCHEMA_VERSION) {
            r.errors.add("schemaVersion: must be " + SCHEMA_VERSION);
        }
        if (req.instanceId == null || !Paths.INSTANCE_ID.matcher(req.instanceId).matches()) {
            r.errors.add("instanceId: must match " + Paths.INSTANCE_ID.pattern());
        } else {
            r.instanceId = req.instanceId;
        }
        if (req.authorUrl != null && !req.authorUrl.trim().isEmpty()) {
            r.authorUrl = Paths.normalizeAuthorUrl(req.authorUrl);
            if (r.authorUrl == null) {
                r.errors.add("authorUrl: must be an https URL (host[:port][/path]) without credentials, query or fragment");
            }
        }
        if (req.pages == null || req.pages.isEmpty()) {
            r.errors.add("pages: at least one page required");
            return r;
        }
        if (req.pages.size() > maxPages) {
            r.errors.add("pages: at most " + maxPages + " per request");
            return r;
        }
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < req.pages.size(); i++) {
            ValidPage vp = validatePage(req.pages.get(i), "pages[" + i + "]", r.errors);
            if (vp != null) {
                if (!seen.add(vp.pagePath)) {
                    r.errors.add("pages[" + i + "].pagePath: duplicate in request");
                } else {
                    r.pages.add(vp);
                }
            }
        }
        if (!r.ok()) {
            r.pages.clear();
        }
        return r;
    }

    private ValidPage validatePage(PageUpdate p, String at, List<String> errors) {
        if (p == null) {
            errors.add(at + ": required");
            return null;
        }
        int before = errors.size();
        if (!Paths.isValidContentPath(p.pagePath)) {
            errors.add(at + ".pagePath: must be a valid path under /content");
        }
        if (p.seq == null || p.seq <= 0) {
            errors.add(at + ".seq: must be a positive integer");
        }
        boolean deleted = Boolean.TRUE.equals(p.deleted);
        Set<ValidRef> author = refs(p.author, at + ".author", errors);
        Set<ValidRef> published = refs(p.published, at + ".published", errors);
        if (deleted && (!author.isEmpty() || !published.isEmpty())) {
            errors.add(at + ": deleted pages must not carry references");
        }
        if (errors.size() != before) {
            return null;
        }
        return new ValidPage(p.pagePath, p.seq, deleted, author, published);
    }

    private Set<ValidRef> refs(List<RefDto> in, String at, List<String> errors) {
        Set<ValidRef> out = new LinkedHashSet<>();
        if (in == null) {
            return out;
        }
        if (in.size() > maxRefsPerPage) {
            errors.add(at + ": at most " + maxRefsPerPage + " refs per page");
            return out;
        }
        for (int i = 0; i < in.size(); i++) {
            RefDto d = in.get(i);
            String pos = at + "[" + i + "]";
            if (d == null || d.type == null || d.ref == null) {
                errors.add(pos + ": type and ref required");
                continue;
            }
            if ("uuid".equals(d.type)) {
                String uuid = Paths.uuidFromUrn(d.ref);
                if (uuid == null) {
                    errors.add(pos + ".ref: must be urn:aaid:aem:<uuid>");
                } else {
                    out.add(new ValidRef(RefType.UUID, uuid));
                }
            } else if ("vanity".equals(d.type)) {
                if (!Paths.VANITY.matcher(d.ref).matches()) {
                    errors.add(pos + ".ref: invalid vanity id");
                } else {
                    out.add(new ValidRef(RefType.VANITY, d.ref));
                }
            } else {
                errors.add(pos + ".type: must be 'uuid' or 'vanity'");
            }
        }
        return out;
    }
}
