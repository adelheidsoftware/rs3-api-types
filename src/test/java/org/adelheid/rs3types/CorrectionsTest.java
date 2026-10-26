package org.adelheid.rs3types;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The corrections to the tests' Lantern page, and what is reported of those that correct nothing. */
class CorrectionsTest {

	private final List<String> reports = new ArrayList<>();

	@Test
	void correctsEachKindOfMember() {
		ApiPage page = correct("""
				rename Lantern.brightness glow                       # client: a test
				type Lantern.tags table<integer, string>             # docs: a test
				optional Lantern:GetOil                              # docs: a test
				field Lantern.wick integer -- (Read-only) Its wick.  # client: a test
				param Lantern:Light seconds number                   # client: a test
				remove Lantern:GetOilLevel                           # client: a test
				note Lantern:Light It also warms the room.           # client: a test
				parent Lantern ui.Component                          # docs: a test
				read-only Lantern.owner                              # client: a test
				""");
		ApiPage.TypeDoc lantern = page.types().get(0);
		assertEquals(List.of(new ApiPage.PropertyDoc("glow", "Int", "How bright."),
				new ApiPage.PropertyDoc("tags", "lua:table<integer, string>", "Its tags."),
				new ApiPage.PropertyDoc("owner", "Player?", "(Read-only) Who owns it."),
				new ApiPage.PropertyDoc("wick", "lua:integer", "(Read-only) Its wick.")), lantern.properties());
		assertEquals(List.of("new", "Light", "SetColour", "GetOil"),
				lantern.functions().stream().map(ApiPage.FunctionDoc::name).toList());
		ApiPage.FunctionDoc light = lantern.functions().get(1);
		// The parameter may no longer be left out
		assertEquals(new Signature.Param("seconds", "lua:number"), light.forms().get(0).params().get(0));
		assertEquals(new ApiPage.ParamDoc("seconds", "lua:number", true, "How long it stays lit."),
				light.params().get(0));
		assertEquals(List.of("Lights the lantern.", "Warning: It burns `oil`.",
				"In the client: It also warms the room."), light.description());
		assertEquals(List.of("Int?"), lantern.functions().get(3).forms().get(0).returns());
		assertEquals("ui.Component", lantern.parent());
		assertEquals(List.of(), reports);
	}

	@Test
	void replacesAndAddsFunctionsFormsAndAddsClassesAndValues() {
		ApiPage page = correct("""
				function Lantern:SetColour fun(colour: Vector4)               # client: a test
				function Lantern:SetColour fun(colour: Vector4, alpha: number) # client: a test
				function Lantern:Snuff fun(seconds?: number): boolean -- Puts it out.   # client: a test
				class LanternCase core/lantern.html -- A case for a lantern.  # docs: a test
				field LanternCase.size integer                                # docs: a test
				read-only LanternCase                                         # docs: a test
				rename LanternGrants.lightAccess LIGHT_ACCESS                 # client: a test
				values LanternGrants LIGHT_ACCESS=1                           # client: a test
				note LanternGrants Each is a number.                          # client: a test
				no-from-id LanternState                                       # client: a test
				""");
		ApiPage.TypeDoc lantern = page.types().get(0);
		ApiPage.FunctionDoc setColour = lantern.functions().get(2);
		assertEquals(List.of(List.of("colour"), List.of("colour", "alpha")), setColour.forms().stream()
				.map(form -> form.params().stream().map(Signature.Param::name).toList()).toList());
		ApiPage.FunctionDoc snuff = lantern.functions().get(5);
		assertEquals(new ApiPage.FunctionDoc("Snuff", true, List.of(new Signature("Lantern", "Snuff", true,
				List.of(new Signature.Param("seconds", "lua:number?")), List.of("lua:boolean"))),
				List.of("Puts it out."), List.of(), List.of(), "corrections.txt"), snuff);
		ApiPage.TypeDoc lanternCase = page.types().get(1);
		assertEquals("LanternCase", lanternCase.name());
		assertEquals(List.of("A case for a lantern."), lanternCase.description());
		assertEquals(List.of(new ApiPage.PropertyDoc("size", "lua:integer", "(Read-only)")), lanternCase.properties());
		assertEquals(new ApiPage.EnumValue("LIGHT_ACCESS", "1", "Allows lighting"),
				page.enums().get(1).values().get(0));
		assertEquals(List.of("In the client: Each is a number."), page.enums().get(1).description());
		// Every enumeration has FromID, but for one a no-from-id line names
		assertEquals(false, page.enums().get(0).fromId());
		assertEquals(true, page.enums().get(1).fromId());
		assertEquals(List.of(), reports);
	}

	@Test
	void reportsACorrectionThatMatchesNothingOrChangesNothing() {
		correct("""
				rename Lantern.colour hue              # client: no such field
				param Lantern:Light colour number      # client: no such parameter
				values LanternGrants darkAccess=2      # client: no such name
				parent Lantern Light                   # docs: the page says so itself
				values LanternState LIT=1              # docs: the page says so itself
				""");
		assertEquals(List.of(
				"corrections.txt: rename Lantern.colour hue              # client: no such field, which matches"
						+ " nothing in the docs.",
				"corrections.txt: param Lantern:Light colour number      # client: no such parameter, which matches"
						+ " nothing in the docs.",
				"corrections.txt: values LanternGrants darkAccess=2      # client: no such name, which matches nothing"
						+ " in the docs.",
				"corrections.txt: parent Lantern Light                   # docs: the page says so itself, which the"
						+ " docs now say themselves.",
				"corrections.txt: values LanternState LIT=1              # docs: the page says so itself, which the"
						+ " docs now say themselves."), reports);
	}

	@Test
	void aLineWithoutItsEvidenceOrOfAnUnknownFormFails() {
		assertThrows(IllegalArgumentException.class, () -> Corrections.parse("remove Lantern.tags\n"));
		assertThrows(IllegalArgumentException.class, () -> Corrections.parse("hide Lantern.tags # client\n"));
		assertThrows(IllegalArgumentException.class, () -> Corrections.parse("parent Lantern # docs\n"));
		// Only a line that adds what the docs leave out takes a description
		assertThrows(IllegalArgumentException.class,
				() -> Corrections.parse("type Lantern.tags string[] -- Its tags. # docs\n"));
	}

	private ApiPage correct(String corrections) {
		ApiPage lantern = PageReader.read("core/lantern.html", Fixtures.read("lantern.html"), Facts.parse(""),
				new ArrayList<>());
		return Corrections.parse(corrections).apply(List.of(lantern), reports).get(0);
	}
}
