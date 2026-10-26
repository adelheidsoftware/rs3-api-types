package org.adelheid.rs3types;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Generates {@code library/} from the docs site: reads every page the navigation links, learns from all of them the
 * other names their signatures give the types, and writes a definition file for each page that documents the API,
 * beside the hand-written {@code base.lua}. What the reader or the writer does not understand is printed at the end.
 * Run from the repository's root, as {@code ./gradlew generate} does: {@code --refresh} fetches every page again, and
 * {@code --offline} reads the cache alone.
 */
public final class Generate {

	/**
	 * A class or an alias base.lua declares, {@code ---@class EventSubscription<T, R>}, or a table it creates,
	 * {@code ui = {}}.
	 */
	private static final Pattern DECLARED =
			Pattern.compile("(?m)^---@(?:class|alias) ([\\w.]+)|^([\\w.]+) = \\{}");

	private Generate() {
	}

	public static void main(String[] args) throws IOException {
		DocsSite.Mode mode = DocsSite.Mode.CACHED;
		for (String arg : args) {
			mode = switch (arg) {
				case "--refresh" -> DocsSite.Mode.REFRESH;
				case "--offline" -> DocsSite.Mode.OFFLINE;
				default -> throw new IllegalArgumentException("Unknown option " + arg
						+ "; use --refresh or --offline.");
			};
		}
		Path root = Path.of("").toAbsolutePath();
		DocsSite site = new DocsSite(DocsSite.DOCS, root.resolve("cache"), mode, DocsSite.http());
		List<String> reports = generate(site, root.resolve("library"), Facts.load(), Corrections.load());
		PrintStream out = System.out;
		reports.forEach(out::println);
		out.println(reports.isEmpty() ? "library/ is up to date with the docs."
				: reports.size() + " things in the docs the generator did not understand.");
	}

	/**
	 * Rewrites a library folder from a site, with the corrections applied, returning what the reader and the writer did
	 * not understand and the corrections that found nothing to correct.
	 */
	static List<String> generate(DocsSite site, Path library, Facts facts, Corrections corrections)
			throws IOException {
		List<String> reports = new ArrayList<>();
		List<ApiPage> pages = new ArrayList<>();
		for (String path : site.pages()) {
			// A page that documents none of the API, such as a tutorial, is read only to be left out, so what the
			// reader makes of it is no report
			List<String> pageReports = new ArrayList<>();
			ApiPage page = PageReader.read(path, site.page(path), facts, pageReports);
			if (page.isApi()) {
				pages.add(page);
				reports.addAll(pageReports);
			}
		}
		pages = corrections.apply(pages, reports);
		String base = baseFile();
		Set<String> defined = defined(pages, base);
		Map<String, String> aliases = aliases(pages, defined, facts, reports);
		// A type described in prose alone is written where some file names it, which a first writing finds
		Map<String, String> wanted = new TreeMap<>();
		for (ApiPage page : pages) {
			LuaCatsWriter.write(page, "", aliases, new ArrayList<>(), wanted, Set.of());
		}
		Set<String> described = new HashSet<>();
		pages.forEach(page -> page.described().keySet().stream()
				.filter(name -> wanted.containsKey(name) && !defined.contains(name))
				.forEach(described::add));
		defined.addAll(described);
		clean(library);
		write(library.resolve("base.lua"), base);
		// Each type the files name, with the page that first names it, to find those no page defines
		Map<String, String> named = new TreeMap<>();
		for (ApiPage page : pages) {
			String lua = LuaCatsWriter.write(page, site.address(page.path()).toString(), aliases, reports, named,
					described);
			write(library.resolve(page.path().replaceAll("\\.html$", ".lua")), lua);
		}
		named.forEach((name, page) -> {
			if (!defined.contains(name)) {
				reports.add(page + ": names the type " + name + ", which no page defines.");
			}
		});
		facts.unused().forEach(fact -> reports.add("facts.txt: " + fact + ", which no page needs any more."));
		return reports;
	}

	/**
	 * The types other names stand for, by those names: the names the {@code alias} facts give, which come first; those
	 * the pages' signatures give their types, {@code NPC} for {@code NPCType}; and the last part of a namespaced type's
	 * name, where no other type ends in it, as {@code Button} is {@code ui.Button}, which the docs' signatures
	 * return. A name two pages give their types is reported, and the first is taken.
	 *
	 * @param types the types the pages and base.lua define
	 */
	private static Map<String, String> aliases(List<ApiPage> pages, Set<String> types, Facts facts,
			List<String> reports) {
		Map<String, String> aliases = new TreeMap<>(facts.aliases());
		for (ApiPage page : pages) {
			page.otherNames().forEach((other, type) -> {
				if (type.equals(aliases.get(other))) {
					return;
				}
				if (types.contains(other)) {
					// A signature that names another page's type, copied from there, names no other name of this one
					reports.add(page.path() + ": its signatures name " + other + ", another page's type, for " + type
							+ "; an owner fact says which is right.");
					return;
				}
				String earlier = aliases.putIfAbsent(other, type);
				if (earlier != null && !earlier.equals(type)) {
					reports.add(page.path() + ": its signatures call " + type + " " + other + ", as another page's"
							+ " call " + earlier + "; the first is taken.");
				}
			});
		}
		Map<String, List<String>> byLastPart = new TreeMap<>();
		types.stream().filter(type -> type.contains("."))
				.forEach(type -> byLastPart.computeIfAbsent(type.substring(type.lastIndexOf('.') + 1),
						last -> new ArrayList<>()).add(type));
		byLastPart.forEach((last, named) -> {
			if (named.size() == 1 && !types.contains(last)) {
				aliases.putIfAbsent(last, named.get(0));
			}
		});
		return aliases;
	}

	/** The types the pages and base.lua define: classes, enumerations, payloads, and the tables of namespaces. */
	private static Set<String> defined(List<ApiPage> pages, String base) {
		Set<String> defined = new HashSet<>(Set.of("Event"));
		for (ApiPage page : pages) {
			page.types().forEach(type -> defined.add(type.name()));
			page.enums().forEach(enumeration -> defined.add(enumeration.name()));
			page.events().stream()
					.filter(event -> event.payload() != null)
					.forEach(event -> defined.add(event.name() + "EventPayload"));
		}
		Matcher declared = DECLARED.matcher(base);
		while (declared.find()) {
			defined.add(declared.group(1) != null ? declared.group(1) : declared.group(2));
		}
		return defined;
	}

	private static String baseFile() throws IOException {
		try (InputStream in = Objects.requireNonNull(Generate.class.getResourceAsStream("base.lua"), "base.lua")) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	/** Deletes the generated files of a library folder, so that a page the site no longer has leaves no file behind. */
	private static void clean(Path library) throws IOException {
		if (!Files.isDirectory(library)) {
			return;
		}
		try (Stream<Path> walk = Files.walk(library)) {
			// Deepest first, so that a folder is empty when it is reached, unless it holds a file of another kind
			for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
				if (path.toString().endsWith(".lua") || (!path.equals(library) && isEmptyFolder(path))) {
					Files.delete(path);
				}
			}
		}
	}

	private static boolean isEmptyFolder(Path path) throws IOException {
		if (!Files.isDirectory(path)) {
			return false;
		}
		try (Stream<Path> children = Files.list(path)) {
			return children.findAny().isEmpty();
		}
	}

	private static void write(Path file, String text) throws IOException {
		Files.createDirectories(file.getParent());
		Files.writeString(file, text, StandardCharsets.UTF_8);
	}
}
