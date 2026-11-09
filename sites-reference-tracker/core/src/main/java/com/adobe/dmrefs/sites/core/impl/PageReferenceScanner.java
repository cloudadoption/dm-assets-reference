package com.adobe.dmrefs.sites.core.impl;

import com.adobe.dmrefs.sites.core.extract.ReferenceExtractor;
import com.adobe.dmrefs.sites.core.model.Ref;
import com.adobe.dmrefs.sites.core.model.ScanResult;
import com.adobe.dmrefs.sites.core.util.Hashes;
import com.adobe.dmrefs.sites.core.util.Caches.TemplateCache;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ValueMap;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Walks a page's jcr:content tree and its template's structure and collects DM references from every String /
 * String[] property. Follows Experience Fragment / Content Fragment paths (bounded depth, cycle-safe).
 * Template-structure results are cached per template and re-checked against last-modified stamps.
 */
@Component(service = PageReferenceScanner.class)
public class PageReferenceScanner {

    private static final Logger LOG = LoggerFactory.getLogger(PageReferenceScanner.class);
    private static final Pattern TEMPLATE_PATH = Pattern.compile("^/conf(/[A-Za-z0-9_.:@ -]+)+$");

    @Reference
    private TrackerSettings settings;

    private volatile TemplateCache<ScanResult> templateCache;

    @Activate
    protected void activate() {
        templateCache = new TemplateCache<>(settings.templateCacheSize(), settings.templateCacheTtlMs(),
                System::currentTimeMillis);
    }

    /** @return refs from the page and its template structure, or {@code null} if the page has no jcr:content. */
    public ScanResult scan(ResourceResolver rr, String pagePath) {
        Resource content = rr.getResource(pagePath + "/jcr:content");
        if (content == null) {
            return null;
        }
        Ctx ctx = new Ctx(settings);
        ctx.visited.add(pagePath);
        walk(content, ctx, 0);
        Set<Ref> refs = new TreeSet<>(ctx.refs);
        boolean partial = ctx.truncated;

        ScanResult tpl = scanTemplate(rr, content.getValueMap().get("cq:template", String.class));
        if (tpl != null) {
            refs.addAll(tpl.refs);
            partial |= tpl.partial;
        }
        if (partial) {
            LOG.warn("DM References: node limit {} reached while scanning {}; result is partial.",
                    ctx.maxNodes, pagePath);
        }
        LOG.debug("DM References: scanned {} -> {} ref(s) in {} node(s)", pagePath, refs.size(), ctx.nodes);
        return new ScanResult(refs, partial);
    }

    private ScanResult scanTemplate(final ResourceResolver rr, String template) {
        if (template == null || !TEMPLATE_PATH.matcher(template).matches() || template.contains("/../")
                || template.endsWith("/..")) {
            return null;
        }
        final String structurePath = template + "/structure";
        Resource structure = rr.getResource(structurePath + "/jcr:content");
        if (structure == null) {
            return null; // static templates have no structure node
        }
        TemplateCache<ScanResult> cache = templateCache;
        ScanResult cached = cache.get(template, new TemplateCache.StampReader() {
            @Override
            public long read(String path) {
                return stamp(rr, path);
            }
        });
        if (cached != null) {
            return cached;
        }
        Ctx ctx = new Ctx(settings);
        ctx.visited.add(structurePath);
        ctx.stamps.put(structurePath, stamp(rr, structurePath));
        walk(structure, ctx, 0);
        ScanResult result = new ScanResult(ctx.refs, ctx.truncated);
        cache.put(template, ctx.stamps, result);
        return result;
    }

    /** Last-modified of the node's jcr:content as millis, or 0 if it has none. */
    private static long stamp(ResourceResolver rr, String path) {
        Resource c = rr.getResource(path + "/jcr:content");
        if (c == null) {
            return 0L;
        }
        ValueMap vm = c.getValueMap();
        Calendar cal = vm.get("cq:lastModified", Calendar.class);
        if (cal == null) {
            cal = vm.get("jcr:lastModified", Calendar.class);
        }
        return cal == null ? 0L : cal.getTimeInMillis();
    }

    private void walk(Resource r, Ctx ctx, int depth) {
        if (ctx.nodes >= ctx.maxNodes) {
            ctx.truncated = true;
            return;
        }
        ctx.nodes++;
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
            if (ctx.truncated) {
                return;
            }
        }
    }

    private void handle(ResourceResolver rr, String key, String value, Ctx ctx, int depth) {
        ctx.extractor.extract(value, ctx.refs);
        if (depth < ctx.maxDepth && ctx.followProps.contains(key) && Hashes.isUnderAnyRoot(value, ctx.followRoots)
                && Hashes.isValidContentPath(value) && ctx.visited.add(value)) {
            follow(rr, value, ctx, depth + 1);
        }
    }

    private void follow(ResourceResolver rr, String path, Ctx ctx, int depth) {
        Resource target = rr.getResource(path);
        if (target == null) {
            return;
        }
        ctx.stamps.put(path, stamp(rr, path));
        // Pages (XF variations) and assets (CFs) both keep their payload under jcr:content.
        Resource content = target.getChild("jcr:content");
        walk(content != null ? content : target, ctx, depth);
    }

    private static final class Ctx {
        final Set<Ref> refs = new TreeSet<>();
        final Set<String> visited = new HashSet<>();
        final Map<String, Long> stamps = new HashMap<>();
        final ReferenceExtractor extractor;
        final Set<String> followProps;
        final String[] followRoots;
        final int maxDepth;
        final int maxNodes;
        int nodes;
        boolean truncated;

        Ctx(TrackerSettings s) {
            extractor = s.extractor();
            followProps = new HashSet<>(Arrays.asList(s.followProperties()));
            followRoots = s.followRoots();
            maxDepth = s.maxFollowDepth();
            maxNodes = s.maxNodesPerPage();
        }
    }
}
