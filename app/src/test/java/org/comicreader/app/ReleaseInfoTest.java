package org.comicreader.app;

import static org.junit.Assert.*;

import org.junit.Test;

public class ReleaseInfoTest {
    @Test
    public void versionsUseNumericComponents() throws Exception {
        assertTrue(ReleaseInfo.compareVersions("v0.10.0", "0.9.9") > 0);
        assertTrue(ReleaseInfo.compareVersions("1.0.0", "0.9999.9999") > 0);
        assertEquals(0, ReleaseInfo.compareVersions("v0.4.0", "0.4.0"));
        assertTrue(ReleaseInfo.compareVersions("0.3.0", "0.4.0") < 0);
    }

    @Test
    public void ambiguousOrNonStableVersionsAreRejected() {
        for (String value :
                new String[] {
                    "", "latest", "0.4", "0.4.0-beta", "0.04.0", "10000.0.0", "v0.4.0/../../other"
                })
            assertThrows(
                    java.io.IOException.class, () -> ReleaseInfo.compareVersions(value, "0.4.0"));
    }

    @Test
    public void redirectsStayOnHttpsGitHubHosts() {
        assertTrue(
                ReleaseInfo.trustedUrl(
                        "https://release-assets.githubusercontent.com/path?signature=example"));
        assertTrue(
                ReleaseInfo.trustedUrl(
                        "https://github.com/Poiki/ComicReader/releases/download/v0.4.0/ComicReader-arm64-v8a.apk"));
        for (String value :
                new String[] {
                    "http://github.com/a",
                    "https://github.com.evil.example/a",
                    "https://evil.example/github.com",
                    "https://user:secret@github.com/a",
                    "https://github.com:8443/a",
                    "file:///a",
                    "https://github.com/a#fragment"
                }) assertFalse(value, ReleaseInfo.trustedUrl(value));
    }
}
