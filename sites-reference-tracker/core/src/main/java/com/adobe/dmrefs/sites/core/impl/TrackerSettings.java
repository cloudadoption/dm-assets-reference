package com.adobe.dmrefs.sites.core.impl;

import com.adobe.dmrefs.sites.core.extract.ReferenceExtractor;
import com.adobe.dmrefs.sites.core.util.Hashes;
import com.adobe.dmrefs.sites.core.util.Ims;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.AttributeType;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Central configuration for the tracker. Other components read settings from here. */
@Component(service = TrackerSettings.class, immediate = true)
@Designate(ocd = TrackerSettings.Config.class)
public class TrackerSettings {

    private static final Logger LOG = LoggerFactory.getLogger(TrackerSettings.class);
    private static final Pattern INSTANCE_ID = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$");

    @ObjectClassDefinition(name = "DM References - Tracker settings",
            description = "Tracks Dynamic Media asset references on Sites author and pushes them to AEM Assets.")
    public @interface Config {

        @AttributeDefinition(name = "Enabled")
        boolean enabled() default true;

        @AttributeDefinition(name = "Instance ID",
                description = "Stable, unique ID of this Sites environment, e.g. acme-prod-author. [A-Za-z0-9._-]{1,64}")
        String instance_id() default "";

        @AttributeDefinition(name = "Assets ingest endpoint",
                description = "Full URL, e.g. https://author-pXXXX-eYYYY.adobeaemcloud.com/bin/dmrefs/ingest")
        String endpoint_url() default "";

        @AttributeDefinition(name = "Bearer token", type = AttributeType.PASSWORD,
                description = "Only used when auth.mode=static (development). IMS access token for a user in the "
                        + "Assets 'dm-reference-writers' group. On 6.5 may be a CryptoSupport-protected value {...}.")
        String bearer_token() default "";

        @AttributeDefinition(name = "Auth mode",
                description = "oauth: IMS client-credentials token, refreshed automatically (default). "
                        + "static: the bearer.token above, for development only.")
        String auth_mode() default "oauth";

        @AttributeDefinition(name = "IMS token URL",
                description = "Must be https on a known Adobe IMS host (checked in code).")
        String ims_token_url() default "https://ims-na1.adobelogin.com/ims/token/v3";

        @AttributeDefinition(name = "IMS client ID", description = "Technical account client ID.")
        String ims_client_id() default "";

        @AttributeDefinition(name = "IMS client secret", type = AttributeType.PASSWORD,
                description = "Technical account client secret. On 6.5 may be CryptoSupport-protected {...}; "
                        + "on Cloud use $[secret:...].")
        String ims_client_secret() default "";

        @AttributeDefinition(name = "IMS scopes")
        String[] ims_scopes() default {"openid", "AdobeID", "read_organizations",
            "additional_info.projectedProductContext", "read_pc.dma_aem_ams"};

        @AttributeDefinition(name = "Job delay (seconds)",
                description = "Wait this long after a publish event before reading the page and pushing. "
                        + "Repeat publishes within the window are scanned once. 1 to 86400.")
        int job_delay_seconds() default 10;

        @AttributeDefinition(name = "Scan cache size", description = "Pages remembered as already delivered.")
        int scan_cache_size() default 10000;

        @AttributeDefinition(name = "Template cache size")
        int template_cache_size() default 200;

        @AttributeDefinition(name = "Template cache TTL (seconds)")
        int template_cache_ttl_seconds() default 600;

        @AttributeDefinition(name = "Backfill pause (ms)", description = "Pause between backfill chunks.")
        int backfill_pause_ms() default 2000;

        @AttributeDefinition(name = "Draft tracking enabled",
                description = "Track page edits (best effort) so unpublished pages show the assets they use.")
        boolean draft_enabled() default true;

        @AttributeDefinition(name = "Draft quiet window (seconds)",
                description = "A page is scanned once it has had no edits for this long.")
        int draft_quiet_seconds() default 60;

        @AttributeDefinition(name = "Draft max wait (seconds)",
                description = "A page that keeps being edited is scanned anyway after this long.")
        int draft_max_wait_seconds() default 300;

        @AttributeDefinition(name = "Draft max entries",
                description = "Pages waiting in memory. When full, the oldest tenth is scanned early.")
        int draft_max_entries() default 10000;

