package org.adelheid.rs3types;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** The tests' pages, written by hand in the docs site's layout, and what the generator must make of them. */
final class Fixtures {

	/** The address the tests' site stands at. */
	static final URI SITE = URI.create("https://docs.example/");

	private Fixtures() {
	}

	/** A file of the tests' resources. */
	static String read(String name) {
		try (InputStream in = Objects.requireNonNull(Fixtures.class.getResourceAsStream(name), name)) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** Serves the tests' site: its home page, a guide, and the pages of {@code Lantern}, of lamps and of events. */
	static String serve(URI page) throws IOException {
		return switch (SITE.relativize(page).toString()) {
			case "index.html" -> read("index.html");
			case "guide.html" -> read("guide.html");
			case "core/lantern.html" -> read("lantern.html");
			case "core/lamps.html" -> read("lamps.html");
			case "core/events.html" -> read("events.html");
			default -> throw new IOException("The tests' site has no " + page + ".");
		};
	}

	/** The facts the tests' site needs. */
	static Facts facts() {
		return Facts.parse(read("test-facts.txt"));
	}
}
