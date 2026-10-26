package org.adelheid.rs3types;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A type as the docs write it, as LuaCATS writes it: {@code Bool/Int/Float/String/Table?} is
 * {@code boolean|integer|number|string|table|nil}, {@code [ String ]} is {@code string[]}, {@code [ Integer, Obj ]} is
 * the tuple {@code [integer, ObjType]}, {@code function (key : String) -> Bool} is {@code fun(key: string): boolean},
 * and a lone {@code ?}, a value of any type, is {@code any}. A name of the API's is kept, without its type arguments,
 * but for one that a page's signatures give a type besides its own, such as {@code NPC} for {@code NPCType}, which is
 * that type.
 */
final class DocType {

	/**
	 * The docs' names for Lua's own types and for the client's number types, which Lua holds as integers or floats; a
	 * 64-bit integer is base.lua's {@code Long}, which keeps its width for a language whose integers are narrower.
	 */
	private static final Map<String, String> PRIMITIVES = Map.ofEntries(
			Map.entry("string", "string"),
			Map.entry("bool", "boolean"),
			Map.entry("boolean", "boolean"),
			Map.entry("int", "integer"),
			Map.entry("integer", "integer"),
			Map.entry("int8", "integer"),
			Map.entry("int16", "integer"),
			Map.entry("int32", "integer"),
			Map.entry("int64", "Long"),
			Map.entry("uint", "integer"),
			Map.entry("uint8", "integer"),
			Map.entry("uint16", "integer"),
			Map.entry("uint32", "integer"),
			Map.entry("uint64", "Long"),
			Map.entry("long", "Long"),
			Map.entry("float", "number"),
			Map.entry("double", "number"),
			Map.entry("number", "number"),
			Map.entry("table", "table"),
			Map.entry("function", "function"),
			Map.entry("nil", "nil"),
			Map.entry("any", "any"));
	/** The mark of a type already written as LuaCATS writes it, as corrections.txt gives types, which is kept. */
	static final String LUA = "lua:";

	private static final Pattern NAME = Pattern.compile("[A-Za-z_][\\w.]*");
	/** A parameter's name in a LuaCATS function type, {@code staticID:} in {@code fun(staticID: integer)}. */
	private static final Pattern FUN_PARAM_NAME = Pattern.compile("(\\w+|\\.\\.\\.)\\??\\s*:");
	/** LuaCATS's own names, which name no type of the API's. */
	private static final Set<String> LUA_NAMES = Set.of("string", "integer", "number", "boolean", "table", "nil",
			"any", "fun", "function", "userdata", "thread", "self");
	private static final Pattern PARAM = Pattern.compile("(\\w+)\\s*:\\s*(.+)");

	private final Map<String, String> aliases;
	private final Consumer<String> names;
	private boolean understood = true;

	private DocType(Map<String, String> aliases, Consumer<String> names) {
		this.aliases = aliases;
		this.names = names;
	}

	/**
	 * The LuaCATS form of a type; {@code any} for a part it does not understand.
	 *
	 * @param aliases the types the pages' signatures give other names, by those names
	 * @param names what is given each name of the API's that the type names, so that one no page defines is found
	 */
	static String lua(String docs, Map<String, String> aliases, Consumer<String> names) {
		return new DocType(aliases, names).type(docs);
	}

	/** Whether {@link #lua} understands every part of a type, rather than reading one as {@code any}. */
	static boolean understands(String docs, Map<String, String> aliases) {
		DocType type = new DocType(aliases, name -> { });
		type.type(docs);
		return type.understood;
	}

	private String type(String docs) {
		if (docs.startsWith(LUA)) {
			String lua = docs.substring(LUA.length());
			NAME.matcher(FUN_PARAM_NAME.matcher(lua).replaceAll("")).results()
					.map(MatchResult::group)
					.filter(name -> !LUA_NAMES.contains(name))
					.forEach(names);
			return lua;
		}
		// A type the docs write as code, `VarType`, is the type
		String type = docs.replace("`", "").strip();
		if (type.isEmpty() || type.equals("?")) {
			return "any";
		}
		boolean optional = false;
		List<String> alternatives = new ArrayList<>();
		for (String slashed : Signature.splitTopLevel(type, '/')) {
			for (String alternative : Signature.splitTopLevel(slashed, '|')) {
				// A callback marks itself optional after its keyword, function? (...), as a ? at its end is its return
				// value's; any other type marks itself at its end
				if (alternative.startsWith("function?")) {
					optional = true;
					alternative = "function" + alternative.substring("function?".length());
				} else if (alternative.endsWith("?") && !alternative.equals("?")
						&& !alternative.startsWith("function")) {
					optional = true;
					alternative = alternative.substring(0, alternative.length() - 1).strip();
				}
				String lua = single(alternative);
				if (!alternatives.contains(lua)) {
					alternatives.add(lua);
				}
			}
		}
		String union = String.join("|", alternatives);
		return optional ? orNil(union) : union;
	}

