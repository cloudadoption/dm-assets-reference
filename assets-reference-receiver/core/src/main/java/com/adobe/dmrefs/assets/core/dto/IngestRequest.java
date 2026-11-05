package com.adobe.dmrefs.assets.core.dto;

import java.util.List;

/**
 * Wire DTOs for POST /bin/dmrefs/ingest. Only these fields are bound; unknown JSON fields are ignored.
 * Never persisted directly: {@link com.adobe.dmrefs.assets.core.validate.PayloadValidator} turns them into
 * validated value objects first.
 */
public class IngestRequest {

    public Integer schemaVersion;
    public String instanceId;
    public String authorUrl;
    public List<PageUpdate> pages;

    public static class PageUpdate {
        public String pagePath;
        public Long seq;
        public Boolean deleted;
        public List<RefDto> author;
        public List<RefDto> published;
    }

    public static class RefDto {
        public String type;
        public String ref;

        public RefDto() {
        }

        public RefDto(String type, String ref) {
            this.type = type;
            this.ref = ref;
        }
    }
}
