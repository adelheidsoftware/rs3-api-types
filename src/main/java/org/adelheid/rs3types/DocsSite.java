package org.adelheid.rs3types;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;

/**
 * The public docs site, as the generator reads it: the pages its navigation links, each fetched once into a cache on
 * this machine and read from there afterwards. The cache is never committed, since the pages are Jagex's. Requests go
 * one at a time, a second apart, with a user agent that names the project and its contact.
 */
final class DocsSite {

	/** The docs site. */
	static final URI DOCS = URI.create("https://developer.runescape.com/rs/");

	/** A browser's user agent, as the site expects, followed by the project and whom to contact about it. */
	private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:158.0) Gecko/20100101"
			+ " Firefox/158.0 rs3-api-types (+https://github.com/adelheidsoftware/rs3-api-types; aaron@adelheid.org)";
	private static final Duration PAUSE = Duration.ofSeconds(1);
	private static final String INDEX = "index.html";

	/** Where a page comes from. */
	enum Mode {

		/** From the cache where it has the page, and otherwise from the site. */
		CACHED,

		/** From the site, again, for every page. */
		REFRESH,

		/** From the cache alone; a page it lacks fails the run. */
		OFFLINE
	}

	/** Fetches a page's HTML. */
	interface Fetcher {

		String fetch(URI page) throws IOException;
	}

	private final URI base;
	private final Path cache;
	private final Mode mode;
	private final Fetcher fetcher;

	DocsSite(URI base, Path cache, Mode mode, Fetcher fetcher) {
		this.base = base;
		this.cache = cache;
		this.mode = mode;
		this.fetcher = fetcher;
	}

	/** The pages the home page's navigation links, by their paths on the site, in the navigation's order. */
	List<String> pages() throws IOException {
		Set<String> pages = new LinkedHashSet<>();
		pages.add(INDEX);
		for (Element link : Jsoup.parse(page(INDEX), base.resolve(INDEX).toString()).select("nav a[href]")) {
			String address = link.attr("abs:href");
			int fragment = address.indexOf('#');
			if (fragment >= 0) {
				address = address.substring(0, fragment);
			}
			if (address.startsWith(base.toString()) && address.endsWith(".html")) {
				pages.add(address.substring(base.toString().length()));
			}
		}
		return new ArrayList<>(pages);
	}

	/** A page's address, by its path on the site. */
	URI address(String path) {
		return base.resolve(path);
	}

	/** A page's HTML, by its path on the site. */
	String page(String path) throws IOException {
		Path cached = cache.resolve(path).normalize();
		if (!cached.startsWith(cache.normalize())) {
			throw new IOException("The page " + path + " lies outside the site.");
		}
		if (mode != Mode.REFRESH && Files.isRegularFile(cached)) {
			return Files.readString(cached, StandardCharsets.UTF_8);
		}
		if (mode == Mode.OFFLINE) {
			throw new IOException("The cache has no " + path + "; run without -Poffline to fetch it.");
		}
		String html = fetcher.fetch(address(path));
		Files.createDirectories(cached.getParent());
		Files.writeString(cached, html, StandardCharsets.UTF_8);
		return html;
	}

	/** Fetches pages over HTTPS, one at a time and at least {@link #PAUSE} apart, naming the project. */
	static Fetcher http() {
		HttpClient client = HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(30))
				.followRedirects(HttpClient.Redirect.NORMAL)
				.build();
		Instant[] last = {Instant.EPOCH};
		return page -> {
			try {
				Duration since = Duration.between(last[0], Instant.now());
				if (since.compareTo(PAUSE) < 0) {
					Thread.sleep(PAUSE.minus(since));
				}
				HttpRequest request = HttpRequest.newBuilder(page)
						.header("User-Agent", USER_AGENT)
						.timeout(Duration.ofSeconds(30))
						.GET()
						.build();
				HttpResponse<String> response =
						client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
				last[0] = Instant.now();
				if (response.statusCode() != 200) {
					throw new IOException("The docs site answered " + response.statusCode() + " for " + page + ".");
				}
				return response.body();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new InterruptedIOException("Interrupted while fetching " + page + ".");
			}
		};
	}
}
