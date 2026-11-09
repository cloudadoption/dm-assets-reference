package com.adobe.dmrefs.sites.core.impl;

import com.adobe.dmrefs.sites.core.model.PageUpdate;
import com.adobe.dmrefs.sites.core.util.BatchPusher;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;

/** Sends ingest batches to the Assets receiver. Never logs the token or the payload. */
@Component(service = AssetsClient.class)
public class AssetsClient {

    private static final Logger LOG = LoggerFactory.getLogger(AssetsClient.class);

    @Reference
    private HttpClientBuilderFactory httpClientBuilderFactory;

    @Reference
    private TrackerSettings settings;

    @Reference
    private TokenProvider tokens;

    /**
     * Sends the pages, resending one by one if Assets rejects the batch, so one bad page is dropped and the rest
     * delivered. See {@link BatchPusher}.
     */
    public BatchPusher.Result push(List<PageUpdate> pages) {
        return BatchPusher.push(pages, new BatchPusher.Sender() {
            @Override
            public int send(List<PageUpdate> batch) {
                return post(PushPayload.build(settings.instanceId(), settings.authorUrl(), batch));
            }
        });
    }

    /**
     * On a 401 or 403 in oauth mode the token is refreshed and the request is sent once more.
     *
     * @return HTTP status code, or -1 on a transport or token error.
     */
    private int post(String json) {
        for (int attempt = 0; attempt < 2; attempt++) {
            String token;
            try {
                token = tokens.token();
            } catch (IOException e) {
                return -1;
            }
            int status = send(json, token);
            if ((status == 401 || status == 403) && attempt == 0 && tokens.isOauth()) {
                tokens.invalidate();
                continue;
            }
            return status;
        }
        return -1;
    }

    private int send(String json, String token) {
        RequestConfig rc = RequestConfig.custom()
                .setConnectTimeout(settings.connectTimeoutMs())
                .setConnectionRequestTimeout(settings.connectTimeoutMs())
                .setSocketTimeout(settings.socketTimeoutMs())
                .setRedirectsEnabled(false)
                .build();
        HttpPost post = new HttpPost(settings.endpoint());
        post.setConfig(rc);
        post.setHeader("Authorization", "Bearer " + token);
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
}
