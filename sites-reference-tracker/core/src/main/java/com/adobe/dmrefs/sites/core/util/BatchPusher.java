package com.adobe.dmrefs.sites.core.util;

import com.adobe.dmrefs.sites.core.model.PageUpdate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Sends a batch and isolates poison pages. A 2xx delivers the batch. A 400 or 413 resends each page on its own,
 * so one bad page is dropped while the rest are delivered and the queue is never blocked. Anything else
 * (5xx, transport error, auth failure) asks the caller to retry the job.
 */
public final class BatchPusher {

    /** @return the HTTP status, or -1 on a transport error. */
    public interface Sender {
        int send(List<PageUpdate> pages);
    }

    public static final class Result {
        public final List<PageUpdate> delivered = new ArrayList<>();
        public final List<PageUpdate> dropped = new ArrayList<>();
        public boolean retry;
        public int lastStatus;
    }

    private BatchPusher() {
    }

    public static Result push(List<PageUpdate> pages, Sender sender) {
        Result r = new Result();
        if (pages.isEmpty()) {
            return r;
        }
        int status = sender.send(pages);
        r.lastStatus = status;
        if (isOk(status)) {
            r.delivered.addAll(pages);
            return r;
        }
        if (!isPermanent(status)) {
            r.retry = true;
            return r;
        }
        if (pages.size() == 1) {
            r.dropped.addAll(pages);
            return r;
        }
        for (PageUpdate p : pages) {
            int s = sender.send(Collections.singletonList(p));
            r.lastStatus = s;
            if (isOk(s)) {
                r.delivered.add(p);
            } else if (isPermanent(s)) {
                r.dropped.add(p);
            } else {
                r.retry = true;
                return r;
            }
        }
        return r;
    }

    private static boolean isOk(int status) {
        return status >= 200 && status < 300;
    }

    private static boolean isPermanent(int status) {
        return status == 400 || status == 413;
    }
}
