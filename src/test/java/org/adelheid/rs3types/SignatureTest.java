package org.adelheid.rs3types;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The signatures of the docs' code blocks. */
class SignatureTest {

	@Test
	void readsAColonFunctionWithItsReturnValue() {
		assertEquals(new Signature("PersistentDB", "Get", true, List.of(new Signature.Param("key", "String")),
						List.of("Bool/Int/Float/String/Table?")),
				Signature.parse("function PersistentDB:Get(key : String) -> Bool/Int/Float/String/Table?"));
	}

	@Test
	void readsADotFunctionOfANamespaceWithOptionalParameters() {
		assertEquals(new Signature("ui.Text", "new", false, List.of(
						new Signature.Param("parent", "ui.ComponentCollection"),
						new Signature.Param("categoryID", "Int?")), List.of("Text")),
				Signature.parse("  function ui.Text.new(parent : ui.ComponentCollection, categoryID : Int?) -> Text"));
	}

	@Test
	void readsAFunctionWithoutParametersOrReturnValues() {
		assertEquals(new Signature("Lamp", "Snuff", true, List.of(), List.of()),
				Signature.parse("function Lamp:Snuff()"));
	}

	@Test
	void readsSeveralReturnValues() {
		assertEquals(new Signature("World", "GetGroundHeight", false, List.of(), List.of("Integer", "Boolean")),
				Signature.parse("function World.GetGroundHeight() -> Integer,Boolean"));
	}

	@Test
	void readsAGlobalFunctionAndVarargs() {
		assertEquals(new Signature("", "log", false, List.of(new Signature.Param("format", "String"),
						new Signature.Param("...", "?")), List.of()),
				Signature.parse("function log(format : String, args : ...)"));
	}

	@Test
	void readsAnOptionalMarkOnTheNameAndACallbackWithItsOwnParentheses() {
		assertEquals(List.of(new Signature.Param("offset", "HeightOffsetType?")),
				parsed("function World.Height(offset? : HeightOffsetType)").params());
		assertEquals(List.of(new Signature.Param("hook", "ui.Hook"),
						new Signature.Param("callback", "function (...) -> Bool?")),
				parsed("function Component:Subscribe(hook : ui.Hook, callback : function (...) -> Bool?)").params());
	}

	@Test
	void readsAnOwnerWithAPlaceholder() {
		assertEquals("game.options.<option name>",
				parsed("function game.options.<option name>:Set(setting : Int) -> Bool").owner());
		assertEquals("PluginResource<T>", parsed("function PluginResource<T>:Unload()").owner());
	}

	@Test
	void anythingElseIsNoSignature() {
		assertNull(Signature.parse("local lantern = Lantern.new(3)"));
		assertNull(Signature.parse("function Lamp:Light(seconds)"));
		assertNull(Signature.parse("function Lamp:Light(seconds : Float"));
	}

	@Test
	void splitsAtTheSeparatorsOutsideBrackets() {
		assertEquals(List.of("a : [ Int ]", "b : (x : Int) -> Bool", "c : Table"),
				Signature.splitTopLevel("a : [ Int ], b : (x : Int) -> Bool, c : Table", ','));
		assertEquals(List.of("[ Int/String ]", "Bool"), Signature.splitTopLevel("[ Int/String ] / Bool", '/'));
		assertEquals(List.of(), Signature.splitTopLevel("  ", ','));
	}

	private static Signature parsed(String line) {
		Signature signature = Signature.parse(line);
		if (signature == null) {
			throw new AssertionError("No signature: " + line);
		}
		return signature;
	}
}
