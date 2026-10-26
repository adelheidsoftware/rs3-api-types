package org.adelheid.rs3types;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * What one page of the docs describes, as {@link PageReader} found it: the types it documents, its global functions,
 * the enumerations it lists and the events it describes, in page order, with every type spelled as the docs spell it.
 * {@link LuaCatsWriter} turns it into LuaCATS.
 *
 * @param path the page's path on the site, {@code core/persistentdb.html}
 * @param otherNames the names the page's signatures give its types besides their own, such as {@code NPC} for
 *     {@code NPCType}, by those names, which other pages' types may use too
 * @param described the types the page describes in prose alone, under a heading named like a type, by name, with
 *     their paragraphs; written where some file names them
 */
record ApiPage(String path, List<TypeDoc> types, List<FunctionDoc> globals, List<EnumDoc> enums,
		List<EventDoc> events, Map<String, String> otherNames, Map<String, List<String>> described) {

	ApiPage {
		// In page order, which Map.copyOf would not keep: the files are written in it, and the first of two other
		// names is taken
		described = Collections.unmodifiableMap(new LinkedHashMap<>(described));
		types = List.copyOf(types);
		globals = List.copyOf(globals);
		enums = List.copyOf(enums);
		events = List.copyOf(events);
		otherNames = Collections.unmodifiableMap(new LinkedHashMap<>(otherNames));
	}

	/** Whether the page documents any of the API, which a tutorial or a guideline does not. */
	boolean isApi() {
		return !types.isEmpty() || !globals.isEmpty() || !enums.isEmpty() || !events.isEmpty();
	}

	/**
	 * A type.
	 *
	 * @param name its Lua name, {@code ui.Text}
	 * @param generics the names of its type parameters, {@code T} for {@code PluginResource<T>}
	 * @param parent the type it extends, {@code ui.Component}; null for none
	 * @param description its overview's paragraphs, and those of the sections of prose about it
	 * @param indexes what indexing it gives, as {@code ui.StyleSheets[id]} gives a style sheet
	 * @param global whether its table is a global, rather than only a description of the client's objects; null where
	 *     the page does not say, and its functions decide
	 */
	record TypeDoc(String name, List<String> generics, @Nullable String parent, List<String> description,
			List<FunctionDoc> functions, List<PropertyDoc> properties, List<IndexDoc> indexes,
			@Nullable Boolean global) {

		TypeDoc {
			generics = List.copyOf(generics);
			description = List.copyOf(description);
			functions = List.copyOf(functions);
			properties = List.copyOf(properties);
			indexes = List.copyOf(indexes);
		}
	}

	/**
	 * A function, with every form its signatures give: the forms of an overloaded function share one entry.
	 *
	 * @param colon whether it is called with a colon, on an object or a singleton
	 * @param params the parameters the table under its signatures describes, of its first form
	 * @param returns the return values the table under its signatures describes
	 * @param place where the docs document it, {@code core/persistentdb.html#set}, which the reports name
	 */
	record FunctionDoc(String name, boolean colon, List<Signature> forms, List<String> description,
			List<ParamDoc> params, List<ReturnDoc> returns, String place) {

		FunctionDoc {
			forms = List.copyOf(forms);
			description = List.copyOf(description);
			params = List.copyOf(params);
			returns = List.copyOf(returns);
		}
	}

	/**
	 * A row of a function's table of parameters.
	 *
	 * @param required false where the table's Required column says the parameter may be left out
	 */
	record ParamDoc(String name, String type, boolean required, String description) {
	}

	/** A row of a function's table of return values. */
	record ReturnDoc(String type, String description) {
	}

	/** A field of a type, of an event's payload or of a table a function takes. */
	record PropertyDoc(String name, String type, String description) {
	}

	/** Indexing a type with a key, {@code ui.StyleSheets[id]}, which gives a value. */
	record IndexDoc(String key, String value, String description) {
	}

	/** An enumeration, with its values in the order the docs list them. */
	record EnumDoc(String name, List<String> description, List<EnumValue> values) {

		EnumDoc {
			description = List.copyOf(description);
			values = List.copyOf(values);
		}
	}

	/**
	 * A value of an enumeration.
	 *
	 * @param value its number, as the docs write it; null where the docs list names alone
	 */
	record EnumValue(String name, @Nullable String value, String description) {
	}

	/**
	 * An event the client sends plugins, which they subscribe to as {@code Event.<name>}.
	 *
	 * @param payload the fields of the table its callbacks receive; null for an event that passes none
	 * @param returns what its callbacks return, as the docs write the type; null for nothing
	 */
	record EventDoc(String name, List<String> description, @Nullable List<PropertyDoc> payload,
			@Nullable String returns) {

		EventDoc {
			description = List.copyOf(description);
			payload = payload == null ? null : List.copyOf(payload);
		}
	}
}
