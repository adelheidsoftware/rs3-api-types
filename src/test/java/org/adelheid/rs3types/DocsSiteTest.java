package org.adelheid.rs3types;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The pages of the tests' site, and the cache that keeps them. */
class DocsSiteTest {

	@TempDir
	Path cache;

	private final List<URI> fetched = new ArrayList<>();

	private DocsSite site(DocsSite.Mode mode) {
		return new DocsSite(Fixtures.SITE, cache, mode, page -> {
			fetched.add(page);
			return Fixtures.serve(page);
		});
	}

	@Test
	void listsThePagesTheNavigationLinksOnTheSite() throws IOException {
		assertEquals(List.of("index.html", "core/lantern.html", "core/lamps.html", "core/events.html", "guide.html"),
				site(DocsSite.Mode.CACHED).pages());
	}

	@Test
	void fetchesAPageOnceAndReadsItFromTheCacheAfterwards() throws IOException {
		String first = site(DocsSite.Mode.CACHED).page("core/lantern.html");
		String again = site(DocsSite.Mode.OFFLINE).page("core/lantern.html");
		assertEquals(first, again);
		assertEquals(List.of(Fixtures.SITE.resolve("core/lantern.html")), fetched);
		assertTrue(cache.resolve("core/lantern.html").toFile().isFile());
	}

	@Test
	void aRefreshFetchesAgain() throws IOException {
		site(DocsSite.Mode.CACHED).page("guide.html");
		site(DocsSite.Mode.REFRESH).page("guide.html");
		assertEquals(2, fetched.size());
	}

	@Test
	void offlineAPageTheCacheLacksFails() {
		IOException missing = assertThrows(IOException.class, () -> site(DocsSite.Mode.OFFLINE).page("guide.html"));
		assertEquals("The cache has no guide.html; run without -Poffline to fetch it.", missing.getMessage());
	}

	@Test
	void aPathOutsideTheSiteFails() {
		assertThrows(IOException.class, () -> site(DocsSite.Mode.CACHED).page("../outside.html"));
	}
}
