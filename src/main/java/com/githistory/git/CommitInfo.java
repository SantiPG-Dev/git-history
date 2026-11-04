package com.githistory.git;

import java.time.Instant;
import java.util.List;

public record CommitInfo(
        String id,
        String shortId,
        String message,
        String fullMessage,
        String author,
        String email,
        Instant time,
        List<String> parents,
        List<RefLabel> refs) {

    public record RefLabel(String name, Type type, boolean current) {
        public enum Type { LOCAL, REMOTE, TAG }
    }
}
