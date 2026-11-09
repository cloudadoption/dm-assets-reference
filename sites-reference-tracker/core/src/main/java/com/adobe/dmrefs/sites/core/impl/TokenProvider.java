package com.adobe.dmrefs.sites.core.impl;

import com.adobe.dmrefs.sites.core.util.Ims;
import com.adobe.dmrefs.sites.core.util.Caches.TokenCache;
import com.adobe.granite.crypto.CryptoException;
import com.adobe.granite.crypto.CryptoSupport;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.osgi.services.HttpClientBuilderFactory;
import org.apache.http.util.EntityUtils;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicyOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Supplies the bearer token for Assets. {@code oauth} mode exchanges the technical account's client credentials
 * at IMS and keeps the token in memory until shortly before it expires; {@code static} is for development.
 * Never logs the token or the secret.
 */
@Component(service = TokenProvider.class)
public class TokenProvider {

    private static final Logger LOG = LoggerFactory.getLogger(TokenProvider.class);
    private static final long SKEW_MS = 5 * 60 * 1000L;
    private static final long MAX_RESPONSE_BYTES = 64 * 1024L;

    @Reference
    private HttpClientBuilderFactory httpClientBuilderFactory;

    @Reference
    private TrackerSettings settings;

    @Reference(cardinality = ReferenceCardinality.OPTIONAL, policyOption = ReferencePolicyOption.GREEDY)
    private volatile CryptoSupport cryptoSupport;

    private final TokenCache cache = new TokenCache(System::currentTimeMillis, SKEW_MS);

    public boolean isOauth() {
        return settings.isOauth();
    }

    /** @throws IOException if no token could be obtained */
    public String token() throws IOException {
        if (!settings.isOauth()) {
            return unprotect(settings.rawBearerToken());
        }
        return cache.get(new TokenCache.Fetcher() {
            @Override
            public TokenCache.Fetched fetch() throws IOException {
                return fetchFromIms();
            }
        });
    }

    /** Drops the cached token so the next call fetches a new one, e.g. after a 401. */
    public void invalidate() {
        cache.invalidate();
    }

    private TokenCache.Fetched fetchFromIms() throws IOException {
        String url = settings.imsTokenUrl();
        if (!Ims.isAllowedTokenUrl(url)) {
            throw new IOException("ims.token.url is not an allowed IMS endpoint");
        }
        List<NameValuePair> form = new ArrayList<>();
        form.add(new BasicNameValuePair("grant_type", "client_credentials"));
        form.add(new BasicNameValuePair("client_id", settings.imsClientId()));
        form.add(new BasicNameValuePair("client_secret", unprotect(settings.rawImsClientSecret())));
        form.add(new BasicNameValuePair("scope", String.join(",", settings.imsScopes())));

        RequestConfig rc = RequestConfig.custom()
                .setConnectTimeout(settings.connectTimeoutMs())
                .setConnectionRequestTimeout(settings.connectTimeoutMs())
                .setSocketTimeout(settings.socketTimeoutMs())
                .setRedirectsEnabled(false)
                .build();
        HttpPost post = new HttpPost(url);
        post.setConfig(rc);
        post.setEntity(new UrlEncodedFormEntity(form, StandardCharsets.UTF_8));

        try (CloseableHttpClient client = httpClientBuilderFactory.newBuilder()
                .useSystemProperties()
                .disableRedirectHandling()
                .disableCookieManagement()
                .build()) {
            HttpResponse resp = client.execute(post);
            int status = resp.getStatusLine().getStatusCode();
            HttpEntity entity = resp.getEntity();
            if (status != 200 || entity == null || entity.getContentLength() > MAX_RESPONSE_BYTES) {
                EntityUtils.consumeQuietly(entity);
                LOG.warn("DM References: IMS token request failed (HTTP {}); check ims.client.id and the secret.",
                        status);
                throw new IOException("IMS token request failed");
            }
            TokenCache.Fetched f = Ims.parseTokenResponse(EntityUtils.toString(entity, StandardCharsets.UTF_8));
            if (f == null) {
                LOG.warn("DM References: IMS token response had no access token.");
                throw new IOException("IMS token response invalid");
            }
            LOG.debug("DM References: obtained IMS token valid for {} s", f.expiresInSeconds());
            return f;
        }
    }

    private String unprotect(String raw) {
        CryptoSupport cs = cryptoSupport;
        if (cs != null && raw != null && cs.isProtected(raw)) {
            try {
                return cs.unprotect(raw);
            } catch (CryptoException e) {
                LOG.error("DM References: could not decrypt a protected setting; check the instance crypto keys.");
                return "";
            }
        }
        return raw == null ? "" : raw;
    }
}
