package com.adobe.dmrefs.assets.core.impl;

import com.adobe.dmrefs.assets.core.dto.IngestRequest;
import com.adobe.dmrefs.assets.core.validate.PayloadValidator;
import com.google.gson.JsonParseException;
import org.apache.jackrabbit.api.security.user.Authorizable;
import org.apache.jackrabbit.api.security.user.Group;
import org.apache.jackrabbit.api.security.user.UserManager;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.PersistenceException;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.api.servlets.SlingAllMethodsServlet;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.RepositoryException;
import javax.servlet.Servlet;
import java.io.BufferedReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * POST /bin/dmrefs/ingest — receives page reference updates from Sites author trackers.
 *
 * <ol>
 *   <li>Caller must be authenticated (IMS bearer token) and a member of the writer group.</li>
 *   <li>Body is size-capped, parsed into allow-listed DTOs and validated in full before any write.</li>
 *   <li>Writes go through a service user scoped to /var/dm-references (+ read on /content/dam).</li>
 * </ol>
 *
 * Responds 200 when every page was applied or was stale (already seen); 503 if any page failed to persist,
 * so the sender retries the batch (safe: already-applied pages come back as stale).
 */
@Component(service = Servlet.class, property = {
        "sling.servlet.paths=/bin/dmrefs/ingest",
        "sling.servlet.methods=POST"
})
public class IngestServlet extends SlingAllMethodsServlet {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LoggerFactory.getLogger(IngestServlet.class);

    @Reference
    private transient ReceiverSettings settings;

    @Reference
    private transient ReferenceStore store;

    @Reference
    private transient ResourceResolverFactory resolverFactory;

    @Override
    protected void doPost(SlingHttpServletRequest request, SlingHttpServletResponse response) throws IOException {
        if (!isWriter(request.getResourceResolver())) {
            LOG.warn("DM References: ingest rejected for non-writer user.");
            Http.error(response, 403, "forbidden");
            return;
        }
        String ct = request.getContentType();
        if (ct == null || !ct.toLowerCase(java.util.Locale.ROOT).startsWith("application/json")) {
            Http.error(response, 415, "content type must be application/json");
            return;
        }
        int max = settings.maxBodyBytes();
        if (request.getContentLengthLong() > max) {
            Http.error(response, 413, "payload too large");
            return;
        }
        String body = readBounded(request.getReader(), max);
        if (body == null) {
            Http.error(response, 413, "payload too large");
            return;
        }

        IngestRequest req;
        try {
            req = Http.GSON.fromJson(body, IngestRequest.class);
        } catch (JsonParseException e) {
            Http.error(response, 400, "malformed json");
            return;
        }
        PayloadValidator.Result vr =
                new PayloadValidator(settings.maxPagesPerRequest(), settings.maxRefsPerPage()).validate(req);
        if (!vr.ok()) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("error", "validation failed");
            err.put("details", vr.errors.size() > 50 ? vr.errors.subList(0, 50) : vr.errors);
            Http.json(response, 400, err);
            return;
        }
        if (!settings.isInstanceAllowed(vr.instanceId)) {
            Http.error(response, 403, "instance not allowed");
            return;
        }

        int applied = 0;
        int stale = 0;
        int unresolved = 0;
        int failed = 0;
        Map<String, String> vanityCache = new HashMap<>();
        try (ResourceResolver rr = Http.service(resolverFactory)) {
            if (rr.getResource(ReferenceStore.ROOT) == null) {
                LOG.error("DM References: service user '{}' cannot see {}; check the repoinit ACL.",
                        rr.getUserID(), ReferenceStore.ROOT);
                Http.error(response, 503, "temporarily unavailable");
                return;
            }
            for (PayloadValidator.ValidPage page : vr.pages) {
                ReferenceStore.Applied a = applyWithRetry(rr, vr.instanceId, vr.authorUrl, page, vanityCache);
                if (a == null) {
                    failed++;
                } else if (a.outcome == ReferenceStore.Outcome.STALE) {
                    stale++;
                } else {
                    applied++;
                    unresolved += a.unresolved;
                }
            }
        } catch (LoginException e) {
            LOG.error("DM References: service user login failed; check repoinit and service user mapping.");
            Http.error(response, 503, "temporarily unavailable");
            return;
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("applied", applied);
        out.put("stale", stale);
        out.put("unresolvedVanityRefs", unresolved);
        out.put("failed", failed);
        Http.json(response, failed > 0 ? 503 : 200, out);
        LOG.info("DM References: ingest from {} applied={} stale={} failed={}",
                vr.instanceId, applied, stale, failed);
    }

    private ReferenceStore.Applied applyWithRetry(ResourceResolver rr, String instanceId, String authorUrl,
                                                  PayloadValidator.ValidPage page, Map<String, String> cache) {
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                ReferenceStore.Applied a = store.apply(rr, instanceId, authorUrl, page, cache);
                rr.commit();
                return a;
            } catch (PersistenceException e) {
                rr.revert();
                rr.refresh();
                LOG.debug("DM References: conflict persisting page (attempt {}): {}", attempt + 1, e.getMessage());
            }
        }
        return null;
    }

    private boolean isWriter(ResourceResolver userResolver) {
        String group = settings.writerGroup();
        String userId = userResolver.getUserID();
        UserManager um = userResolver.adaptTo(UserManager.class);
        if (um == null || userId == null || "anonymous".equals(userId)) {
            return false;
        }
        try {
            Authorizable a = um.getAuthorizable(userId);
            if (a == null || a.isGroup()) {
                return false;
            }
            Iterator<Group> it = a.memberOf();
            while (it.hasNext()) {
                if (group.equals(it.next().getID())) {
                    return true;
                }
            }
            return false;
        } catch (RepositoryException e) {
            return false;
        }
    }

    /** @return body, or null if it exceeds {@code maxChars}. */
    private static String readBounded(BufferedReader reader, int maxChars) throws IOException {
        StringBuilder sb = new StringBuilder(Math.min(maxChars, 8192));
        char[] buf = new char[8192];
        int n;
        while ((n = reader.read(buf)) != -1) {
            if (sb.length() + n > maxChars) {
                return null;
            }
            sb.append(buf, 0, n);
        }
        return sb.toString();
    }
}
