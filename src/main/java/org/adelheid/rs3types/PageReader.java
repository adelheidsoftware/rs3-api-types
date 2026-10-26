package org.adelheid.rs3types;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jspecify.annotations.Nullable;

/**
 * Reads a page of the docs site into an {@link ApiPage}. A page's article is a tree of headings, each with its blocks:
 * paragraphs, code, tables and notes. The title names the page's type. A heading named Overview, Constructors,
 * Methods, Functions, Properties or Enumerations is a section of the type it is under; any other is read by what it
 * holds: signatures make it a function, a table of payload fields an event, sections or a table of properties a type
 * of its own, a table of values an enumeration, and otherwise it is prose about its type, whose headings are read in
 * its place. What the reader does not understand, it reports with the page and the heading's anchor, and leaves out.
 */
final class PageReader {

	private static final Pattern HEADING = Pattern.compile("h[1-6]");
	private static final Pattern TYPE_NAME = Pattern.compile("[A-Za-z_][\\w.]*");
	private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_]\\w*");
	private static final Pattern NEWLINE = Pattern.compile("\\R");
	private static final Pattern SPACES = Pattern.compile("\\s+");
	/** The overview's line of inheritance, {@code ui.Component -> ui.Text}. */
	private static final Pattern INHERITANCE = Pattern.compile("[\\w.]+(\\s*->\\s*[\\w.]+)+");
	private static final Pattern ARROW = Pattern.compile("\\s*->\\s*");
	/** A name within a type, {@code [ NPCData ]} names {@code NPCData}. */
	private static final Pattern NAMES = Pattern.compile("[A-Za-z_][\\w.]*");
	/** A heading for a section of a type, {@code MiniMenuEntry Properties}. */
	private static final Pattern TYPED_SECTION = Pattern.compile("([A-Za-z_][\\w.]*) (\\w+)");
	/** A placeholder in a signature's owner, {@code <T>} or {@code <option name>}. */
	private static final Pattern PLACEHOLDER = Pattern.compile("<([^>]*)>");
	/** An item of a list of aliases, {@code r/g/b -> x/y/z}. */
	private static final Pattern ALIAS = Pattern.compile("(\\S+)\\s*->\\s*(\\S+)");
	private static final Pattern SLASH = Pattern.compile("/");
	/** The mark of a field that cannot be assigned at the start of its description, however the docs capitalise it. */
	private static final Pattern READ_ONLY_MARK = Pattern.compile("^\\((?i:read-only)\\)");
	/** A type's name alone, with no sentence about it. */
	private static final Pattern TYPE_ALONE = Pattern.compile("[A-Z]\\w*(\\.\\w+)*");
	/** The note of a Properties section whose table lists no field that can be assigned. */
	private static final String ALL_READ_ONLY = "All properties are read-only.";
	/** The note of a Properties section that lists other names of the table's fields. */
	private static final String ALIASES = "The following aliases are supported:";

	/** The sections the reader knows, by their headings. */
	private enum Section {
		OVERVIEW, EXAMPLES, CONSTRUCTORS, METHODS, FUNCTIONS, PROPERTIES, ENUMERATIONS;

		static @Nullable Section named(String heading) {
			return switch (heading.toLowerCase(Locale.ROOT)) {
				case "overview" -> OVERVIEW;
				case "example", "examples" -> EXAMPLES;
				case "constructor", "constructors" -> CONSTRUCTORS;
				case "method", "methods" -> METHODS;
				case "function", "functions" -> FUNCTIONS;
				case "properties" -> PROPERTIES;
				case "enumerations" -> ENUMERATIONS;
				default -> null;
			};
		}
	}

	/** A heading, its own blocks up to the next heading, and the headings under it. */
	private static final class Heading {

		final int level;
		final String text;
		final String anchor;
		final List<Element> blocks = new ArrayList<>();
		final List<Heading> children = new ArrayList<>();

		Heading(int level, String text, String anchor) {
			this.level = level;
			this.text = text;
			this.anchor = anchor;
		}
	}

	/** A type while its page is read; named once all of it has been, as its signatures may name it. */
	private static final class TypeBuilder {

		final String heading;
		final String anchor;
		@Nullable String parent;
		final List<String> description = new ArrayList<>();
		final List<ApiPage.FunctionDoc> functions = new ArrayList<>();
		final List<ApiPage.PropertyDoc> properties = new ArrayList<>();
		final List<ApiPage.IndexDoc> indexes = new ArrayList<>();
		/** The owners its signatures name, which may name it better than its heading. */
		final Set<String> owners = new LinkedHashSet<>();
		/** Set where the page says whether its table is a global, rather than its functions. */
		@Nullable Boolean global;

		TypeBuilder(String heading, String anchor) {
			this.heading = heading;
			this.anchor = anchor;
		}

		boolean isEmpty() {
			return functions.isEmpty() && properties.isEmpty() && indexes.isEmpty();
		}
	}

	private final String path;
	private final Facts facts;
	private final List<String> reports;
	private final List<TypeBuilder> types = new ArrayList<>();
	/** The page's global functions, {@code log}, which belong to no type. */
	private final TypeBuilder globals = new TypeBuilder("", "");
	private final List<ApiPage.EnumDoc> enums = new ArrayList<>();
	private final List<ApiPage.EventDoc> events = new ArrayList<>();
	private final Map<String, List<String>> described = new LinkedHashMap<>();
	/** The names the page uses as types, in its headings and its tables' Type and Data Property columns. */
	private final Set<String> typeNames = new LinkedHashSet<>();
	private String anchor = "";

	private PageReader(String path, Facts facts, List<String> reports) {
		this.path = path;
		this.facts = facts;
		this.reports = reports;
	}

	/**
	 * The page's API.
	 *
	 * @param path the page's path on the site, which the reports and the facts name it by
	 * @param reports where what the reader does not understand is added, each as {@code <path>#<anchor>: <what>}
	 */
	static ApiPage read(String path, String html, Facts facts, List<String> reports) {
		return new PageReader(path, facts, reports).read(Jsoup.parse(html));
	}

	private ApiPage read(Document document) {
		Element article = document.selectFirst("article");
		Heading title = article == null ? null : tree(article);
		if (title == null) {
			report("the page has no title");
			return new ApiPage(path, List.of(), List.of(), List.of(), List.of(), Map.of(), Map.of());
		}
		collectTypeNames(title);
		TypeBuilder page = type(title);
		children(title, page);
		return finish();
	}

	/** The article's headings as a tree under its title; null for an article without one. */
	private static @Nullable Heading tree(Element article) {
		article.select("a.headerlink").remove();
		footnotes(article);
		List<Heading> open = new ArrayList<>();
		place(article, open);
		return open.isEmpty() ? null : open.get(0);
	}

	/**
	 * Writes each footnote into the text that refers to it, in place of its number, and removes the list of footnotes
	 * at the end of the article, as the definition files have no place for them.
	 */
	private static void footnotes(Element article) {
		for (Element link : article.select("a.footnote-ref")) {
			Element footnote = article.getElementById(link.attr("href").replaceFirst("^#", ""));
			Element parent = link.parent();
			Element reference = parent != null && parent.tagName().equals("sup") ? parent : link;
			reference.replaceWith(new TextNode(footnote == null ? "" : " " + footnoteText(footnote)));
		}
		article.select("div.footnote").remove();
	}

	/** A footnote's text: its paragraphs, and the items of its lists between semicolons, without its links back. */
	private static String footnoteText(Element footnote) {
		Element copy = footnote.clone();
		copy.select("a.footnote-backref").remove();
		List<String> parts = new ArrayList<>();
		for (Element block : copy.children()) {
			if (block.tagName().equals("ul") || block.tagName().equals("ol")) {
				parts.add(String.join("; ", block.children().stream().map(PageReader::text).toList()) + ".");
			} else {
				parts.add(text(block));
			}
		}
		// The docs end a footnote with a non-breaking space before its links back
		return SPACES.matcher(String.join(" ", parts).replace('\u00a0', ' ')).replaceAll(" ").strip();
	}

	/**
	 * Places an element's blocks in the tree: a heading under the open heading of a lower level, any other block in
	 * the innermost open heading. A collapsible block, which the docs group a category in, as the hooks of
	 * {@code ui.Hook} are, is a scope of its own: a heading in it closes with it, and its other blocks are its
	 * heading's. The first open heading is the title.
	 */
	private static void place(Element parent, List<Heading> open) {
		List<Element> blocks = new ArrayList<>();
		collectBlocks(parent, blocks);
		for (Element block : blocks) {
			if (HEADING.matcher(block.tagName()).matches()) {
				Heading heading = new Heading(block.tagName().charAt(1) - '0', text(block), block.id());
				while (open.size() > 1 && open.get(open.size() - 1).level >= heading.level) {
					open.remove(open.size() - 1);
				}
				if (open.isEmpty()) {
					open.add(heading);
				} else if (open.size() > 1 || heading.level > open.get(0).level) {
					open.get(open.size() - 1).children.add(heading);
					open.add(heading);
				}
			} else if (block.tagName().equals("details") && !isExample(block)) {
				int depth = open.size();
				place(block, open);
				while (open.size() > depth) {
					open.remove(open.size() - 1);
				}
			} else if (!open.isEmpty()) {
				open.get(open.size() - 1).blocks.add(block);
			}
		}
	}

	/** Gathers the names a heading and those under it use as types, in their headings and their tables. */
	private void collectTypeNames(Heading heading) {
		if (TYPE_NAME.matcher(heading.text).matches()) {
			typeNames.add(heading.text);
		}
		for (Element block : heading.blocks) {
			if (block.tagName().equals("table")) {
				List<String> headers = headers(block);
				for (List<String> row : rows(block)) {
					for (int column = 0; column < headers.size(); column++) {
						if (headers.get(column).equals("type") || headers.get(column).equals("data property")) {
							NAMES.matcher(row.get(column)).results().forEach(name -> typeNames.add(name.group()));
						}
					}
				}
			}
		}
		heading.children.forEach(this::collectTypeNames);
	}

	/** The blocks of an element in document order: headings, paragraphs, code, tables, lists and notes. */
	private static void collectBlocks(Element parent, List<Element> blocks) {
		for (Element child : parent.children()) {
			String tag = child.tagName();
			boolean block = HEADING.matcher(tag).matches() || tag.equals("p") || tag.equals("table")
					|| tag.equals("ul") || tag.equals("ol") || tag.equals("pre") || tag.equals("details")
					|| child.hasClass("admonition") || child.hasClass("highlight");
			if (block) {
				blocks.add(child);
			} else {
				collectBlocks(child, blocks);
			}
		}
	}

	private TypeBuilder type(Heading heading) {
		TypeBuilder type = new TypeBuilder(heading.text, heading.anchor);
		types.add(type);
		return type;
	}

	private void children(Heading parent, TypeBuilder type) {
		for (Heading child : parent.children) {
			heading(child, type);
		}
	}

	/** A heading under a type, read by its name where it names a section, and otherwise by what it holds. */
	private void heading(Heading heading, TypeBuilder type) {
		anchor = heading.anchor;
		if (facts.skips(place())) {
			return;
		}
		Section section = Section.named(heading.text);
		Matcher typed = TYPED_SECTION.matcher(heading.text);
		if (section != null) {
			section(heading, section, type);
		} else if (typed.matches() && Section.named(typed.group(2)) != null
				&& (facts.name(place()) != null || belongsTo(typed.group(1), type)
						|| typeNames.contains(typed.group(1)))) {
			// A section named for its type, MiniMenuEntry Properties, which the page uses as one, or a name fact
			// names; Visual Properties is a group of its own type's properties
			String target = facts.name(place());
			section(heading, Objects.requireNonNull(Section.named(typed.group(2))),
					typeNamed(target != null ? target : typed.group(1), type));
		} else if (hasSignatures(heading.blocks)) {
			function(heading, type);
		} else if (isIndex(heading)) {
			index(heading, type);
		} else if (heading.children.stream().anyMatch(child -> hasTable(child.blocks, PageReader::isPayloadTable))) {
			events(heading);
		} else if (isType(heading, type) || facts.name(place()) != null) {
			TypeBuilder sub = type(heading);
			describe(heading, sub, null);
			children(heading, sub);
		} else if (TYPE_NAME.matcher(heading.text).matches() && hasTable(heading.blocks, PageReader::isEnumTable)) {
			// An enumeration is named like a type; a table of values under prose, Plugin Permissions, is prose
			enumeration(heading);
			children(heading, type);
		} else {
			// Prose about the type, which may group more of its members under headings of its own; one named like a
			// type describes that type, which other tables may name
			if (TYPE_NAME.matcher(heading.text).matches()) {
				TypeBuilder prose = new TypeBuilder(heading.text, heading.anchor);
				describe(heading, prose, null);
				described.put(heading.text, prose.description);
			}
			describe(heading, type, heading.text);
			children(heading, type);
		}
	}

	/** The type a section named for it is about: the type it is under, or another, which it begins. */
	private TypeBuilder typeNamed(String name, TypeBuilder current) {
		if (belongsTo(name, current)) {
			return current;
		}
		for (TypeBuilder type : types) {
			if (type.heading.equals(name)) {
				return type;
			}
		}
		TypeBuilder created = new TypeBuilder(name, anchor);
		types.add(created);
		return created;
	}

	private void section(Heading heading, Section section, TypeBuilder type) {
		switch (section) {
			case OVERVIEW -> {
				describe(heading, type, null);
				children(heading, type);
			}
			case EXAMPLES -> {
				// Examples are for people; the stubs leave them out
			}
			case CONSTRUCTORS, METHODS, FUNCTIONS -> {
				// A section may hold its signatures itself, as Vector2's Constructors does, or a heading for each
				if (hasSignatures(heading.blocks)) {
					function(heading, type);
				}
				for (Heading child : heading.children) {
					anchor = child.anchor;
					if (facts.skips(place())) {
						continue;
					}
					if (hasSignatures(child.blocks) || facts.signature(place()) != null) {
						function(child, type);
					} else if (isIndex(child)) {
						index(child, type);
					} else if (child.children.isEmpty()) {
						report("a function without a signature, " + child.text);
					} else {
						heading(child, type);
					}
				}
			}
			case PROPERTIES -> {
				int first = type.properties.size();
				List<Element> notes = new ArrayList<>();
				for (Element block : heading.blocks) {
					if (block.tagName().equals("table")) {
						if (isPropertiesTable(block)) {
							addFields(type, fields(block));
						} else {
							report("a table of properties the reader does not know, with the columns "
									+ headers(block));
						}
					} else if (block.hasClass("admonition")) {
						notes.add(block);
					}
				}
				notes.forEach(note -> propertiesNote(note, type, first));
				children(heading, type);
			}
			case ENUMERATIONS -> {
				for (Heading child : heading.children) {
					anchor = child.anchor;
					if (facts.skips(place())) {
						continue;
					}
					if (hasTable(child.blocks, PageReader::isEnumTable)) {
						enumeration(child);
					} else {
						report("an enumeration without a table of values, " + child.text);
					}
				}
			}
		}
	}

	/**
	 * A heading's description of a type: its paragraphs, its line of inheritance, its notes, the tables of its
	 * properties, and the lists a fact says name a table's fields.
	 *
	 * @param topic for a heading of prose about the type, its heading, which begins its first paragraph; null for the
	 *     type's own overview
	 */
	private void describe(Heading heading, TypeBuilder type, @Nullable String topic) {
		boolean first = true;
		for (Element block : heading.blocks) {
			String tag = block.tagName();
			if (tag.equals("p")) {
				String text = text(block);
				if (topic == null && INHERITANCE.matcher(text).matches()) {
					List<String> chain = List.of(ARROW.split(text));
					type.parent = chain.get(chain.size() - 2);
				} else if (!text.isEmpty()) {
					type.description.add(first && topic != null ? topic + ": " + text : text);
					first = false;
				}
			} else if (tag.equals("table")) {
				table(block, type);
			} else if (tag.equals("ul") || tag.equals("ol")) {
				list(block);
			} else {
				note(block, type.description);
			}
		}
	}

	/**
	 * A table outside a function: a type's properties; a table of terms and their descriptions, which joins the
	 * description; or a table of prose, such as a diagram or a list of advice, which has no types and is left out.
	 */
	private void table(Element table, TypeBuilder type) {
		List<String> headers = headers(table);
		if (isPropertiesTable(table)) {
			addFields(type, fields(table));
		} else if (headers.size() == 2 && headers.get(1).equals("description")) {
			// Terms and their descriptions, such as a list of domains, or of values under prose
			rows(table).forEach(row -> type.description.add(row.get(0) + ": " + row.get(1)));
		} else if (headers.contains("type")) {
			report("a table the reader does not know, with the columns " + headers);
		}
	}

	/**
	 * A list whose items a fact says are the fields of a table, such as the client's options; the collapsible block a
	 * list is in names their category, as Graphics does the client's graphics options.
	 */
	private void list(Element list) {
		List<String> table = facts.list(place());
		if (table == null) {
			return;
		}
		Element details = list.closest("details");
		Element summary = details == null ? null : details.selectFirst("summary");
		String category = summary == null ? "" : text(summary);
		TypeBuilder fields = types.stream().filter(type -> type.heading.equals(table.get(0))).findFirst()
				.orElseGet(() -> {
					TypeBuilder created = new TypeBuilder(table.get(0), anchor);
					created.global = true;
					types.add(created);
					return created;
				});
		for (Element item : list.select("li")) {
			String name = SPACES.splitAsStream(text(item).replace("`", "")).findFirst().orElse("");
			if (IDENTIFIER.matcher(name).matches()) {
				fields.properties.add(new ApiPage.PropertyDoc(name, table.get(1),
						category.isEmpty() ? "" : "One of the " + category + " options."));
			} else {
				report("a list item that names no field of " + table.get(0) + ", " + text(item));
			}
		}
	}

	/** A heading's functions: one for each name its signatures give, all with its description and its tables. */
	private void function(Heading heading, TypeBuilder type) {
		List<String> failures = new ArrayList<>();
		List<Signature> forms = forms(heading.blocks, failures);
		failures.forEach(line -> report("a signature the reader does not understand, " + line));
		String given = facts.signature(place());
		if (given != null) {
			Signature form = Signature.parse(given);
			if (form == null) {
				report("a signature fact the reader does not understand, " + given);
			} else {
				forms = List.of(form);
			}
		}
		String owner = facts.owner(place());
		if (owner != null) {
			forms = forms.stream().map(form -> new Signature(owner, form.name(), form.colon(), form.params(),
					form.returns())).toList();
		}
		// A return table that contradicts the signature, as one copied from another function's, is settled by a fact
		String trusted = facts.trust(place());
		String renamed = facts.rename(place());
		if (renamed != null) {
			forms = forms.stream().map(form -> new Signature(form.owner(), renamed, form.colon(), form.params(),
					form.returns())).toList();
		} else {
			checkName(heading, forms);
		}
		List<String> description = new ArrayList<>();
		List<ApiPage.ParamDoc> params = new ArrayList<>();
		List<ApiPage.ReturnDoc> returns = new ArrayList<>();
		for (Element block : heading.blocks) {
			String tag = block.tagName();
			if (tag.equals("p")) {
				String text = text(block);
				if (!text.isEmpty()) {
					description.add(text);
				}
			} else if (tag.equals("table")) {
				forms = functionTable(block, forms, params, returns);
			} else {
				note(block, description);
			}
		}
		if ("table".equals(trusted)) {
			List<String> types = returns.stream().map(ApiPage.ReturnDoc::type).toList();
			forms = forms.stream().map(form -> new Signature(form.owner(), form.name(), form.colon(), form.params(),
					types)).toList();
		} else if ("signature".equals(trusted)) {
			returns.clear();
		}
		Map<String, List<Signature>> byName = new LinkedHashMap<>();
		for (Signature form : forms) {
			byName.computeIfAbsent(form.name(), name -> new ArrayList<>()).add(form);
			if (!form.owner().isEmpty()) {
				type.owners.add(form.owner());
			}
		}
		boolean first = true;
		for (Map.Entry<String, List<Signature>> function : byName.entrySet()) {
			List<Signature> named = function.getValue();
			// The table of parameters describes them by name, for every function of the heading; the table of return
			// values describes the first
			// A function without an owner is a global, log, of no type
			addFunction(named.get(0).owner().isEmpty() ? globals : type, new ApiPage.FunctionDoc(function.getKey(),
					named.get(0).colon(), named, description, params, first ? returns : List.of(), place()));
			first = false;
		}
		children(heading, type);
	}

	/**
	 * Reports a heading that names a function its signatures do not, as when the docs copy a signature from the
	 * heading above; a {@code rename} fact gives the right name.
	 */
	private void checkName(Heading heading, List<Signature> forms) {
		int parenthesis = heading.text.indexOf('(');
		String written = (parenthesis < 0 ? heading.text : heading.text.substring(0, parenthesis)).strip();
		String base = written.substring(written.lastIndexOf('.') + 1);
		boolean constructor = forms.stream().allMatch(form -> form.name().equals("new"))
				|| base.equalsIgnoreCase("constructor") || base.equalsIgnoreCase("constructors");
		if (IDENTIFIER.matcher(base).matches() && !constructor
				&& forms.stream().noneMatch(form -> form.name().equals(heading.text) || form.name().equals(base))) {
			report("the heading " + heading.text + " documents a function its signatures call "
					+ forms.get(0).name() + "; a rename fact says which is right");
		}
	}

	/** A table under a function's signatures: its parameters, its return values, or the keys of a table it takes. */
	private List<Signature> functionTable(Element table, List<Signature> forms, List<ApiPage.ParamDoc> params,
			List<ApiPage.ReturnDoc> returns) {
		List<String> headers = headers(table);
		String first = headers.isEmpty() ? "" : headers.get(0);
		if (first.equals("returns") || first.equals("getter returns")) {
			for (List<String> row : rows(table)) {
				returns.add(new ApiPage.ReturnDoc(row.get(0), row.size() > 1 ? row.get(1) : ""));
			}
		} else if (first.equals("key")) {
			return settings(table, forms);
		} else if ((first.equals("parameter") || first.equals("name")) && headers.contains("type")) {
			int type = headers.indexOf("type");
			int required = headers.indexOf("required");
			int description = headers.indexOf("description");
			for (List<String> row : rows(table)) {
				params.add(new ApiPage.ParamDoc(row.get(0), row.get(type),
						required < 0 || !row.get(required).equalsIgnoreCase("no"),
						description < 0 ? "" : row.get(description)));
			}
		} else {
			report("a table the reader does not know, with the columns " + headers);
		}
		return forms;
	}

	/**
	 * The keys a function's table parameter takes, {@code NPCList.Filter}'s {@code filterSet}, as a class of their
	 * own, {@code NPCListFilterSettings}, which the parameter is then typed as. Each key may be left out, as a filter
	 * takes any of its keys and a key with a default its default.
	 */
	private List<Signature> settings(Element table, List<Signature> forms) {
		Signature form = forms.get(0);
		String name = form.owner().replace(".", "") + form.name() + "Settings";
		TypeBuilder settings = new TypeBuilder(name, anchor);
		settings.description.add("The keys `" + form.owner() + "." + form.name() + "` takes in its table.");
		for (ApiPage.PropertyDoc key : fields(table)) {
			String type = key.type().endsWith("?") ? key.type() : key.type() + "?";
			settings.properties.add(new ApiPage.PropertyDoc(key.name(), type, key.description()));
		}
		types.add(settings);
		List<Signature> typed = new ArrayList<>();
		for (Signature each : forms) {
			List<Signature.Param> params = new ArrayList<>();
			for (Signature.Param param : each.params()) {
				boolean isTable = param.type().equalsIgnoreCase("table") || param.type().equalsIgnoreCase("table?");
				params.add(isTable ? new Signature.Param(param.name(), param.type().endsWith("?") ? name + "?" : name)
						: param);
			}
			typed.add(new Signature(each.owner(), each.name(), each.colon(), params, each.returns()));
		}
		return typed;
	}

	/**
	 * A note of a Properties section about the fields of its table, before or after it: one that says all of them are
	 * read-only marks each so, unless the table says otherwise; one that lists aliases, {@code r/g/b -> x/y/z}, adds a
	 * field for each alias the table does not list, as the field it stands for. The section's other prose is left out.
	 *
	 * @param first the index among the type's fields of the section's first
	 */
	private void propertiesNote(Element note, TypeBuilder type, int first) {
		String text = String.join(" ", note.select("p").stream()
				.filter(paragraph -> !paragraph.hasClass("admonition-title"))
				.map(PageReader::text)
				.toList());
		if (text.equals(ALL_READ_ONLY)) {
			for (int i = first; i < type.properties.size(); i++) {
				ApiPage.PropertyDoc field = type.properties.get(i);
				String description = field.description();
				if (!description.startsWith("(Read-only)") && !description.startsWith("(Writeable")) {
					type.properties.set(i, new ApiPage.PropertyDoc(field.name(), field.type(),
							("(Read-only) " + description).strip()));
				}
			}
		} else if (text.equals(ALIASES)) {
			for (Element item : note.select("li")) {
				Matcher alias = ALIAS.matcher(text(item));
				List<String> names = alias.matches() ? SLASH.splitAsStream(alias.group(1)).toList() : List.of();
				List<String> targets = alias.matches() ? SLASH.splitAsStream(alias.group(2)).toList() : List.of();
				if (names.isEmpty() || names.size() != targets.size()) {
					report("a list of aliases the reader does not understand, " + text(item));
					continue;
				}
				for (int i = 0; i < names.size(); i++) {
					addAlias(type, names.get(i), targets.get(i));
				}
			}
		}
	}

	/** Adds a field that aliases another, unless the table lists it, as Vector2's lists u and v. */
	private void addAlias(TypeBuilder type, String name, String target) {
		if (type.properties.stream().anyMatch(field -> field.name().equals(name))) {
			return;
		}
		ApiPage.PropertyDoc aliased = type.properties.stream()
				.filter(field -> field.name().equals(target))
				.findFirst()
				.orElse(null);
		if (aliased == null) {
			report("an alias " + name + " of " + target + ", a field the type does not have");
			return;
		}
		String description = aliased.description();
		String stated = description.endsWith(".") ? description.substring(0, description.length() - 1) : description;
		type.properties.add(new ApiPage.PropertyDoc(name, aliased.type(),
				stated.isEmpty() ? "An alias of " + target + "." : stated + ", aliasing " + target + "."));
	}

	/**
	 * Adds a table's fields to a type. A name the type has already is a field listed twice, as the docs do by mistake;
	 * a {@code second} fact gives the second its own name.
	 */
	private void addFields(TypeBuilder type, List<ApiPage.PropertyDoc> fields) {
		for (ApiPage.PropertyDoc field : fields) {
			ApiPage.PropertyDoc added = field;
			if (type.properties.stream().anyMatch(known -> known.name().equals(field.name()))) {
				String second = facts.second(place(), field.name());
				if (second == null) {
					report("a table that lists the field " + field.name() + " twice; a second fact names the second");
					continue;
				}
				added = new ApiPage.PropertyDoc(second, field.type(), field.description());
			}
			type.properties.add(added);
		}
	}

	/** Adds a function, or its forms to the function of its name that an earlier heading began. */
	private static void addFunction(TypeBuilder type, ApiPage.FunctionDoc function) {
		for (int i = 0; i < type.functions.size(); i++) {
			ApiPage.FunctionDoc earlier = type.functions.get(i);
			if (earlier.name().equals(function.name()) && earlier.colon() == function.colon()) {
				List<Signature> forms = new ArrayList<>(earlier.forms());
				forms.addAll(function.forms());
				List<ApiPage.ParamDoc> params = new ArrayList<>(earlier.params());
				function.params().stream()
						.filter(param -> params.stream().noneMatch(known -> known.name().equals(param.name())))
						.forEach(params::add);
				type.functions.set(i, new ApiPage.FunctionDoc(earlier.name(), earlier.colon(), forms,
						earlier.description(), params, earlier.returns(), earlier.place()));
				return;
			}
		}
		type.functions.add(function);
	}

	/**
	 * Whether a heading documents indexing its type, as {@code ui.StyleSheets[id]} does: no signature, but a key
	 * among its parameters and a value among its return values.
	 */
	private static boolean isIndex(Heading heading) {
		List<Element> tables = heading.blocks.stream().filter(block -> block.tagName().equals("table")).toList();
		// Indexing that also sets, style[property] = value, lists the value as a second parameter
		boolean key = tables.stream().anyMatch(table -> headers(table).equals(List.of("parameter", "type",
				"description")) && (rows(table).size() == 1 || rows(table).size() == 2));
		boolean value = tables.stream().anyMatch(table -> !headers(table).isEmpty()
				&& List.of("returns", "getter returns").contains(headers(table).get(0)));
		return key && value;
	}

	private void index(Heading heading, TypeBuilder type) {
		String key = "";
		String value = "";
		String description = "";
		for (Element block : heading.blocks) {
			if (block.tagName().equals("table")) {
				List<String> headers = headers(block);
				List<String> row = rows(block).get(0);
				if (headers.get(0).equals("parameter")) {
					key = row.get(1);
				} else {
					value = row.get(0);
				}
			} else if (block.tagName().equals("p") && description.isEmpty()) {
				description = text(block);
			}
		}
		type.indexes.add(new ApiPage.IndexDoc(key, value, description));
	}

	/** Each heading under a heading of events, such as Supported events, is an event, with its payload's fields. */
	private void events(Heading heading) {
		for (Heading event : heading.children) {
			anchor = event.anchor;
			List<String> description = new ArrayList<>();
			List<ApiPage.PropertyDoc> payload = null;
			for (Element block : event.blocks) {
				String tag = block.tagName();
				if (tag.equals("p")) {
					String text = text(block);
					if (!text.isEmpty()) {
						description.add(text);
					}
				} else if (tag.equals("table") && isPayloadTable(block)) {
					payload = fields(block);
				} else if (tag.equals("table")) {
					report("a table the reader does not know, with the columns " + headers(block));
				} else {
					note(block, description);
				}
			}
			events.add(new ApiPage.EventDoc(event.text, description, payload, facts.returns(place())));
		}
	}

	/**
	 * Whether a heading documents a type of its own: one named like a type that holds sections or a table of
	 * properties, or one with another name whose sections' signatures name a type other than the one it is under.
	 */
	private static boolean isType(Heading heading, TypeBuilder current) {
		boolean sections = heading.children.stream().anyMatch(child -> {
			Section section = Section.named(child.text);
			return section != null && section != Section.OVERVIEW && section != Section.EXAMPLES;
		});
		Set<String> owners = new LinkedHashSet<>();
		collectOwners(heading, owners);
		if (TYPE_NAME.matcher(heading.text).matches()) {
			// ui.Interfaces, a type of its own beside the page's, whose functions name it
			return sections || hasTable(heading.blocks, PageReader::isPropertiesTable) || owners.contains(heading.text);
		}
		return sections && !owners.isEmpty() && owners.stream().noneMatch(owner -> belongsTo(owner, current));
	}

	private static void collectOwners(Heading heading, Set<String> owners) {
		forms(heading.blocks).forEach(form -> owners.add(form.owner()));
		heading.children.forEach(child -> collectOwners(child, owners));
	}

	/** Whether a signature's owner names a type: {@code Component} names {@code ui.Component}. */
	private static boolean belongsTo(String owner, TypeBuilder type) {
		return owner.equals(type.heading) || type.heading.endsWith("." + owner) || owner.equalsIgnoreCase(type.heading)
				|| type.owners.contains(owner);
	}

	/** An enumeration: a heading with tables of values, by name and value, by name alone, or of hooks. */
	private void enumeration(Heading heading) {
		List<String> description = new ArrayList<>();
		List<ApiPage.EnumValue> values = new ArrayList<>();
		for (Element block : heading.blocks) {
			String tag = block.tagName();
			if (tag.equals("p")) {
				String text = text(block);
				if (!text.isEmpty()) {
					description.add(text);
				}
			} else if (tag.equals("table") && isEnumTable(block)) {
				for (ApiPage.EnumValue value : enumValues(block)) {
					addValue(values, value);
				}
			} else if (tag.equals("table")) {
				report("an enumeration's table the reader does not know, with the columns " + headers(block));
			} else {
				note(block, description);
			}
		}
		enums.add(new ApiPage.EnumDoc(heading.text, description, values));
	}

	/**
	 * Adds an enumeration's value, or, for a name it has already, the value's description to the first's: a hook, which
	 * the docs list under each kind of component that fires it, with the parameters each gives its callbacks, is one
	 * value.
	 */
	private static void addValue(List<ApiPage.EnumValue> values, ApiPage.EnumValue value) {
		for (int i = 0; i < values.size(); i++) {
			ApiPage.EnumValue known = values.get(i);
			if (known.name().equals(value.name())) {
				String description = known.description().isEmpty() ? value.description()
						: value.description().isEmpty() ? known.description()
						: known.description() + " " + value.description();
				values.set(i, new ApiPage.EnumValue(known.name(), known.value(), description));
				return;
			}
		}
		values.add(value);
	}

	/**
	 * The values of an enumeration's table. Its first column names them, but where it is called Value and the table
	 * has no other, as the docs list names alone; a value's further lines, and a hook's parameters, join its
	 * description.
	 */
	private static List<ApiPage.EnumValue> enumValues(Element table) {
		List<String> headers = headers(table);
		int value = headers.indexOf("value");
		boolean namesAlone = value == 0;
		int description = headers.indexOf("description");
		int parameters = headers.indexOf("parameters");
		int dataType = headers.indexOf("value data type");
		List<String> labels = table.select("thead th, thead td").stream().map(PageReader::text).toList();
		List<ApiPage.EnumValue> values = new ArrayList<>();
		for (List<List<String>> row : cellLines(table)) {
			List<String> name = row.get(0);
			List<String> text = new ArrayList<>(name.subList(1, name.size()));
			if (description >= 0) {
				text.add(String.join(" ", row.get(description)));
			}
			if (parameters >= 0) {
				text.add("Its callbacks take " + String.join(", ", row.get(parameters)) + ".");
			}
			if (dataType >= 0) {
				text.add("Its values are " + String.join(" ", row.get(dataType)) + ".");
			}
			for (int column = 1; column < headers.size(); column++) {
				if (column != value && column != description && column != parameters && column != dataType
						&& !row.get(column).isEmpty()) {
					// A further column, as MiniMenuActionType's MiniMenuEntryType and Data Property
					text.add(labels.get(column) + ": " + String.join(" ", row.get(column)) + ".");
				}
			}
			String number = namesAlone || value < 0 ? null : String.join(" ", row.get(value));
			values.add(new ApiPage.EnumValue(name.isEmpty() ? "" : name.get(0), number,
					String.join(" ", text.stream().filter(line -> !line.isBlank()).toList())));
		}
		return values;
	}

	/**
	 * The fields of a table of properties, payload variables or keys. A row may name several, a line each, with a type
	 * a line each; whether a field may be written, and its default, join its description.
	 */
	private List<ApiPage.PropertyDoc> fields(Element table) {
		List<String> headers = headers(table);
		int type = headers.indexOf("type");
		int writeable = headers.indexOf("writeable");
		int defaultValue = headers.indexOf("default value");
		int description = headers.indexOf("description");
		List<ApiPage.PropertyDoc> fields = new ArrayList<>();
		for (List<List<String>> row : cellLines(table)) {
			List<String> names = row.get(0);
			List<String> types = type < 0 ? List.of() : row.get(type);
			String written = description < 0 ? "" : String.join(" ", row.get(description));
			// ui.Component's table spells the mark (Read-Only)
			StringBuilder text = new StringBuilder(READ_ONLY_MARK.matcher(written).replaceFirst("(Read-only)"));
			if (writeable >= 0) {
				String writes = String.join(" ", row.get(writeable));
				boolean said = text.toString().startsWith("(Read-only)") || text.toString().startsWith("(Writeable");
				if (writes.equalsIgnoreCase("no") && !said) {
					text.insert(0, "(Read-only) ");
				} else if (!writes.isEmpty() && !writes.equalsIgnoreCase("no") && !said) {
					text.insert(0, writes.equalsIgnoreCase("yes") ? "(Writeable) " : "(Writeable: " + writes + ") ");
				}
			}
			String given = defaultValue < 0 ? "" : String.join(" ", row.get(defaultValue));
			// A dash is no default, as a key that must be given, or a field the client sets
			if (!given.isEmpty() && !given.equals("-") && !given.equals("`-`")) {
				text.append(text.isEmpty() ? "" : " ").append("Defaults to ").append(given).append('.');
			}
			if (names.size() > 1 && names.size() == types.size()) {
				for (int i = 0; i < names.size(); i++) {
					fields.add(new ApiPage.PropertyDoc(names.get(i), types.get(i), text.toString()));
				}
			} else if (names.size() > 1) {
				report("a row that names several fields, " + names + ", but not a type for each, " + types);
			} else if (!names.isEmpty()) {
				fields.add(field(names.get(0), types, text.toString()));
			}
		}
		return fields;
	}

	/**
	 * A field of one name: its types, a line each, are alternatives. A row that gives no type, or writes its
	 * description where the type goes, takes its type from a {@code field} fact; one that writes a further type where
	 * its description goes, as the sprite's {@code Int} and {@code RuntimeSprite}, has both.
	 */
	private ApiPage.PropertyDoc field(String written, List<String> types, String description) {
		String type = String.join("/", types);
		String text = description;
		if (!type.isEmpty() && TYPE_ALONE.matcher(text).matches()) {
			type = type + "/" + text;
			text = "";
		}
		// A field that may be nil may carry its mark on its name, npcData?
		String name = written.endsWith("?") ? written.substring(0, written.length() - 1) : written;
		boolean optional = !name.equals(written);
		if (type.startsWith("(Read-only)")) {
			// A row may write the mark of its description where its type goes
			type = type.substring("(Read-only)".length()).strip();
			text = "(Read-only) " + text;
		}
		// A description written where the type goes is a sentence, which ends with a full stop
		boolean sentence = type.endsWith(".");
		if (type.isEmpty() || type.equals("-") || sentence) {
			if (sentence && text.isEmpty()) {
				text = type;
			}
			String given = facts.field(place(), name);
			if (given == null) {
				report("a field whose type the docs do not give, " + name + "; a field fact gives it");
				type = "?";
			} else {
				type = given;
			}
		}
		return new ApiPage.PropertyDoc(name, optional && !type.endsWith("?") ? type + "?" : type, text);
	}

	/**
	 * The forms of the signature blocks among some blocks, a signature that spans several lines joined onto one. Those
	 * in a note count, as the docs give a function's typed variants there, but not those of an example.
	 *
	 * @param failures where a line that starts a signature the reader cannot read is added
	 */
	private static List<Signature> forms(List<Element> blocks, List<String> failures) {
		List<Signature> forms = new ArrayList<>();
		for (Element block : blocks) {
			if (isExample(block)) {
				continue;
			}
			for (Element pre : block.tagName().equals("pre") ? List.of(block) : block.select("pre")) {
				List<String> lines = NEWLINE.splitAsStream(pre.wholeText()).toList();
				if (isBareSignatures(lines)) {
					lines = lines.stream().map(line -> line.isBlank() ? line : "function " + line.strip()).toList();
				}
				for (int i = 0; i < lines.size(); i++) {
					String line = lines.get(i).strip();
					if (!line.startsWith("function")) {
						continue;
					}
					StringBuilder joined = new StringBuilder(line);
					while (depth(joined) > 0 && i + 1 < lines.size()) {
						joined.append(' ').append(lines.get(++i).strip());
					}
					Signature form = Signature.parse(joined.toString());
					if (form != null) {
						forms.add(form);
					} else {
						failures.add(joined.toString());
					}
				}
			}
		}
		return forms;
	}

	/**
	 * Whether a block of code is signatures written without their keyword, {@code Chat.AddMessage(message : String)}:
	 * every line one, so that an example's call is never taken for one.
	 */
	private static boolean isBareSignatures(List<String> lines) {
		List<String> code = lines.stream().map(String::strip).filter(line -> !line.isEmpty()).toList();
		return !code.isEmpty() && code.stream().allMatch(line -> !line.startsWith("function")
				&& Signature.parse("function " + line) != null);
	}

	private static List<Signature> forms(List<Element> blocks) {
		return forms(blocks, new ArrayList<>());
	}

	private static boolean hasSignatures(List<Element> blocks) {
		return !forms(blocks).isEmpty();
	}

	private static int depth(CharSequence text) {
		int depth = 0;
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (c == '(') {
				depth++;
			} else if (c == ')') {
				depth--;
			}
		}
		return depth;
	}

	/** A note: an example is left out, as the Examples section is; any other joins a description. */
	private static void note(Element block, List<String> description) {
		if (isExample(block) || !(block.hasClass("admonition") || block.tagName().equals("details"))) {
			return;
		}
		Element title = block.selectFirst(".admonition-title, summary");
		String kind = title == null ? "" : text(title);
		List<String> paragraphs = new ArrayList<>();
		for (Element paragraph : block.select("p")) {
			if (!paragraph.hasClass("admonition-title")) {
				String text = text(paragraph);
				if (!text.isEmpty()) {
					paragraphs.add(text);
				}
			}
		}
		if (!paragraphs.isEmpty()) {
			description.add((kind.isEmpty() ? "" : kind + ": ") + String.join(" ", paragraphs));
		}
	}

	private static boolean isExample(Element block) {
		if (!(block.hasClass("admonition") || block.tagName().equals("details"))) {
			return false;
		}
		Element title = block.selectFirst(".admonition-title, summary");
		return block.hasClass("example") || (title != null && text(title).equalsIgnoreCase("example"));
	}

	private static boolean hasTable(List<Element> blocks, Predicate<Element> kind) {
		return blocks.stream().anyMatch(block -> block.tagName().equals("table") && kind.test(block));
	}

	/** A table of a type's fields: a column of names, called Name or Property, and one of types. */
	private static boolean isPropertiesTable(Element table) {
		List<String> headers = headers(table);
		return !headers.isEmpty() && List.of("name", "property").contains(headers.get(0)) && headers.contains("type");
	}

	/** A table of an event's payload, whose first column is called Payload variable. */
	private static boolean isPayloadTable(Element table) {
		List<String> headers = headers(table);
		return !headers.isEmpty() && headers.get(0).equals("payload variable");
	}

	/** A table of an enumeration's values: names, with values, descriptions or a hook's parameters, and no types. */
	private static boolean isEnumTable(Element table) {
		List<String> headers = headers(table);
		return headers.size() >= 2 && List.of("name", "value").contains(headers.get(0)) && !headers.contains("type")
				&& (headers.contains("value") || headers.contains("description") || headers.contains("parameters"));
	}

	private static List<String> headers(Element table) {
		List<String> headers = new ArrayList<>();
		for (Element cell : table.select("thead th, thead td")) {
			headers.add(text(cell).toLowerCase(Locale.ROOT));
		}
		return headers;
	}

	/** The body's rows, each cell's text on one line, with as many cells as the table has columns. */
	private static List<List<String>> rows(Element table) {
		return cellLines(table).stream()
				.map(row -> row.stream().map(lines -> String.join(" ", lines)).toList())
				.toList();
	}

	/** The body's rows, each cell as its lines, which line breaks separate, with as many as the table has columns. */
	private static List<List<List<String>>> cellLines(Element table) {
		int columns = headers(table).size();
		List<List<List<String>>> rows = new ArrayList<>();
		for (Element row : table.select("tbody tr")) {
			List<List<String>> cells = new ArrayList<>();
			for (Element cell : row.select("td")) {
				cells.add(lines(cell));
			}
			while (cells.size() < columns) {
				cells.add(List.of());
			}
			rows.add(cells);
		}
		return rows;
	}

	/** An element's lines of text, which its line breaks separate, each as {@link #text} gives it. */
	private static List<String> lines(Element element) {
		StringBuilder text = new StringBuilder();
		appendText(element, text, true);
		return NEWLINE.splitAsStream(text)
				.map(line -> SPACES.matcher(line).replaceAll(" ").strip())
				.filter(line -> !line.isEmpty())
				.toList();
	}

	/** An element's text as Markdown, which LuaLS shows: code in backticks, links as their text, spaces collapsed. */
	static String text(Element element) {
		StringBuilder text = new StringBuilder();
		appendText(element, text, false);
		return SPACES.matcher(text).replaceAll(" ").strip();
	}

	private static void appendText(Node node, StringBuilder text, boolean breaks) {
		for (Node child : node.childNodes()) {
			if (child instanceof TextNode textNode) {
				text.append(breaks ? textNode.getWholeText().replace('\n', ' ') : textNode.getWholeText());
			} else if (child instanceof Element element) {
				switch (element.tagName()) {
					case "code" -> text.append('`').append(element.wholeText().strip()).append('`');
					case "br" -> text.append(breaks ? '\n' : ' ');
					// A table's Writeable column draws a cross or a tick
					case "svg" -> text.append(element.hasClass("lucide-x") ? "No"
							: element.hasClass("lucide-check") ? "Yes" : "");
					default -> appendText(element, text, breaks);
				}
			}
		}
	}

	/** The page and the anchor of the heading being read, by which the facts and the reports name a place. */
	private String place() {
		return path + "#" + anchor;
	}

	private void report(String what) {
		reports.add(path + (anchor.isEmpty() ? "" : "#" + anchor) + ": " + what);
	}

	/**
	 * The page's types, named: a fact's name; else, for a heading that names no type, or names one its signatures name
	 * more fully ({@code World.RemoveRoof} for {@code RemoveRoof}, {@code audio} for {@code Audio}), the owner its
	 * signatures name; else its heading. Another owner the signatures name becomes another name of the type's.
	 */
	private ApiPage finish() {
		Map<String, TypeBuilder> named = new LinkedHashMap<>();
		Map<String, List<String>> generics = new LinkedHashMap<>();
		Map<String, String> otherNames = new LinkedHashMap<>();
		for (TypeBuilder type : types) {
			anchor = type.anchor;
			String owner = type.owners.size() == 1 ? type.owners.iterator().next() : null;
			List<String> parameters = new ArrayList<>();
			String ownerName = owner == null ? null : typeName(owner, parameters);
			String fact = facts.name(place());
			String name;
			if (fact != null) {
				name = fact;
				if (!fact.equals(type.heading)) {
					// Other types name it by its heading, [Combo Entry]
					otherNames.put(type.heading, fact);
				}
			} else if (!TYPE_NAME.matcher(type.heading).matches()) {
				if (ownerName == null) {
					if (!type.isEmpty()) {
						report("a type its heading does not name, " + type.heading + "; a name fact names it");
					}
					continue;
				}
				name = ownerName;
			} else if (ownerName != null && (ownerName.equalsIgnoreCase(type.heading)
					|| ownerName.endsWith("." + type.heading))) {
				name = ownerName;
			} else {
				name = type.heading;
			}
			for (String other : type.owners) {
				if (!other.equals(name) && TYPE_NAME.matcher(other).matches()) {
					otherNames.put(other, name);
				}
			}
			TypeBuilder earlier = named.putIfAbsent(name, type);
			if (earlier != null) {
				merge(earlier, type);
			}
			if (!parameters.isEmpty()) {
				generics.put(name, parameters);
			}
		}
		List<ApiPage.TypeDoc> docs = new ArrayList<>();
		named.forEach((name, type) -> {
			// A type of no members of its own is still one where it extends another, as ui.CutsceneLayer a layer
			if (type.isEmpty() && type.parent == null) {
				return;
			}
			String parent = facts.parent(name);
			Boolean global = facts.isGlobal(name) ? Boolean.TRUE : type.global;
			docs.add(new ApiPage.TypeDoc(name, generics.getOrDefault(name, List.of()),
					parent != null ? parent : type.parent, type.description, type.functions, type.properties,
					type.indexes, global));
		});
		List<ApiPage.TypeDoc> withCallers = docs.stream().map(type -> withCallerFunctions(type, docs)).toList();
		return new ApiPage(path, withCallers, globals.functions, enums, events, otherNames, described);
	}

	/**
	 * A type whose parent stands, in its functions, for the type each is called on, as {@code Vector} for each vector,
	 * with those functions as its own: its name in the parent's place, unless it documents one of that name itself.
	 */
	private ApiPage.TypeDoc withCallerFunctions(ApiPage.TypeDoc type, List<ApiPage.TypeDoc> docs) {
		String parent = type.parent();
		if (parent == null || !facts.isCaller(parent)) {
			return type;
		}
		Pattern caller = Pattern.compile("\\b" + Pattern.quote(parent) + "\\b");
		UnaryOperator<String> retype = docType -> caller.matcher(docType).replaceAll(type.name());
		List<ApiPage.FunctionDoc> functions = new ArrayList<>(type.functions());
		docs.stream()
				.filter(doc -> doc.name().equals(parent))
				.flatMap(doc -> doc.functions().stream())
				.filter(function -> function.colon() && functions.stream()
						.noneMatch(own -> own.name().equals(function.name()) && own.colon()))
				.map(function -> new ApiPage.FunctionDoc(function.name(), true, function.forms().stream()
						.map(form -> new Signature(type.name(), form.name(), true, form.params().stream()
								.map(param -> new Signature.Param(param.name(), retype.apply(param.type())))
								.toList(), form.returns().stream().map(retype).toList()))
						.toList(), function.description(), function.params().stream()
						.map(param -> new ApiPage.ParamDoc(param.name(), retype.apply(param.type()), param.required(),
								param.description()))
						.toList(), function.returns().stream()
						.map(value -> new ApiPage.ReturnDoc(retype.apply(value.type()), value.description()))
						.toList(), function.place()))
				.forEach(functions::add);
		return new ApiPage.TypeDoc(type.name(), type.generics(), parent, type.description(), functions,
				type.properties(), type.indexes(), type.global());
	}

	/**
	 * The type a signature's owner names, its placeholders taken as type parameters: {@code PluginResource<T>} is
	 * {@code PluginResource} with {@code T}. Null for an owner that names an element of a table, such as
	 * {@code game.options.<option name>}, which names no type.
	 */
	private static @Nullable String typeName(String owner, List<String> parameters) {
		Matcher placeholder = PLACEHOLDER.matcher(owner);
		List<String> found = new ArrayList<>();
		while (placeholder.find()) {
			found.add(placeholder.group(1).strip());
		}
		String name = PLACEHOLDER.matcher(owner).replaceAll("");
		if (!TYPE_NAME.matcher(name).matches() || name.endsWith(".")
				|| found.stream().anyMatch(parameter -> !IDENTIFIER.matcher(parameter).matches())) {
			return null;
		}
		parameters.addAll(found);
		return name;
	}

	private static void merge(TypeBuilder into, TypeBuilder from) {
		into.description.addAll(from.description);
		from.functions.forEach(function -> addFunction(into, function));
		into.properties.addAll(from.properties);
		into.indexes.addAll(from.indexes);
		if (into.parent == null) {
			into.parent = from.parent;
		}
	}
}
