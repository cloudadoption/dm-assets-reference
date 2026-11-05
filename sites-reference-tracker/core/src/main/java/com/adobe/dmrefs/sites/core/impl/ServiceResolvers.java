package com.adobe.dmrefs.sites.core.impl;

import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;

import java.util.Collections;

/** Service user 'dm-references-tracker' (mapped via ServiceUserMapperImpl.amended~dmrefs). */
final class ServiceResolvers {

    static final String SUBSERVICE = "tracker";

    private ServiceResolvers() {
    }

    static ResourceResolver open(ResourceResolverFactory f) throws LoginException {
        return f.getServiceResourceResolver(
                Collections.<String, Object>singletonMap(ResourceResolverFactory.SUBSERVICE, SUBSERVICE));
    }
}
