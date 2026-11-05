package com.adobe.dmrefs.assets.core.impl;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;

import java.io.IOException;
import java.util.Collections;

/** Shared response + service-resolver helpers for the two servlets. */
final class Http {

    static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    static final String SUBSERVICE = "writer";

    private Http() {
    }

    static void json(SlingHttpServletResponse resp, int status, Object body) throws IOException {
        resp.setStatus(status);
        resp.setContentType("application/json");
        resp.setCharacterEncoding("UTF-8");
        resp.setHeader("Cache-Control", "no-store");
        resp.setHeader("X-Content-Type-Options", "nosniff");
        resp.getWriter().write(GSON.toJson(body));
    }

    static void error(SlingHttpServletResponse resp, int status, String message) throws IOException {
        json(resp, status, Collections.singletonMap("error", message));
    }

    static ResourceResolver service(ResourceResolverFactory f) throws LoginException {
        return f.getServiceResourceResolver(
                Collections.<String, Object>singletonMap(ResourceResolverFactory.SUBSERVICE, SUBSERVICE));
    }
}
