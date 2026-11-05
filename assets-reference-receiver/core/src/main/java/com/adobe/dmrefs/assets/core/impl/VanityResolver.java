package com.adobe.dmrefs.assets.core.impl;

import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.query.Query;
import javax.jcr.query.QueryManager;
import javax.jcr.query.RowIterator;
import java.util.Map;

/**
 * Vanity ID <-> asset UUID, using a metadata property on the asset.
 *
 * <p>Assumption for the MVP: the DM asset ID ({@code urn:aaid:aem:<uuid>}) is the asset node's jcr:uuid,
 * and the vanity ID is stored at {@code jcr:content/metadata/<vanity.metadata.property>}.
 * For production, add an Oak property index on that property.</p>
 */
@Component(service = VanityResolver.class)
public class VanityResolver {

    private static final Logger LOG = LoggerFactory.getLogger(VanityResolver.class);
    private static final String NOT_FOUND = "";

    @Reference
    private ReceiverSettings settings;

    /**
     * @param cache per-request cache (vanity -> uuid or "" when unresolved)
     * @return the bare UUID, or null if unresolved or ambiguous
     */
    public String resolve(Session session, String vanity, Map<String, String> cache) {
        String hit = cache.get(vanity);
        if (hit != null) {
            return hit.isEmpty() ? null : hit;
        }
        String uuid = query(session, vanity);
        cache.put(vanity, uuid == null ? NOT_FOUND : uuid);
        return uuid;
    }

    private String query(Session session, String vanity) {
        String prop = settings.vanityProperty();
        if (prop == null) {
            return null;
        }
        // prop is validated against a strict pattern; the user-supplied vanity is a bound variable.
        String stmt = "SELECT [jcr:path] FROM [dam:Asset] AS a WHERE ISDESCENDANTNODE(a, [/content/dam]) "
                + "AND a.[jcr:content/metadata/" + prop + "] = $v";
        try {
            QueryManager qm = session.getWorkspace().getQueryManager();
            Query q = qm.createQuery(stmt, Query.JCR_SQL2);
            q.bindValue("v", session.getValueFactory().createValue(vanity));
            q.setLimit(2);
            RowIterator rows = q.execute().getRows();
            if (!rows.hasNext()) {
                return null;
            }
            Node n = rows.nextRow().getNode();
            if (rows.hasNext()) {
                LOG.warn("DM References: vanity id maps to more than one asset; leaving unresolved.");
                return null;
            }
            return n.getIdentifier().toLowerCase(java.util.Locale.ROOT);
        } catch (RepositoryException e) {
            LOG.warn("DM References: vanity lookup failed: {}", e.getMessage());
            return null;
        }
    }

    /** @return the asset's vanity ID, or null. */
    public String vanityOf(Node asset) {
        String prop = settings.vanityProperty();
        if (prop == null) {
            return null;
        }
        String rel = "jcr:content/metadata/" + prop;
        try {
            return asset.hasProperty(rel) ? asset.getProperty(rel).getString() : null;
        } catch (RepositoryException e) {
            return null;
        }
    }
}
