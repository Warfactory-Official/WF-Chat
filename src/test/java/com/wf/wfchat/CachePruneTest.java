package com.wf.wfchat;

import static org.assertj.core.api.Assertions.assertThat;

import com.wf.wfchat.core.CachePrune;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CachePruneTest {
    @TempDir
    Path dir;

    private void file(String name, int size, long mtime) throws Exception {
        Path p = dir.resolve(name);
        Files.createDirectories(p.getParent());
        Files.write(p, new byte[size]);
        Files.setLastModifiedTime(p, FileTime.fromMillis(mtime));
    }

    @Test
    void evictsOldestFirstAndKeepsPinned() throws Exception {
        file("dictionary.json", 100, 1);
        file("a.png", 100, 1000);
        file("server/b.png", 100, 2000);
        file("c.png", 100, 3000);
        long freed = CachePrune.prune(dir, 250, Set.of("dictionary.json"));
        assertThat(freed).isEqualTo(200);
        assertThat(dir.resolve("dictionary.json")).exists();
        assertThat(dir.resolve("a.png")).doesNotExist();
        assertThat(dir.resolve("server/b.png")).doesNotExist();
        assertThat(dir.resolve("c.png")).exists();
    }

    @Test
    void underLimitUntouchedAndMissingDirOk() throws Exception {
        file("a.png", 10, 1);
        assertThat(CachePrune.prune(dir, 100, Set.of())).isZero();
        assertThat(CachePrune.prune(dir.resolve("none"), 1, Set.of())).isZero();
    }
}
