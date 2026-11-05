package com.adobe.dmrefs.sites.core.impl;

import com.adobe.granite.crypto.CryptoException;
import com.adobe.granite.crypto.CryptoSupport;
import org.apache.http.HttpResponse;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.osgi.services.HttpClientBuilderFactory;
import org.apache.http.util.EntityUtils;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicyOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/** Sends ingest batches to the Assets receiver. Never logs the token or the payload. */
@Component(service = AssetsClient.class)
public class AssetsClient {

    private static final Logger LOG = LoggerFactory.getLogger(AssetsClient.class);

    @Reference
    private HttpClientBuilderFactory httpClientBuilderFactory;

    @Reference
    private TrackerSettings settings;

    @Reference(cardinality = ReferenceCardinality.OPTIONAL, policyOption = ReferencePolicyOption.GREEDY)
    private volatile CryptoSupport cryptoSupport;

    /** @return HTTP status code, or -1 on transport error. */
    public int post(String json) {
        RequestConfig rc = RequestConfig.custom()
                .setConnectTimeout(settings.connectTimeoutMs())
                .setConnectionRequestTimeout(settings.connectTimeoutMs())
                .setSocketTimeout(settings.socketTimeoutMs())
                .setRedirectsEnabled(false)
                .build();
        HttpPost post = new HttpPost(settings.endpoint());
        post.setConfig(rc);
        post.setHeader("Authorization", "Bearer " + token());
        post.setHeader("Accept", "application/json");
        post.setEntity(new StringEntity(json, ContentType.APPLICATION_JSON));

        try (CloseableHttpClient client = httpClientBuilderFactory.newBuilder()
                .useSystemProperties()
                .disableRedirectHandling()
                .disableCookieManagement()
                .build()) {
            HttpResponse resp = client.execute(post);
            int status = resp.getStatusLine().getStatusCode();
            EntityUtils.consumeQuietly(resp.getEntity());
            LOG.debug("DM References: POST to {} -> HTTP {}", settings.endpoint().getHost(), status);
            return status;
        } catch (IOException e) {
            LOG.warn("DM References: push to {} failed: {}", settings.endpoint().getHost(), e.getClass().getSimpleName());
            return -1;
        }
    }

    private String token() {
        String raw = settings.rawBearerToken();
        CryptoSupport cs = cryptoSupport;
        if (cs != null && cs.isProtected(raw)) {
            try {
                return cs.unprotect(raw);
            } catch (CryptoException e) {
                LOG.error("DM References: could not decrypt bearer.token; check the instance crypto keys.");
                return "";
            }
        }
        return raw;
    }
}