	/**
	 * A LuaCATS type that may also be nil: {@code T?}, {@code A|B|nil}, and a function type in brackets,
	 * {@code (fun(): boolean)|nil}, whose {@code ?} or {@code |nil} would be its result's. Any type may be nil already.
	 */
	static String orNil(String lua) {
		if (lua.endsWith("?") || lua.endsWith("|nil") || lua.equals("any")) {
			return lua;
		}
		if (lua.contains("fun(")) {
			return "(" + lua + ")|nil";
		}
		return lua.contains("|") ? lua + "|nil" : lua + "?";
	}

	private String single(String docs) {
		String type = docs.strip();
		if (type.equals("?")) {
			return "any";
		}
		// A name with spaces, which a heading gives a type, [Combo Entry]
		String alias = aliases.get(type);
		if (alias != null) {
			if (!PRIMITIVES.containsValue(alias)) {
				names.accept(alias);
			}
			return alias;
		}
		if (type.startsWith("{") && type.endsWith("}")) {
			return map(type.substring(1, type.length() - 1));
		}
		List<String> values = Signature.splitTopLevel(type, ',');
		if (values.size() > 1) {
			// Values written together, Integer,Integer, are a tuple
			return "[" + String.join(", ", values.stream().map(this::type).toList()) + "]";
		}
		if (type.startsWith("function") && type.substring("function".length()).strip().startsWith("(")) {
			return callback(type.substring("function".length()).strip());
		}
		boolean brackets = type.startsWith("[") && type.endsWith("]");
		boolean parentheses = type.startsWith("(") && type.endsWith(")");
		if (brackets || parentheses) {
			String inner = type.substring(1, type.length() - 1);
			List<String> parts = Signature.splitTopLevel(inner, ',');
			if (parts.size() > 1 || parentheses) {
				// Several values together, [ Integer, Obj ] or (Int, Int), are a tuple
				return "[" + String.join(", ", parts.stream().map(this::type).toList()) + "]";
			}
			return element(type(inner)) + "[]";
		}
		int generic = type.indexOf('<');
		if (generic > 0 && type.endsWith(">")) {
			type = type.substring(0, generic);
		}
		String primitive = PRIMITIVES.get(type.toLowerCase(Locale.ROOT));
		if (primitive != null) {
			return primitive;
		}
		if (NAME.matcher(type).matches()) {
			String name = aliases.getOrDefault(type, type);
			names.accept(name);
			return name;
		}
		understood = false;
		return "any";
	}

	/** A map, {@code String -> Int}, as {@code table<string, integer>}. */
	private String map(String entries) {
		int arrow = entries.indexOf("->");
		if (arrow < 0) {
			understood = false;
			return "table";
		}
		return "table<" + type(entries.substring(0, arrow)) + ", " + type(entries.substring(arrow + 2)) + ">";
	}

	/** A callback, {@code (key : String, ...) -> Bool}, as {@code fun(key: string, ...: any): boolean}. */
	private String callback(String signature) {
		int close = Signature.closing(signature, 0);
		if (close < 0) {
			understood = false;
			return "any";
		}
		List<String> params = new ArrayList<>();
		for (String param : Signature.splitTopLevel(signature.substring(1, close), ',')) {
			Matcher named = PARAM.matcher(param);
			if (param.equals("...")) {
				params.add("...: any");
			} else if (named.matches()) {
				params.add(named.group(1) + ": " + type(named.group(2)));
			} else {
				understood = false;
				params.add("value: any");
			}
		}
		String rest = signature.substring(close + 1).strip();
		String returns = "";
		if (rest.startsWith("->")) {
			returns = ": " + String.join(", ",
					Signature.splitTopLevel(rest.substring(2), ',').stream().map(this::type).toList());
		} else if (!rest.isEmpty()) {
			understood = false;
		}
		return "fun(" + String.join(", ", params) + ")" + returns;
	}

	/** An array's element type, in parentheses where it is a union, so that {@code []} applies to all of it. */
	private static String element(String type) {
		return type.contains("|") ? "(" + type + ")" : type;
	}
}
