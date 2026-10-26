package org.adelheid.rs3types;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * What the generator needs to know about the API that its pages do not say, from {@code facts.txt}, one fact a line.
 * A fact about a place in the docs names it by its page and its heading's anchor, {@code core/plugin.html#urls}. The
 * facts note which of them the pages used, so that a fact the docs no longer need is reported rather than kept.
 */
final class Facts {

	private static final Pattern SPACES = Pattern.compile("\\s+");

	/** Each fact by its kind and the type or place it is about, with its line and its values. */
	private final Map<String, Fact> facts;
	private final Set<String> used = new HashSet<>();

	/** A fact's line, and what follows its kind and its subject. */
	private record Fact(String line, List<String> values) {
	}

	private Facts(Map<String, Fact> facts) {
		this.facts = facts;
	}

	/** The facts the generator ships with. */
	static Facts load() {
		try (InputStream in = Objects.requireNonNull(Facts.class.getResourceAsStream("facts.txt"), "facts.txt")) {
			return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/**
	 * The facts in a text, between blank lines and {@code #} comments:
	 *
	 * <ul>
	 *   <li>{@code global <type>}: its table is a global, though the docs call each of its functions with a colon;
	 *   <li>{@code caller <type>}: in its functions, it stands for the type each is called on, a type that extends it;
	 *   <li>{@code name <place> <type>}: the type documented there, which neither its heading nor its signatures name;
	 *   <li>{@code parent <type> <parent>}: the type it extends, which the docs do not write;
	 *   <li>{@code rename <place> <function>}: the function documented there, which its signatures misname;
	 *   <li>{@code returns <place> <type>}: what the callbacks of the event documented there return;
	 *   <li>{@code list <place> <table> <type>}: the list there names the fields of a table, each of a type;
	 *   <li>{@code skip <place>}: a section the generator leaves out, being no API or written by hand in base.lua;
	 *   <li>{@code signature <place> <signature>}: the signature of the function documented there, which the docs
	 *       leave out;
	 *   <li>{@code owner <place> <type>}: the owner of the functions documented there, which their signatures misname;
	 *   <li>{@code field <place> <field> <type>}: the type of a field of a table there, which the table leaves out;
	 *   <li>{@code alias <name> <type>}: the type a name the docs use stands for, which no page defines, or another
	 *       page does, whose name the docs use for two types;
	 *   <li>{@code second <place> <field> <name>}: the name of the second field of that name a table there lists;
	 *   <li>{@code trust <place> signature|table}: which of the function's signature and its return table is right,
	 *       where they disagree.
	 * </ul>
	 *
	 * <p>A fact about a field is about the field of its place, so its subject is {@code <place>.<field>}; a signature
	 * is the rest of its line.
	 */
	static Facts parse(String text) {
		Map<String, Fact> facts = new LinkedHashMap<>();
		// Each kind's values, after its subject
		Map<String, Integer> arguments = Map.ofEntries(Map.entry("global", 0), Map.entry("caller", 0),
				Map.entry("name", 1),
				Map.entry("parent", 1), Map.entry("rename", 1), Map.entry("returns", 1), Map.entry("list", 2),
				Map.entry("skip", 0), Map.entry("owner", 1), Map.entry("field", 2), Map.entry("alias", 1),
				Map.entry("second", 2), Map.entry("trust", 1));
		for (String line : text.lines().toList()) {
			String stripped = line.strip();
			if (stripped.isEmpty() || stripped.startsWith("#")) {
				continue;
			}
			List<String> words = SPACES.splitAsStream(stripped).toList();
			if (words.get(0).equals("signature") && words.size() >= 3) {
				String rest = stripped.substring(stripped.indexOf(words.get(1)) + words.get(1).length()).strip();
				facts.put("signature " + words.get(1), new Fact(stripped, List.of(rest)));
				continue;
			}
			Integer expected = arguments.get(words.get(0));
			if (expected == null || words.size() != expected + 2) {
				throw new IllegalArgumentException("facts.txt has a line the generator does not understand: " + line);
			}
			if (words.get(0).equals("field") || words.get(0).equals("second")) {
				facts.put(words.get(0) + " " + words.get(1) + "." + words.get(2),
						new Fact(stripped, List.of(words.get(3))));
			} else {
				facts.put(words.get(0) + " " + words.get(1), new Fact(stripped, words.subList(2, words.size())));
			}
		}
		return new Facts(facts);
	}

	/** Whether a type's table is a global, though the docs write none of its functions with a dot. */
	boolean isGlobal(String type) {
		return fact("global", type) != null;
	}

	/** Whether a type stands, in its functions, for the type each is called on, as Vector for Vector2. */
	boolean isCaller(String type) {
		return fact("caller", type) != null;
	}

	/** The type documented at a place, which neither its heading nor its signatures name; null for none. */
	@Nullable String name(String place) {
		return value("name", place);
	}

	/** The type a type extends, which the docs do not write; null for none. */
	@Nullable String parent(String type) {
		return value("parent", type);
	}

	/** The function documented at a place, which its signatures misname; null for none. */
	@Nullable String rename(String place) {
		return value("rename", place);
	}

	/** What the callbacks of the event documented at a place return; null for nothing. */
	@Nullable String returns(String place) {
		return value("returns", place);
	}

	/** The table whose fields the list at a place names, and their type; null for none. */
	@Nullable List<String> list(String place) {
		Fact fact = fact("list", place);
		return fact == null ? null : fact.values();
	}

	/** The signature of the function documented at a place, which the docs leave out; null for none. */
	@Nullable String signature(String place) {
		return value("signature", place);
	}

	/** The owner of the functions documented at a place, which their signatures misname; null for none. */
	@Nullable String owner(String place) {
		return value("owner", place);
	}

	/** The type of a field of a table at a place, which the table leaves out; null for none. */
	@Nullable String field(String place, String field) {
		return value("field", place + "." + field);
	}

	/** The name of the second field of that name a table at a place lists; null for none. */
	@Nullable String second(String place, String field) {
		return value("second", place + "." + field);
	}

	/**
	 * Which of a function's signature and its return table is right, {@code signature} or {@code table}; null for none.
	 */
	@Nullable String trust(String place) {
		return value("trust", place);
	}

	/** The types the names the docs use stand for, by those names, from the {@code alias} facts. */
	Map<String, String> aliases() {
		Map<String, String> aliases = new LinkedHashMap<>();
		facts.forEach((key, fact) -> {
			if (key.startsWith("alias ")) {
				used.add(key);
				aliases.put(key.substring("alias ".length()), fact.values().get(0));
			}
		});
		return aliases;
	}

	/** Whether the generator leaves out the section at a place. */
	boolean skips(String place) {
		return fact("skip", place) != null;
	}

	/** The lines of the facts that no page used, which the docs no longer need. */
	List<String> unused() {
		List<String> unused = new ArrayList<>();
		facts.forEach((key, fact) -> {
			if (!used.contains(key)) {
				unused.add(fact.line());
			}
		});
		return unused;
	}

	/** The one value of a fact; null where there is no such fact. */
	private @Nullable String value(String kind, String subject) {
		Fact fact = fact(kind, subject);
		return fact == null ? null : fact.values().get(0);
	}

	private @Nullable Fact fact(String kind, String subject) {
		String key = kind + " " + subject;
		Fact fact = facts.get(key);
		if (fact != null) {
			used.add(key);
		}
		return fact;
	}
}