        @AttributeDefinition(name = "Draft ignored users",
                description = "Edits by these users are ignored. Exact name, or a prefix ending in *.")
        String[] draft_ignored_users() default {"replication-service"};

        @AttributeDefinition(name = "Fingerprint cache size",
                description = "Pages whose last delivered draft set is remembered, to skip unchanged ones.")
        int fingerprint_cache_size() default 10000;

        @AttributeDefinition(name = "Allow plain HTTP", description = "Local development only.")
        boolean allow_insecure_http() default false;

        @AttributeDefinition(name = "Author URL",
                description = "Public https base URL of this author instance, sent to Assets so it can link to pages. "
                        + "e.g. https://author.acme.com. Optional.")
        String author_url() default "";

        @AttributeDefinition(name = "Content roots", description = "Only pages under these roots are tracked.")
        String[] content_roots() default {"/content"};

        @AttributeDefinition(name = "UUID patterns",
                description = "Regexes with a named group 'uuid' (the asset UUID).")
        String[] uuid_patterns() default {
            "urn:aaid:aem:(?<uuid>[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})",
            "dm-aid--(?<uuid>[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})"
        };

        @AttributeDefinition(name = "Vanity patterns",
                description = "Regexes with a named group 'vanity'. VERIFY against the customer's vanity URL format.")
        String[] vanity_patterns() default {
            "/adobe/assets/(?<vanity>(?!urn:)[A-Za-z0-9._~-]{1,200})/as/"
        };

        @AttributeDefinition(name = "Follow properties",
                description = "Properties whose value is a path to follow (Experience Fragments, Content Fragments).")
        String[] follow_properties() default {"fragmentVariationPath", "fragmentPath"};

        @AttributeDefinition(name = "Follow roots", description = "Followed paths must be under one of these.")
        String[] follow_roots() default {"/content/experience-fragments", "/content/dam"};

        @AttributeDefinition(name = "Max follow depth")
        int max_follow_depth() default 3;

        @AttributeDefinition(name = "Max nodes scanned per page",
                description = "A page that hits this limit is sent as partial: references are only added.")
        int max_nodes_per_page() default 20000;

        @AttributeDefinition(name = "Push batch size", description = "Pages per POST.")
        int push_batch_size() default 100;

        @AttributeDefinition(name = "Connect timeout (ms)")
        int connect_timeout_ms() default 5000;

        @AttributeDefinition(name = "Socket timeout (ms)")
        int socket_timeout_ms() default 15000;
    }

    private volatile Config config;
    private volatile ReferenceExtractor extractor;
    private volatile String[] contentRoots;
    private volatile URI endpoint;
    private volatile String authorUrl;

    @Activate
    @Modified
    protected void activate(Config cfg) {
        this.config = cfg;
        this.extractor = new ReferenceExtractor(cfg.uuid_patterns(), cfg.vanity_patterns());
        this.contentRoots = validRoots(cfg.content_roots());
        this.endpoint = parseEndpoint(cfg.endpoint_url(), cfg.allow_insecure_http());
        this.authorUrl = Hashes.normalizeAuthorUrl(cfg.author_url());
        if (!cfg.author_url().trim().isEmpty() && authorUrl == null) {
            LOG.warn("DM References: author.url must be an https URL without credentials, query or fragment; "
                    + "it will not be sent.");
        }
        if (!INSTANCE_ID.matcher(cfg.instance_id()).matches()) {
            LOG.warn("DM References: instance.id is missing or invalid; pushes are disabled until it is set.");
        }
        if (!isOauth()) {
            LOG.warn("DM References: auth.mode=static is for development only; use oauth in production.");
        }
        LOG.info("DM References: settings loaded: {}", describe());
    }

    /** Effective settings for logs; never includes the token value. */
    String describe() {
        Config c = config;
        if (c == null) {
            return "unconfigured";
        }
        return "enabled=" + c.enabled() + ", instanceId=" + c.instance_id()
                + ", endpointHost=" + (endpoint == null ? "none" : endpoint.getHost())
                + ", authorUrl=" + (authorUrl == null ? "none" : authorUrl)
                + ", authMode=" + (isOauth() ? "oauth" : "static")
                + ", credentialsSet=" + hasCredentials()
                + ", contentRoots=" + Arrays.toString(contentRoots)
                + ", uuidPatterns=" + c.uuid_patterns().length
                + ", vanityPatterns=" + c.vanity_patterns().length;
    }

