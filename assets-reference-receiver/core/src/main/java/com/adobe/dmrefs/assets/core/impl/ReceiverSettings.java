package com.adobe.dmrefs.assets.core.impl;

import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

@Component(service = ReceiverSettings.class, immediate = true)
@Designate(ocd = ReceiverSettings.Config.class)
public class ReceiverSettings {

    private static final Logger LOG = LoggerFactory.getLogger(ReceiverSettings.class);
    /** Relative property path under jcr:content/metadata; validated because it is placed into a query. */
    private static final Pattern PROPERTY_NAME = Pattern.compile("^[A-Za-z0-9_-]+(:[A-Za-z0-9_-]+)?$");

    @ObjectClassDefinition(name = "DM References - Receiver settings")
    public @interface Config {

        @AttributeDefinition(name = "Writer group", description = "Only members may POST to /bin/dmrefs/ingest.")
        String writer_group() default "dm-reference-writers";

        @AttributeDefinition(name = "Max body bytes")
        int max_body_bytes() default 1048576;

        @AttributeDefinition(name = "Max pages per request")
        int max_pages_per_request() default 200;

        @AttributeDefinition(name = "Max refs per page")
        int max_refs_per_page() default 500;

        @AttributeDefinition(name = "Vanity metadata property",
                description = "Property under <asset>/jcr:content/metadata that holds the vanity ID. "
                        + "VERIFY: set to wherever the customer's vanity IDs are stored.")
        String vanity_metadata_property() default "dam:vanityId";

        @AttributeDefinition(name = "Allowed instance IDs", description = "Empty = any well-formed ID.")
        String[] allowed_instance_ids() default {};

        @AttributeDefinition(name = "Max references returned")
        int max_references_returned() default 1000;
    }

    private volatile Config config;
    private volatile Set<String> allowedInstances;
    private volatile String vanityProperty;

    @Activate
    @Modified
    protected void activate(Config cfg) {
        this.config = cfg;
        this.allowedInstances = cfg.allowed_instance_ids() == null ? Collections.<String>emptySet()
                : new HashSet<>(Arrays.asList(cfg.allowed_instance_ids()));
        if (PROPERTY_NAME.matcher(cfg.vanity_metadata_property()).matches()) {
            this.vanityProperty = cfg.vanity_metadata_property();
        } else {
            LOG.error("DM References: invalid vanity.metadata.property; vanity resolution disabled.");
            this.vanityProperty = null;
        }
    }

    public String writerGroup() {
        return config.writer_group();
    }

    public int maxBodyBytes() {
        return config.max_body_bytes();
    }

    public int maxPagesPerRequest() {
        return config.max_pages_per_request();
    }

    public int maxRefsPerPage() {
        return config.max_refs_per_page();
    }

    public int maxReferencesReturned() {
        return config.max_references_returned();
    }

    /** @return validated property name, or null if vanity resolution is disabled. */
    public String vanityProperty() {
        return vanityProperty;
    }

    public boolean isInstanceAllowed(String instanceId) {
        Set<String> allowed = allowedInstances;
        return allowed.isEmpty() || allowed.contains(instanceId);
    }
}
