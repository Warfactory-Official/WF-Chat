package com.wf.wfchat.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/** Least-recently-modified eviction for a cache directory; readers touch files they use. */
public final class CachePrune {
    private CachePrune() {
    }

    /** Deletes oldest files until the tree is at most {@code maxBytes}; {@code keep} file names are never deleted. Returns bytes freed. */
    public static long prune(Path dir, long maxBytes, Set<String> keep) throws IOException {
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        List<Path> files;
        try (Stream<Path> s = Files.walk(dir)) {
            files = s.filter(Files::isRegularFile).toList();
        }
        long total = 0;
        for (Path f : files) {
            total += Files.size(f);
        }
        long freed = 0;
        List<Path> oldest = files.stream().filter(f -> !keep.contains(f.getFileName().toString()))
                .sorted(Comparator.comparingLong(CachePrune::mtime)).toList();
        for (Path f : oldest) {
            if (total <= maxBytes) {
                break;
            }
            long size = Files.size(f);
            Files.delete(f);
            total -= size;
            freed += size;
        }
        return freed;
    }

    private static long mtime(Path f) {
        try {
            return Files.getLastModifiedTime(f).toMillis();
        } catch (IOException ex) {
            return 0;
        }
    }
}
