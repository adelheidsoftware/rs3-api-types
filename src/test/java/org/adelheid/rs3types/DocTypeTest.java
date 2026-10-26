package org.adelheid.rs3types;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The docs' types in LuaCATS. */
class DocTypeTest {

	private static final Map<String, String> ALIASES = Map.of("NPC", "NPCType", "Combo Entry", "ui.ComboEntry");

	private static String lua(String docs) {
		return DocType.lua(docs, ALIASES, name -> { });
	}

	@Test
	void namesLuasTypes() {
		assertEquals("string", lua("String"));
		assertEquals("boolean", lua("Bool"));
		assertEquals("number", lua("Float"));
		// A 64-bit integer keeps its width, as base.lua's alias Long
		assertEquals("Long", lua("Uint64"));
		assertEquals("Long?", lua("Long?"));
	}

	@Test
	void writesOptionalTypesAndUnions() {
		assertEquals("integer?", lua("Int?"));
		assertEquals("boolean|integer|number|string|table|nil", lua("Bool/Int/Float/String/Table?"));
		assertEquals("integer|string|nil", lua("Int? | String?"));
		assertEquals("any", lua("?"));
	}

	@Test
	void writesArraysTuplesAndMaps() {
		assertEquals("string[]", lua("[ String ]"));
		assertEquals("(integer|string)[]", lua("[ Int/String ]"));
		assertEquals("[integer, NPCType][]", lua("[ [ Integer, NPC ] ]"));
		assertEquals("[integer, integer]", lua("Integer,Integer"));
		assertEquals("[integer, integer]", lua("(Int, Int)"));
		assertEquals("table<integer, integer[]>", lua("`{ Int -> [ Uint16 ] }`"));
	}

	@Test
	void writesCallbacks() {
		assertEquals("fun(key: string, value: boolean|integer): boolean",
				lua("function (key : String, value : Bool/Int) -> Bool"));
		assertEquals("fun(...: any): boolean?", lua("function (...) -> Bool?"));
		assertEquals("(fun(self: HTTPRequest))|nil", lua("function? (self : HTTPRequest)"));
	}

	@Test
	void makesATypeOptionalWithoutChangingAFunctionsResult() {
		assertEquals("integer?", DocType.orNil("integer"));
		assertEquals("integer|string|nil", DocType.orNil("integer|string"));
		assertEquals("(fun(entity: NPCEntity): boolean)|nil", DocType.orNil("fun(entity: NPCEntity): boolean"));
		assertEquals("string[]?", DocType.orNil("string[]?"));
		assertEquals("any", DocType.orNil("any"));
	}

	@Test
	void keepsATypeWrittenAsLuaCatsAndTellsItsNames() {
		List<String> names = new ArrayList<>();
		assertEquals("fun(locType: LocType): table<integer, string>",
				DocType.lua(DocType.LUA + "fun(locType: LocType): table<integer, string>", ALIASES, names::add));
		assertEquals(List.of("LocType"), names);
	}

	@Test
	void keepsTheApisNamesButForAnAlias() {
		assertEquals("ui.Component", lua("ui.Component"));
		assertEquals("ui.Component", lua("ui.Component<T>"));
		assertEquals("NPCType?", lua("NPC?"));
		assertEquals("ui.ComboEntry[]", lua("[Combo Entry]"));
	}

	@Test
	void tellsTheNamesItWrites() {
		List<String> names = new ArrayList<>();
		DocType.lua("[ NPC ]/ui.Component/String", ALIASES, names::add);
		assertEquals(List.of("NPCType", "ui.Component"), names);
	}

	@Test
	void tellsWhatItDoesNotUnderstand() {
		assertTrue(DocType.understands("[ ui.Component ]?", ALIASES));
		assertTrue(DocType.understands("[Combo Entry]", ALIASES));
		assertFalse(DocType.understands("Map of String to Int", ALIASES));
		assertEquals("any", lua("Map of String to Int"));
	}
}
