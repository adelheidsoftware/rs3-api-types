package org.adelheid.rs3types;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * One form of a function, as a signature block of the docs writes it:
 * {@code function PersistentDB:Get(key : String) -> Bool/Int/Float/String/Table?}. Types keep the docs' spelling;
 * {@link DocType} turns them into LuaCATS.
 *
 * @param owner the table the docs write before the name, {@code PersistentDB}, {@code ui.Text}, or with a placeholder
 *     for an element of a table, {@code game.options.<option name>}; empty for a global function, {@code log}
 * @param colon whether the function is called with a colon
 * @param returns the types after {@code ->}, one for each value; none for a function that returns nothing
 */
record Signature(String owner, String name, boolean colon, List<Param> params, List<String> returns) {

	/** {@code function <owner>.<name>(}, {@code function <owner>:<name>(} or {@code function <name>(}. */
	private static final Pattern HEAD = Pattern.compile("function\\s+(?:(.+?)([.:]))?(\\w+)\\s*\\(");
	private static final Pattern PARAM = Pattern.compile("(\\w+)(\\?)?\\s*:\\s*(.+)|(\\.\\.\\.)(?:\\s*:\\s*(.+))?");

	Signature {
		params = List.copyOf(params);
		returns = List.copyOf(returns);
	}

	/**
	 * A parameter.
	 *
	 * @param type its type as the docs write it, ending in {@code ?} where it may be left out
	 */
	record Param(String name, String type) {
	}

	/** A signature, joined onto one line where it spans several; null for text that is no signature, such as code. */
	static @Nullable Signature parse(String text) {
		String line = text.strip();
		Matcher head = HEAD.matcher(line);
		if (!head.lookingAt()) {
			return null;
		}
		int open = head.end() - 1;
		int close = closing(line, open);
		if (close < 0) {
			return null;
		}
		List<Param> params = new ArrayList<>();
		for (String part : splitTopLevel(line.substring(open + 1, close), ',')) {
			Param param = param(part);
			if (param == null) {
				return null;
			}
			params.add(param);
		}
		String rest = line.substring(close + 1).strip();
		List<String> returns;
		if (rest.isEmpty()) {
			returns = List.of();
		} else if (rest.startsWith("->")) {
			returns = splitTopLevel(rest.substring(2), ',');
		} else {
			return null;
		}
		String owner = head.group(1);
		return new Signature(owner == null ? "" : owner, head.group(3), ":".equals(head.group(2)), params, returns);
	}

	/**
	 * A parameter, {@code key : String}; a name marked {@code ?} makes its type optional, and the type {@code ...}
	 * makes it Lua's varargs, as {@code args : ...} is. Null for anything else.
	 */
	private static @Nullable Param param(String part) {
		Matcher param = PARAM.matcher(part);
		if (!param.matches()) {
			return null;
		}
		if (param.group(4) != null) {
			String type = param.group(5);
			return new Param("...", type == null ? "?" : type.strip());
		}
		String type = param.group(3).strip();
		if (type.equals("...")) {
			return new Param("...", "?");
		}
		boolean optional = param.group(2) != null && !type.endsWith("?");
		return new Param(param.group(1), optional ? type + "?" : type);
	}

	/** The index of the parenthesis that closes the one at {@code open}; -1 where none does. */
	static int closing(String text, int open) {
		int depth = 0;
		for (int i = open; i < text.length(); i++) {
			char c = text.charAt(i);
			if (c == '(') {
				depth++;
			} else if (c == ')' && --depth == 0) {
				return i;
			}
		}
		return -1;
	}

	/**
	 * The parts of a list that a separator outside brackets separates, each stripped; none for a blank list. Types use
	 * it too: {@code [ Int/String ]/Bool} is two alternatives.
	 */
	static List<String> splitTopLevel(String list, char separator) {
		List<String> parts = new ArrayList<>();
		int depth = 0;
		int start = 0;
		for (int i = 0; i < list.length(); i++) {
			char c = list.charAt(i);
			if (c == '(' || c == '[' || c == '{' || c == '<') {
				depth++;
			} else if (c == ')' || c == ']' || c == '}' || (c == '>' && (i == 0 || list.charAt(i - 1) != '-'))) {
				// The > of an arrow, as in a callback's type, closes nothing
				depth--;
			} else if (c == separator && depth == 0) {
				parts.add(list.substring(start, i).strip());
				start = i + 1;
			}
		}
		String last = list.substring(start).strip();
		if (!last.isEmpty() || !parts.isEmpty()) {
			parts.add(last);
		}
		return parts;
	}
}
