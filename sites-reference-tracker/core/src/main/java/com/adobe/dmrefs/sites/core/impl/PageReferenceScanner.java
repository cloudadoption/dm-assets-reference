package com.adobe.dmrefs.sites.core.impl;

import com.adobe.dmrefs.sites.core.extract.ReferenceExtractor;
import com.adobe.dmrefs.sites.core.model.Ref;
import com.adobe.dmrefs.sites.core.util.Hashes;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ValueMap;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Walks a page's jcr:content tree and collects DM references from every String / String[] property.
 * Follows Experience Fragment / Content Fragment paths (bounded depth, cycle-safe).
 */
@Component(service = PageReferenceScanner.class)
public class PageReferenceScanner {

    private static final Logger LOG = LoggerFactory.getLogger(PageReferenceScanner.class);

    @Reference
    private TrackerSettings settings;

    /** @return refs found, or {@code null} if the page does not exist. */
    public Set<Ref> scan(ResourceResolver rr, String pagePath) {
        Resource content = rr.getResource(pagePath + "/jcr:content");
        if (content == null) {
            return null;
        }
        Ctx ctx = new Ctx(settings);
        ctx.visited.add(pagePath);
        walk(content, ctx, 0);
        if (ctx.nodes >= ctx.maxNodes) {
            LOG.warn("DM References: node limit {} reached while scanning {}; result may be partial.",
                    ctx.maxNodes, pagePath);
        }
        LOG.debug("DM References: scanned {} -> {} ref(s) in {} node(s)", pagePath, ctx.refs.size(), ctx.nodes);
        return ctx.refs;
    }

    private void walk(Resource r, Ctx ctx, int depth) {
        if (ctx.nodes++ >= ctx.maxNodes) {
            return;
        }
        ValueMap vm = r.getValueMap();
        for (Map.Entry<String, Object> e : vm.entrySet()) {
            Object v = e.getValue();
            if (v instanceof String) {
                handle(r.getResourceResolver(), e.getKey(), (String) v, ctx, depth);
            } else if (v instanceof String[]) {
                for (String s : (String[]) v) {
                    handle(r.getResourceResolver(), e.getKey(), s, ctx, depth);
                }
            }
        }
        for (Resource child : r.getChildren()) {
            walk(child, ctx, depth);
            if (ctx.nodes >= ctx.maxNodes) {
                return;
            }
        }
    }

    private void handle(ResourceResolver rr, String key, String value, Ctx ctx, int depth) {
        ctx.extractor.extract(value, ctx.refs);
        if (depth < ctx.maxDepth && ctx.followProps.contains(key) && Hashes.isUnderAnyRoot(value, ctx.followRoots)
                && ctx.visited.add(value)) {
            follow(rr, value, ctx, depth + 1);
        }
    }

    private void follow(ResourceResolver rr, String path, Ctx ctx, int depth) {
        Resource target = rr.getResource(path);
        if (target == null) {
            return;
        }
        // Pages (XF variations) and assets (CFs) both keep their payload under jcr:content.
        Resource content = target.getChild("jcr:content");
        walk(content != null ? content : target, ctx, depth);
    }

    private static final class Ctx {
        final Set<Ref> refs = new TreeSet<>();
        final Set<String> visited = new HashSet<>();
        final ReferenceExtractor extractor;
        final Set<String> followProps;
        final String[] followRoots;
        final int maxDepth;
        final int maxNodes;
        int nodes;

        Ctx(TrackerSettings s) {
            extractor = s.extractor();
            followProps = new HashSet<>(Arrays.asList(s.followProperties()));
            followRoots = s.followRoots();
            maxDepth = s.maxFollowDepth();
            maxNodes = s.maxNodesPerPage();
        }
    }
}
