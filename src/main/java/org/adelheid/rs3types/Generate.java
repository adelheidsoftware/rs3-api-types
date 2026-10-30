package org.adelheid.rs3types;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
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
 * Generates {@code generated/library/} from the docs site: reads every page the navigation links, learns from all of
 * them the other names their signatures give the types, and writes a definition file for each page that documents the
 * API, beside the hand-written {@code base.lua}. What the reader or the writer does not understand is printed at the
 * end. Run from the repository's root, as {@code ./gradlew generate} does: {@code --refresh} fetches every page again,
 * {@code --offline} reads the cache alone, and {@code --no-bindings} leaves bindings.txt out and writes the library to
 * {@code build/library-docs-only} instead, so that generated/ keeps its word.
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
		boolean withBindings = true;
		for (String arg : args) {
			switch (arg) {
				case "--refresh" -> mode = DocsSite.Mode.REFRESH;
				case "--offline" -> mode = DocsSite.Mode.OFFLINE;
				case "--no-bindings" -> withBindings = false;
				default -> throw new IllegalArgumentException("Unknown option " + arg
						+ "; use --refresh, --offline or --no-bindings.");
			}
		}
		Path root = Path.of("").toAbsolutePath();
		DocsSite site = new DocsSite(DocsSite.DOCS, root.resolve("cache"), mode, DocsSite.http());
		Path library = root.resolve(withBindings ? "generated/library" : "build/library-docs-only");
		Corrections bindings = withBindings ? Corrections.loadBindings() : Corrections.parse("", "bindings.txt");
		List<String> reports = generate(site, library, root.resolve("generated/constants"), Facts.load(), bindings,
				Corrections.load());
		PrintStream out = System.out;
		reports.forEach(out::println);
		out.println(reports.isEmpty() ? "generated/ is up to date with the docs."
				: reports.size() + " things in the docs the generator did not understand.");
	}

	/**
	 * Rewrites a library folder from a site, with bindings.txt's corrections and then corrections.txt's applied, and
	 * the LuaCATS of the constants a folder's CSVs give, returning what the reader and the writer did not understand
	 * and the lines of either file that found nothing to correct.
	 *
	 * @param bindings the corrections of bindings.txt, applied first, so that one of corrections.txt wins
	 */
	static List<String> generate(DocsSite site, Path library, Path constants, Facts facts, Corrections bindings,
			Corrections corrections) throws IOException {
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
		pages = bindings.apply(pages, reports);
		pages = corrections.apply(pages, reports);
		pages = withSharedFields(pages, facts, reports);
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
		ConstantsWriter.write(constants, library, configTypes(constants, aliases, defined, reports));
		facts.unused().forEach(fact -> reports.add("facts.txt: " + fact + ", which no page needs any more."));
		return reports;
	}

	/**
	 * The pages with the fields each {@code fields} fact gives a type: the other type's, as corrected, but for those
	 * the fact leaves out and those the type has itself. A field to leave out that the other type lacks is reported,
	 * as the docs may have changed.
	 */
	private static List<ApiPage> withSharedFields(List<ApiPage> pages, Facts facts, List<String> reports) {
		Map<String, ApiPage.TypeDoc> types = new HashMap<>();
		pages.forEach(page -> page.types().forEach(type -> types.put(type.name(), type)));
		List<ApiPage> shared = new ArrayList<>();
		for (ApiPage page : pages) {
			List<ApiPage.TypeDoc> pageTypes = new ArrayList<>();
			for (ApiPage.TypeDoc type : page.types()) {
				List<String> fact = facts.fields(type.name());
				ApiPage.TypeDoc other = fact == null ? null : types.get(fact.get(0));
				if (fact == null || other == null) {
					if (fact != null) {
						reports.add("facts.txt: " + type.name() + " has the fields of " + fact.get(0)
								+ ", which no page defines.");
					}
					pageTypes.add(type);
					continue;
				}
				List<String> leftOut = fact.subList(1, fact.size());
				leftOut.stream()
						.filter(name -> other.properties().stream().noneMatch(property -> property.name().equals(name)))
						.forEach(name -> reports.add("facts.txt: " + type.name() + " leaves out " + name + ", which "
								+ other.name() + " does not have."));
				List<ApiPage.PropertyDoc> properties = new ArrayList<>(type.properties());
				other.properties().stream()
						.filter(property -> !leftOut.contains(property.name())
								&& properties.stream().noneMatch(own -> own.name().equals(property.name())))
						.forEach(properties::add);
				pageTypes.add(type.withProperties(properties));
			}
			shared.add(new ApiPage(page.path(), pageTypes, page.globals(), page.enums(), page.events(),
					page.otherNames(), page.described()));
		}
		return shared;
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

	/**
	 * The type of each config namespace's configs, by the namespace: its name and {@code Type}, {@code NPCType} for
	 * {@code NPC}, or the type an alias gives that name, as {@code QuestTypeConfig} for {@code QuestType}. One no page
	 * defines is reported, and is {@code any}.
	 */
	private static Map<String, String> configTypes(Path constants, Map<String, String> aliases, Set<String> defined,
			List<String> reports) throws IOException {
		Map<String, String> types = new TreeMap<>();
		for (String namespace : Constants.tables(constants).getOrDefault("config", List.of())) {
			String type = aliases.getOrDefault(namespace + "Type", namespace + "Type");
			if (!defined.contains(type)) {
				reports.add("generated/constants/tables.txt: config." + namespace + " holds " + type
						+ ", which no page defines.");
				type = "any";
			}
			types.put(namespace, type);
		}
		return types;
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
