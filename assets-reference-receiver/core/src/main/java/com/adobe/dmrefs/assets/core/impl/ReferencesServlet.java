package com.adobe.dmrefs.assets.core.impl;

import com.adobe.dmrefs.assets.core.util.Paths;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.api.servlets.SlingSafeMethodsServlet;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.AccessDeniedException;
import javax.jcr.ItemNotFoundException;
import javax.jcr.Node;
import javax.jcr.PathNotFoundException;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.servlet.Servlet;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

/**
 * GET /bin/dmrefs/references?assetId=urn:aaid:aem:&lt;uuid&gt;  or  ?path=/content/dam/...
 *
 * <p>The asset is resolved with the <em>caller's</em> session, so a user only sees references for assets
 * they can read (no IDOR). Reference rows are then read with the service user.</p>
 */
@Component(service = Servlet.class, property = {
        "sling.servlet.paths=/bin/dmrefs/references",
        "sling.servlet.methods=GET"
})
public class ReferencesServlet extends SlingSafeMethodsServlet {

    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LoggerFactory.getLogger(ReferencesServlet.class);

    @Reference
    private transient ReceiverSettings settings;

    @Reference
    private transient ReferenceStore store;

    @Reference
    private transient VanityResolver vanityResolver;

    @Reference
    private transient ResourceResolverFactory resolverFactory;

    @Override
    protected void doGet(SlingHttpServletRequest request, SlingHttpServletResponse response) throws IOException {
        Session userSession = request.getResourceResolver().adaptTo(Session.class);
        if (userSession == null) {
            Http.error(response, 401, "unauthorized");
            return;
        }
        Node asset;
        try {
            asset = findAsset(userSession, request.getParameter("assetId"), request.getParameter("path"));
        } catch (IllegalArgumentException e) {
            Http.error(response, 400, e.getMessage());
            return;
        } catch (RepositoryException e) {
            Http.error(response, 404, "asset not found");
            return;
        }
        if (asset == null) {
            Http.error(response, 404, "asset not found");
            return;
        }

        try (ResourceResolver rr = Http.service(resolverFactory)) {
            String uuid = asset.getIdentifier().toLowerCase(java.util.Locale.ROOT);
            String vanity = vanityResolver.vanityOf(asset);
            int max = settings.maxReferencesReturned();

            List<ReferenceStore.Entry> rows = store.forAsset(rr, uuid, max);
            if (vanity != null && Paths.VANITY.matcher(vanity).matches() && rows.size() < max) {
                rows.addAll(store.forUnresolvedVanity(rr, vanity, max - rows.size()));
            }

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("assetId", Paths.URN_PREFIX + uuid);
            out.put("assetPath", asset.getPath());
            out.put("vanity", vanity);
            List<Map<String, Object>> refs = merge(rows);
            out.put("total", refs.size());
            out.put("truncated", rows.size() >= max);
            out.put("references", refs);
            Http.json(response, 200, out);
        } catch (LoginException e) {
            LOG.error("DM References: service user login failed; check repoinit and service user mapping.");
            Http.error(response, 503, "temporarily unavailable");
        } catch (RepositoryException e) {
            Http.error(response, 500, "internal error");
        }
    }

    private static Node findAsset(Session s, String assetId, String path) throws RepositoryException {
        Node n;
        if (assetId != null) {
            String uuid = Paths.uuidFromUrn(assetId);
            if (uuid == null) {
                throw new IllegalArgumentException("assetId must be urn:aaid:aem:<uuid>");
            }
            try {
                n = s.getNodeByIdentifier(uuid);
            } catch (ItemNotFoundException | AccessDeniedException e) {
                return null;
            }
        } else if (path != null) {
            if (!Paths.isValidDamPath(path)) {
                throw new IllegalArgumentException("path must be under /content/dam");
            }
            try {
                n = s.getNode(path);
            } catch (PathNotFoundException | AccessDeniedException e) {
                return null;
            }
        } else {
            throw new IllegalArgumentException("assetId or path required");
        }
        return n.isNodeType("dam:Asset") ? n : null;
    }

    /** Merges resolved + unresolved rows for the same (instance, page). */
    private static List<Map<String, Object>> merge(List<ReferenceStore.Entry> rows) {
        Map<String, Map<String, Object>> byPage = new LinkedHashMap<>();
        SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
        iso.setTimeZone(TimeZone.getTimeZone("UTC"));
        for (ReferenceStore.Entry e : rows) {
            String k = e.instanceId + "\n" + e.pagePath;
            Map<String, Object> m = byPage.get(k);
            if (m == null) {
                m = new LinkedHashMap<>();
                m.put("instanceId", e.instanceId);
                m.put("pagePath", e.pagePath);
                m.put("author", false);
                m.put("published", false);
                m.put("referencedAs", new ArrayList<String>());
                byPage.put(k, m);
            }
            m.put("author", (Boolean) m.get("author") || e.author);
            m.put("published", (Boolean) m.get("published") || e.published);
            @SuppressWarnings("unchecked")
            List<String> as = (List<String>) m.get("referencedAs");
            for (String r : e.referencedAs) {
                if (!as.contains(r)) {
                    as.add(r);
                }
            }
            if (e.updatedAt != null) {
                m.put("updatedAt", iso.format(e.updatedAt.getTime()));
            }
            String page = Paths.pageUrl(e.authorUrl, e.pagePath);
            if (page != null) {
                m.put("authorUrl", Paths.normalizeAuthorUrl(e.authorUrl));
                m.put("pageUrl", page);
            }
        }
        return new ArrayList<>(byPage.values());
    }
}
