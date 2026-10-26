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
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Corrections to the API the pages give, from {@code corrections.txt}: where the client differs from the docs, or the
 * docs say in prose or an example what their tables and signatures do not. Each is about a member by its Lua path,
 * {@code NPCType.attackOpPriority}, or a function's, {@code ui.Interface:GetComponent}, and gives its evidence after a
 * {@code #}. Types are written as LuaCATS writes them. The corrections are applied once the pages are read; one that
 * matches nothing in the docs, or changes nothing, is reported, as the docs or the client may have caught up with it.
 */
final class Corrections {

	private static final Pattern SPACES = Pattern.compile("\\s+");
	/** The parameters of a LuaCATS function type, {@code fun(staticID: integer, name?: string): T}. */
	private static final Pattern FUN = Pattern.compile("fun\\((.*)\\)(?:\\s*:\\s*(.+))?");
	private static final Pattern FUN_PARAM = Pattern.compile("(\\w+|\\.\\.\\.)(\\?)?\\s*:\\s*(.+)");
	/** The kinds, by the number of words that follow the subject; -1 for a rest of the line, as a type or a text is. */
	private static final Map<String, Integer> KINDS = Map.ofEntries(Map.entry("rename", 1), Map.entry("type", -1),
			Map.entry("optional", 0), Map.entry("field", -1), Map.entry("function", -1), Map.entry("param", -1),
			Map.entry("values", -1), Map.entry("parent", 1), Map.entry("remove", 0), Map.entry("read-only", 0),
			Map.entry("note", -1), Map.entry("class", 1));
	/** The kinds that may end with a description, after {@code --}: those that add what the docs leave out. */
	private static final Set<String> DESCRIBED = Set.of("field", "function", "class");
	private static final String DESCRIPTION = " -- ";

	private final List<Correction> corrections;

	/** A correction's kind, its subject, what follows it up to its description, its description, and its line. */
	private record Correction(String kind, String subject, String rest, String description, String line) {
	}

	private Corrections(List<Correction> corrections) {
		this.corrections = corrections;
	}

	/** The corrections the generator ships with. */
	static Corrections load() {
		try (InputStream in = Objects.requireNonNull(Corrections.class.getResourceAsStream("corrections.txt"),
				"corrections.txt")) {
			return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/**
	 * The corrections in a text, one a line, between blank lines and {@code #} comments, each followed by its evidence
	 * after {@code #}:
	 *
	 * <ul>
	 *   <li>{@code rename <member|Enum.VALUE> <name>}: the name of the member, or of the enumeration's value, in the
	 *       client, which the other lines name it by;
	 *   <li>{@code type <member> <type>}: a field's type, or what a function returns, {@code nil} for nothing;
	 *   <li>{@code optional <member>}: the field, or what the function returns, may be nil;
	 *   <li>{@code field <Class.field> <type>}: a field the docs do not give, or another type for one they do;
	 *   <li>{@code function <Owner.name|Owner:name> <fun(...)>}: a function's form; a function's first line replaces
	 *       the forms the docs give, or adds the function, and each further line adds a form;
	 *   <li>{@code param <function> <name> <type>}: a parameter's type, which may be left out where the name ends in
	 *       {@code ?} and not otherwise;
	 *   <li>{@code values <enum> <NAME=value...>}: the values of an enumeration the docs give no values for;
	 *   <li>{@code parent <class> <class>}: the class it extends;
	 *   <li>{@code remove <member>}: a member the docs give and the client lacks;
	 *   <li>{@code read-only <class|field>}: the field, or each field of the class, cannot be assigned;
	 *   <li>{@code note <member|class|enum> <text>}: what the client does that the docs do not say, shown "In the
	 *       client:";
	 *   <li>{@code class <Class> <page>}: a table the docs describe in prose alone, in the file of a page, whose
	 *       fields are {@code field} lines.
	 * </ul>
	 *
	 * <p>A {@code field}, {@code function} or {@code class} line may end with a description after {@code --}, which
	 * replaces the docs' or describes what they leave out.
	 */
	static Corrections parse(String text) {
		List<Correction> corrections = new ArrayList<>();
		for (String line : text.lines().toList()) {
			String stripped = line.strip();
			if (stripped.isEmpty() || stripped.startsWith("#")) {
				continue;
			}
			int evidence = stripped.indexOf(" #");
			if (evidence < 0) {
				throw new IllegalArgumentException("corrections.txt has a line without its evidence: " + line);
			}
			String correction = stripped.substring(0, evidence).strip();
			String description = "";
			int dashes = correction.indexOf(DESCRIPTION);
			if (dashes >= 0) {
				description = correction.substring(dashes + DESCRIPTION.length()).strip();
				correction = correction.substring(0, dashes).strip();
			}
			List<String> words = SPACES.splitAsStream(correction).toList();
			Integer expected = KINDS.get(words.get(0));
			boolean fits = expected != null && words.size() >= 2
					&& (expected < 0 ? words.size() >= 3 : words.size() == expected + 2)
					&& (description.isEmpty() || DESCRIBED.contains(words.get(0)));
			if (!fits) {
				throw new IllegalArgumentException("corrections.txt has a line the generator does not understand: "
						+ line);
			}
			String afterSubject = correction.substring(correction.indexOf(words.get(1)) + words.get(1).length())
					.strip();
			corrections.add(new Correction(words.get(0), words.get(1), afterSubject, description, stripped));
		}
		return new Corrections(corrections);
	}

	/**
	 * The pages with the corrections applied, in the order of the file. A correction that matches nothing in the docs,
	 * or changes nothing, as the docs say the same, is reported.
	 */
	List<ApiPage> apply(List<ApiPage> pages, List<String> reports) {
		List<ApiPage> corrected = new ArrayList<>(pages);
		// The functions whose forms a line has replaced already, whose further lines add forms
		Set<String> replaced = new HashSet<>();
		for (Correction correction : corrections) {
			List<ApiPage> before = List.copyOf(corrected);
			if (!apply(correction, corrected, replaced)) {
				reports.add("corrections.txt: " + correction.line() + ", which matches nothing in the docs.");
			} else if (corrected.equals(before)) {
				reports.add("corrections.txt: " + correction.line() + ", which the docs now say themselves.");
			}
		}
		return corrected;
	}

	private boolean apply(Correction correction, List<ApiPage> pages, Set<String> replaced) {
		return switch (correction.kind()) {
			case "class" -> addClass(pages, correction.subject(), correction.rest(), correction.description());
			case "values" -> values(pages, correction.subject(), correction.rest());
			case "parent" -> changeType(pages, correction.subject(),
					type -> withParent(type, correction.rest()));
			case "note" -> note(pages, correction.subject(), correction.rest());
			case "read-only" -> readOnly(pages, correction.subject());
			case "field" -> field(pages, correction.subject(), correction.rest(), correction.description());
			case "function" -> function(pages, correction.subject(), correction.rest(), correction.description(),
					replaced);
			default -> member(pages, correction);
		};
	}

	/** A correction of one member: renamed, retyped, made optional, or removed, or one of a function's parameters. */
	private static boolean member(List<ApiPage> pages, Correction correction) {
		String path = correction.subject();
		String rest = correction.rest();
		return switch (correction.kind()) {
			case "rename" -> changeMember(pages, path,
					property -> new ApiPage.PropertyDoc(rest, property.type(), property.description()),
					function -> renamed(function, rest)) || renameValue(pages, path, rest);
			case "type" -> changeMember(pages, path,
					property -> new ApiPage.PropertyDoc(property.name(), lua(rest), property.description()),
					function -> withReturns(function, rest.equals("nil") ? List.of() : List.of(lua(rest))));
			case "optional" -> changeMember(pages, path,
					property -> new ApiPage.PropertyDoc(property.name(), optional(property.type()),
							property.description()),
					function -> withReturns(function, function.forms().get(0).returns().stream()
							.map(Corrections::optional).toList()));
			case "remove" -> removeMember(pages, path);
			case "param" -> {
				List<String> words = SPACES.splitAsStream(rest).toList();
				String written = words.get(0);
				String type = rest.substring(written.length()).strip();
				String name = written.endsWith("?") ? written.substring(0, written.length() - 1) : written;
				String typed = written.endsWith("?") ? lua(type) + "?" : lua(type);
				boolean[] named = {false};
				boolean found = changeMember(pages, path, property -> property, function -> {
					named[0] |= function.forms().stream()
							.anyMatch(form -> form.params().stream().anyMatch(param -> param.name().equals(name)));
					return withParam(function, name, typed);
				});
				yield found && named[0];
			}
			default -> throw new IllegalStateException(correction.kind());
		};
	}

	// ---- Finding what a path names

	/** Where a type is: the index of its page, and its index among the page's types; null for none. */
	private record Place(int page, int index) {
	}

	private static @Nullable Place type(List<ApiPage> pages, String name) {
		for (int page = 0; page < pages.size(); page++) {
			List<ApiPage.TypeDoc> types = pages.get(page).types();
			for (int index = 0; index < types.size(); index++) {
				if (types.get(index).name().equals(name)) {
					return new Place(page, index);
				}
			}
		}
		return null;
	}

	/** A member's path split after its type: the longest start of the path that names a type, and the rest. */
	private record Member(String type, String name) {
	}

	private static @Nullable Member member(List<ApiPage> pages, String path) {
		String normalised = path.replace(':', '.');
		for (int dot = normalised.lastIndexOf('.'); dot > 0; dot = normalised.lastIndexOf('.', dot - 1)) {
			String type = normalised.substring(0, dot);
			if (type(pages, type) != null || payload(pages, type) != null) {
				return new Member(type, normalised.substring(dot + 1));
			}
		}
		return null;
	}

	/** Where an event's payload is, by its class's name, {@code ChatMessageEventPayload}; null for none. */
	private static @Nullable Place payload(List<ApiPage> pages, String name) {
		if (!name.endsWith("EventPayload")) {
			return null;
		}
		String event = name.substring(0, name.length() - "EventPayload".length());
		for (int page = 0; page < pages.size(); page++) {
			List<ApiPage.EventDoc> events = pages.get(page).events();
			for (int index = 0; index < events.size(); index++) {
				if (events.get(index).name().equals(event) && events.get(index).payload() != null) {
					return new Place(page, index);
				}
			}
		}
		return null;
	}

	// ---- Changing the pages

	/** Changes a member, a field or a function of a type or of a payload, or a global function; false for none. */
	private static boolean changeMember(List<ApiPage> pages, String path,
			UnaryOperator<ApiPage.PropertyDoc> onProperty, UnaryOperator<ApiPage.FunctionDoc> onFunction) {
		Member member = member(pages, path);
		if (member == null) {
			return changeGlobal(pages, path, onFunction);
		}
		boolean[] found = {false};
		UnaryOperator<List<ApiPage.PropertyDoc>> properties = list -> list.stream().map(property -> {
			if (property.name().equals(member.name())) {
				found[0] = true;
				return onProperty.apply(property);
			}
			return property;
		}).toList();
		UnaryOperator<List<ApiPage.FunctionDoc>> functions = list -> list.stream().map(function -> {
			if (function.name().equals(member.name())) {
				found[0] = true;
				return onFunction.apply(function);
			}
			return function;
		}).toList();
		changeType(pages, member.type(), type -> new ApiPage.TypeDoc(type.name(), type.generics(), type.parent(),
				type.description(), functions.apply(type.functions()), properties.apply(type.properties()),
				type.indexes(), type.global()));
		changePayload(pages, member.type(), properties);
		return found[0];
	}

	private static boolean changeGlobal(List<ApiPage> pages, String name, UnaryOperator<ApiPage.FunctionDoc> change) {
		for (int page = 0; page < pages.size(); page++) {
			ApiPage each = pages.get(page);
			List<ApiPage.FunctionDoc> globals = new ArrayList<>(each.globals());
			for (int index = 0; index < globals.size(); index++) {
				if (globals.get(index).name().equals(name)) {
					globals.set(index, change.apply(globals.get(index)));
					pages.set(page, new ApiPage(each.path(), each.types(), globals, each.enums(), each.events(),
							each.otherNames(), each.described()));
					return true;
				}
			}
		}
		return false;
	}

	private static boolean changeType(List<ApiPage> pages, String name, UnaryOperator<ApiPage.TypeDoc> change) {
		Place place = type(pages, name);
		if (place == null) {
			return false;
		}
		ApiPage page = pages.get(place.page());
		List<ApiPage.TypeDoc> types = new ArrayList<>(page.types());
		types.set(place.index(), change.apply(types.get(place.index())));
		pages.set(place.page(), new ApiPage(page.path(), types, page.globals(), page.enums(), page.events(),
				page.otherNames(), page.described()));
		return true;
	}

	private static void changePayload(List<ApiPage> pages, String name,
			UnaryOperator<List<ApiPage.PropertyDoc>> change) {
		Place place = payload(pages, name);
		if (place == null) {
			return;
		}
		ApiPage page = pages.get(place.page());
		List<ApiPage.EventDoc> events = new ArrayList<>(page.events());
		ApiPage.EventDoc event = events.get(place.index());
		events.set(place.index(), new ApiPage.EventDoc(event.name(), event.description(),
				change.apply(Objects.requireNonNull(event.payload())), event.returns()));
		pages.set(place.page(), new ApiPage(page.path(), page.types(), page.globals(), page.enums(), events,
				page.otherNames(), page.described()));
	}

	private static boolean removeMember(List<ApiPage> pages, String path) {
		Member member = member(pages, path);
		if (member == null) {
			return false;
		}
		boolean[] found = {false};
		changeType(pages, member.type(), type -> {
			List<ApiPage.PropertyDoc> properties = type.properties().stream()
					.filter(property -> !property.name().equals(member.name())).toList();
			List<ApiPage.FunctionDoc> functions = type.functions().stream()
					.filter(function -> !function.name().equals(member.name())).toList();
			found[0] = properties.size() < type.properties().size() || functions.size() < type.functions().size();
			return new ApiPage.TypeDoc(type.name(), type.generics(), type.parent(), type.description(), functions,
					properties, type.indexes(), type.global());
		});
		return found[0];
	}

	/**
	 * A field of a type or of a payload: another type for one it has, or a field it lacks.
	 *
	 * @param description the field's description, replacing the docs'; empty to keep theirs
	 */
	private static boolean field(List<ApiPage> pages, String path, String type, String description) {
		Member member = member(pages, path);
		if (member == null) {
			return false;
		}
		UnaryOperator<List<ApiPage.PropertyDoc>> change = list -> {
			List<ApiPage.PropertyDoc> properties = new ArrayList<>();
			boolean known = false;
			for (ApiPage.PropertyDoc property : list) {
				if (property.name().equals(member.name())) {
					known = true;
					properties.add(new ApiPage.PropertyDoc(property.name(), lua(type),
							description.isEmpty() ? property.description() : description));
				} else {
					properties.add(property);
				}
			}
			if (!known) {
				properties.add(new ApiPage.PropertyDoc(member.name(), lua(type), description));
			}
			return properties;
		};
		changeType(pages, member.type(), each -> new ApiPage.TypeDoc(each.name(), each.generics(), each.parent(),
				each.description(), each.functions(), change.apply(each.properties()), each.indexes(),
				each.global()));
		changePayload(pages, member.type(), change);
		return true;
	}

	/**
	 * A function's form: the first line for a function replaces its forms, or adds it; a further line adds one.
	 *
	 * @param description the function's description, replacing the docs'; empty to keep theirs
	 */
	private static boolean function(List<ApiPage> pages, String path, String fun, String description,
			Set<String> replaced) {
		boolean colon = path.contains(":");
		Member member = member(pages, path);
		if (member == null) {
			return false;
		}
		Signature form = signature(member.type(), member.name(), colon, fun);
		if (form == null) {
			return false;
		}
		boolean first = replaced.add(path);
		boolean[] found = {false};
		changeType(pages, member.type(), type -> {
			List<ApiPage.FunctionDoc> functions = new ArrayList<>();
			for (ApiPage.FunctionDoc function : type.functions()) {
				if (function.name().equals(member.name()) && function.colon() == colon) {
					found[0] = true;
					List<Signature> forms = new ArrayList<>(first ? List.of() : function.forms());
					forms.add(form);
					functions.add(new ApiPage.FunctionDoc(function.name(), colon, forms,
							description.isEmpty() ? function.description() : List.of(description), function.params(),
							first ? List.of() : function.returns(), function.place()));
				} else {
					functions.add(function);
				}
			}
			if (!found[0]) {
				functions.add(new ApiPage.FunctionDoc(member.name(), colon, List.of(form),
						description.isEmpty() ? List.of() : List.of(description), List.of(), List.of(),
						"corrections.txt"));
			}
			return new ApiPage.TypeDoc(type.name(), type.generics(), type.parent(), type.description(), functions,
					type.properties(), type.indexes(), type.global());
		});
		return true;
	}

	/** A form from a LuaCATS function type, {@code fun(staticID: integer): ui.Component?}; null for another type. */
	static @Nullable Signature signature(String owner, String name, boolean colon, String fun) {
		Matcher matcher = FUN.matcher(fun.strip());
		if (!matcher.matches()) {
			return null;
		}
		List<Signature.Param> params = new ArrayList<>();
		for (String part : Signature.splitTopLevel(matcher.group(1), ',')) {
			Matcher param = FUN_PARAM.matcher(part);
			if (!param.matches()) {
				return null;
			}
			String type = lua(param.group(3).strip());
			params.add(new Signature.Param(param.group(1), param.group(2) != null ? type + "?" : type));
		}
		String returns = matcher.group(2);
		List<String> values = returns == null ? List.of()
				: Signature.splitTopLevel(returns, ',').stream().map(Corrections::lua).toList();
		return new Signature(owner, name, colon, params, values);
	}

	private static boolean values(List<ApiPage> pages, String name, String assignments) {
		Map<String, String> given = new LinkedHashMap<>();
		for (String assignment : SPACES.splitAsStream(assignments).toList()) {
			int equals = assignment.indexOf('=');
			given.put(assignment.substring(0, equals), assignment.substring(equals + 1));
		}
		// Each name the line gives must be one of the enumeration's
		boolean[] known = {false};
		boolean found = changeEnum(pages, name, enumeration -> {
			known[0] = given.keySet().stream().allMatch(assigned -> enumeration.values().stream()
					.anyMatch(value -> value.name().equals(assigned)));
			if (!known[0]) {
				return enumeration;
			}
			List<ApiPage.EnumValue> values = new ArrayList<>();
			for (ApiPage.EnumValue value : enumeration.values()) {
				values.add(new ApiPage.EnumValue(value.name(), given.getOrDefault(value.name(), value.value()),
						value.description()));
			}
			return new ApiPage.EnumDoc(enumeration.name(), enumeration.description(), values);
		});
		return found && known[0];
	}

	/** Renames a value of an enumeration, {@code PluginResourceLoadResult.success}; false for none. */
	private static boolean renameValue(List<ApiPage> pages, String path, String name) {
		int dot = path.lastIndexOf('.');
		if (dot < 0) {
			return false;
		}
		String written = path.substring(dot + 1);
		boolean[] found = {false};
		changeEnum(pages, path.substring(0, dot), enumeration -> new ApiPage.EnumDoc(enumeration.name(),
				enumeration.description(), enumeration.values().stream().map(value -> {
					if (!value.name().equals(written)) {
						return value;
					}
					found[0] = true;
					return new ApiPage.EnumValue(name, value.value(), value.description());
				}).toList()));
		return found[0];
	}

	private static boolean changeEnum(List<ApiPage> pages, String name, UnaryOperator<ApiPage.EnumDoc> change) {
		for (int page = 0; page < pages.size(); page++) {
			ApiPage each = pages.get(page);
			List<ApiPage.EnumDoc> enums = new ArrayList<>(each.enums());
			for (int index = 0; index < enums.size(); index++) {
				if (enums.get(index).name().equals(name)) {
					enums.set(index, change.apply(enums.get(index)));
					pages.set(page, new ApiPage(each.path(), each.types(), each.globals(), enums, each.events(),
							each.otherNames(), each.described()));
					return true;
				}
			}
		}
		return false;
	}

	/** A note on a class, an enumeration, or a member. */
	private static boolean note(List<ApiPage> pages, String path, String text) {
		String sentence = "In the client: " + text;
		if (changeType(pages, path, type -> new ApiPage.TypeDoc(type.name(), type.generics(), type.parent(),
				append(type.description(), sentence), type.functions(), type.properties(), type.indexes(),
				type.global()))) {
			return true;
		}
		if (changeEnum(pages, path, enumeration -> new ApiPage.EnumDoc(enumeration.name(),
				append(enumeration.description(), sentence), enumeration.values()))) {
			return true;
		}
		return changeMember(pages, path,
				property -> new ApiPage.PropertyDoc(property.name(), property.type(),
						(property.description() + " " + sentence).strip()),
				function -> new ApiPage.FunctionDoc(function.name(), function.colon(), function.forms(),
						append(function.description(), sentence), function.params(), function.returns(),
						function.place()));
	}

	/** Marks a field, or each field of a class, as one that cannot be assigned, as the docs mark theirs. */
	private static boolean readOnly(List<ApiPage> pages, String path) {
		UnaryOperator<ApiPage.PropertyDoc> mark = property -> {
			String description = property.description();
			boolean said = description.startsWith("(Read-only)") || description.startsWith("(Writeable");
			return said ? property
					: new ApiPage.PropertyDoc(property.name(), property.type(), ("(Read-only) " + description).strip());
		};
		if (changeType(pages, path, type -> new ApiPage.TypeDoc(type.name(), type.generics(), type.parent(),
				type.description(), type.functions(), type.properties().stream().map(mark).toList(), type.indexes(),
				type.global()))) {
			return true;
		}
		return changeMember(pages, path, mark, function -> function);
	}

	/** A class the docs describe in prose alone, added to a page's file, with its description, if any. */
	private static boolean addClass(List<ApiPage> pages, String name, String path, String description) {
		for (int page = 0; page < pages.size(); page++) {
			ApiPage each = pages.get(page);
			if (each.path().equals(path)) {
				List<ApiPage.TypeDoc> types = new ArrayList<>(each.types());
				types.add(new ApiPage.TypeDoc(name, List.of(), null,
						description.isEmpty() ? List.of() : List.of(description), List.of(), List.of(), List.of(),
						null));
				pages.set(page, new ApiPage(each.path(), types, each.globals(), each.enums(), each.events(),
						each.otherNames(), each.described()));
				return true;
			}
		}
		return false;
	}

	private static ApiPage.TypeDoc withParent(ApiPage.TypeDoc type, String parent) {
		return new ApiPage.TypeDoc(type.name(), type.generics(), parent, type.description(), type.functions(),
				type.properties(), type.indexes(), type.global());
	}

	private static ApiPage.FunctionDoc renamed(ApiPage.FunctionDoc function, String name) {
		return new ApiPage.FunctionDoc(name, function.colon(), function.forms().stream()
				.map(form -> new Signature(form.owner(), name, form.colon(), form.params(), form.returns())).toList(),
				function.description(), function.params(), function.returns(), function.place());
	}

	private static ApiPage.FunctionDoc withReturns(ApiPage.FunctionDoc function, List<String> returns) {
		return new ApiPage.FunctionDoc(function.name(), function.colon(), function.forms().stream()
				.map(form -> new Signature(form.owner(), form.name(), form.colon(), form.params(), returns)).toList(),
				function.description(), function.params(), List.of(), function.place());
	}

	private static ApiPage.FunctionDoc withParam(ApiPage.FunctionDoc function, String name, String type) {
		return new ApiPage.FunctionDoc(function.name(), function.colon(), function.forms().stream()
				.map(form -> new Signature(form.owner(), form.name(), form.colon(), form.params().stream()
						.map(param -> param.name().equals(name) ? new Signature.Param(name, type) : param).toList(),
						form.returns())).toList(),
				function.description(), function.params().stream()
						.map(param -> param.name().equals(name)
								? new ApiPage.ParamDoc(name, type, !type.endsWith("?"), param.description()) : param)
						.toList(),
				function.returns(), function.place());
	}

	private static List<String> append(List<String> description, String sentence) {
		List<String> appended = new ArrayList<>(description);
		appended.add(sentence);
		return appended;
	}

	/** A LuaCATS type, marked for the writer, which writes it as it is. */
	private static String lua(String type) {
		return DocType.LUA + type.strip();
	}

	/** A type, in the docs' form or marked as LuaCATS, that may be nil. */
	private static String optional(String type) {
		if (type.startsWith(DocType.LUA)) {
			return DocType.LUA + DocType.orNil(type.substring(DocType.LUA.length()));
		}
		return type.endsWith("?") ? type : type + "?";
	}
}
