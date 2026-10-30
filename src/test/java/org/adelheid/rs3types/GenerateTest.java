package org.adelheid.rs3types;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The library the generator writes from the tests' site, and the facts it reads. */
class GenerateTest {

	@TempDir
	Path root;

	@Test
	void writesADefinitionFileForEachPageOfTheApi() throws IOException {
		Path library = root.resolve("library");
		Files.createDirectories(library.resolve("gone"));
		Files.writeString(library.resolve("gone/removed.lua"), "-- a page the site no longer has\n");
		DocsSite site = new DocsSite(Fixtures.SITE, root.resolve("cache"), DocsSite.Mode.CACHED, Fixtures::serve);

		List<String> reports = Generate.generate(site, library, root.resolve("constants"), Fixtures.facts(),
				Corrections.parse("", "bindings.txt"), Corrections.parse(""));

		for (String name : List.of("lantern", "lamps", "events")) {
			assertEquals(Fixtures.read(name + ".lua"),
					Files.readString(library.resolve("core/" + name + ".lua"), StandardCharsets.UTF_8), name);
		}
		assertEquals(Fixtures.read("base.lua"), Files.readString(library.resolve("base.lua"), StandardCharsets.UTF_8));
		// The home page and the guide document none of the API, and the removed page's file and folder are gone
		assertFalse(Files.exists(library.resolve("index.lua")));
		assertFalse(Files.exists(library.resolve("guide.lua")));
		assertFalse(Files.exists(library.resolve("gone")));
		// The tests' Lantern names types the site does not define, and one fact is needed by no page
		assertEquals(List.of(
				"core/lantern.html: names the type Light, which no page defines.",
				"core/lantern.html: names the type Player, which no page defines.",
				"core/lantern.html: names the type Vector4, which no page defines.",
				"facts.txt: global Nowhere, which no page needs any more."), reports);
	}

	@Test
	void givesATypeTheFieldsOfAnother() throws IOException {
		Path library = root.resolve("library");
		DocsSite site = new DocsSite(Fixtures.SITE, root.resolve("cache"), DocsSite.Mode.CACHED, Fixtures::serve);
		Facts facts = Facts.parse(Fixtures.read("test-facts.txt") + "fields lamps.Glow lamps.Wick length shine\n");

		List<String> reports = Generate.generate(site, library, root.resolve("constants"), facts,
				Corrections.parse("", "bindings.txt"), Corrections.parse(""));

		// lamps.Glow, which the page describes in prose alone, has the wick's fields but its length
		String lamps = Files.readString(library.resolve("core/lamps.lua"), StandardCharsets.UTF_8);
		assertTrue(lamps.contains("""
				---A glow, which lamps give off.
				---@class lamps.Glow
				---@field glow lamps.Glow The glow it gives.
				---@field shade LampShade The shade over it.
				local Glow = {}
				"""), lamps);
		assertTrue(reports.contains("facts.txt: lamps.Glow leaves out shine, which lamps.Wick does not have."),
				reports::toString);
	}

	@Test
	void readsEachKindOfFact() {
		Facts facts = Facts.parse("""
				global Store
				caller Vector
				name core/a.html#b Thing
				parent Vector2 Vector
				rename core/a.html#c Reflect
				returns core/events.html#draw boolean
				list core/a.html#overview game.options ClientOption
				skip core/a.html#d
				signature core/a.html#e function World.Remove(name : String)
				owner core/a.html#f ui.Carousel
				field core/a.html#g isPaused Bool
				alias Combo ui.ComboField
				fields ui.TextDataConfig ui.TextData content
				""");
		assertEquals(true, facts.isGlobal("Store"));
		assertEquals(true, facts.isCaller("Vector"));
		assertEquals("Thing", facts.name("core/a.html#b"));
		assertEquals("Vector", facts.parent("Vector2"));
		assertEquals("Reflect", facts.rename("core/a.html#c"));
		assertEquals("boolean", facts.returns("core/events.html#draw"));
		assertEquals(List.of("game.options", "ClientOption"), facts.list("core/a.html#overview"));
		assertEquals(true, facts.skips("core/a.html#d"));
		assertEquals("function World.Remove(name : String)", facts.signature("core/a.html#e"));
		assertEquals("ui.Carousel", facts.owner("core/a.html#f"));
		assertEquals("Bool", facts.field("core/a.html#g", "isPaused"));
		assertEquals("ui.ComboField", facts.aliases().get("Combo"));
		assertEquals(List.of("ui.TextData", "content"), facts.fields("ui.TextDataConfig"));
		assertNull(facts.name("core/a.html#c"));
		assertEquals(List.of(), facts.unused());
	}

	@Test
	void aFactTheGeneratorDoesNotKnowFails() {
		assertThrows(IllegalArgumentException.class, () -> Facts.parse("local Store\n"));
		assertThrows(IllegalArgumentException.class, () -> Facts.parse("rename core/a.html#c\n"));
	}

	@Test
	void wrapsALongParagraphBetweenWords() {
		String paragraph = "word ".repeat(40).strip();
		List<String> lines = LuaCatsWriter.wrap(paragraph);
		assertEquals(2, lines.size());
		assertEquals(paragraph, String.join(" ", lines));
	}
}