    private static String[] validRoots(String[] roots) {
        List<String> out = new ArrayList<>();
        if (roots != null) {
            for (String r : roots) {
                if ("/content".equals(r) || Hashes.isValidContentPath(r)) {
                    out.add(r);
                } else {
                    LOG.warn("DM References: ignoring invalid content root {}", r);
                }
            }
        }
        return out.toArray(new String[0]);
    }

    private static URI parseEndpoint(String url, boolean allowHttp) {
        if (url == null || url.trim().isEmpty()) {
            return null;
        }
        try {
            URI u = URI.create(url.trim());
            String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
            boolean ok = "https".equals(scheme) || (allowHttp && "http".equals(scheme));
            if (!ok || u.getHost() == null || u.getUserInfo() != null) {
                LOG.error("DM References: endpoint.url must be an https URL without credentials; pushes disabled.");
                return null;
            }
            return u;
        } catch (IllegalArgumentException e) {
            LOG.error("DM References: endpoint.url is not a valid URI; pushes disabled.");
            return null;
        }
    }

    public boolean isEnabled() {
        return config != null && config.enabled();
    }

    /** True when everything needed to push is configured. */
    public boolean canPush() {
        return isEnabled() && endpoint != null && INSTANCE_ID.matcher(config.instance_id()).matches()
                && hasCredentials();
    }

    private boolean hasCredentials() {
        Config c = config;
        if (c == null) {
            return false;
        }
        if (isOauth()) {
            return !c.ims_client_id().isEmpty() && !c.ims_client_secret().isEmpty()
                    && Ims.isAllowedTokenUrl(c.ims_token_url());
        }
        return !c.bearer_token().isEmpty();
    }

    public boolean isOauth() {
        return config != null && !"static".equalsIgnoreCase(config.auth_mode());
    }

    public String imsTokenUrl() {
        return config.ims_token_url();
    }

    public String imsClientId() {
        return config.ims_client_id();
    }

    public String rawImsClientSecret() {
        return config.ims_client_secret();
    }

    public String[] imsScopes() {
        return config.ims_scopes();
    }

    /** Clamped to 1..86400 s. */
    public int jobDelaySeconds() {
        return Math.min(86400, Math.max(1, config.job_delay_seconds()));
    }

    public int scanCacheSize() {
        return Math.max(10, config.scan_cache_size());
    }

    public int templateCacheSize() {
        return Math.max(1, config.template_cache_size());
    }

    public long templateCacheTtlMs() {
        return Math.max(1, config.template_cache_ttl_seconds()) * 1000L;
    }

    public int backfillPauseMs() {
        return Math.max(0, config.backfill_pause_ms());
    }

    /** Draft tracking needs the same configuration as pushing. */
    public boolean isDraftEnabled() {
        return canPush() && config.draft_enabled();
    }

    public long draftQuietMs() {
        return Math.max(1, config.draft_quiet_seconds()) * 1000L;
    }

    public long draftMaxWaitMs() {
        return Math.max(config.draft_quiet_seconds(), config.draft_max_wait_seconds()) * 1000L;
    }

    public int draftMaxEntries() {
        return Math.max(10, config.draft_max_entries());
    }

    public String[] draftIgnoredUsers() {
        return config.draft_ignored_users();
    }

    public int fingerprintCacheSize() {
        return Math.max(10, config.fingerprint_cache_size());
    }

    public String instanceId() {
        return config.instance_id();
    }

    public URI endpoint() {
        return endpoint;
    }

    /** Normalized https base URL of this author instance, or null if not configured. */
    public String authorUrl() {
        return authorUrl;
    }

    public String rawBearerToken() {
        return config.bearer_token();
    }

    public ReferenceExtractor extractor() {
        return extractor;
    }

    public String[] contentRoots() {
        return contentRoots.clone();
    }

    public String[] followProperties() {
        return config.follow_properties();
    }

    public String[] followRoots() {
        return config.follow_roots();
    }

    public int maxFollowDepth() {
        return Math.max(0, config.max_follow_depth());
    }

    public int maxNodesPerPage() {
        return Math.max(100, config.max_nodes_per_page());
    }

    public int pushBatchSize() {
        return Math.min(500, Math.max(1, config.push_batch_size()));
    }

    public int connectTimeoutMs() {
        return config.connect_timeout_ms();
    }

    public int socketTimeoutMs() {
        return config.socket_timeout_ms();
    }
}
