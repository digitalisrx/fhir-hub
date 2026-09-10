package nl.digitalis.fhirhub.fhir;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import nl.digitalis.fhirhub.fhir.LabDeterminations.Determination;
import nl.digitalis.fhirhub.fhir.LabDeterminations.NhgEquivalent;

/**
 * The determinations medication surveillance actually reads are written down twice — as a Java
 * table that maps each LOINC code onto its MFB parameter, its caption and the units it accepts,
 * and as the documentation value set the guide renders — and the two meet only in a payload. This
 * is the test that notices when they drift. Neither list is what the profile accepts: any LOINC
 * code validates on {@code Observation.code} (see {@code LoincVS} and
 * {@code TerminologyEnforcementTest}); this list is narrower, and documents what is meaningful.
 */
class LabDeterminationsTest {

	private static final Path VALUE_SET =
			Path.of("ig", "fsh-generated", "resources", "ValueSet-lab-determination.json");

	private final LabDeterminations determinations = new LabDeterminations();

	@Test
	void theDocumentationValueSetListsExactlyTheDeterminationsTheTableCanForward() throws IOException {
		assertThat(codesInTheValueSet())
				.as("LOINC codes in %s", VALUE_SET)
				.containsExactlyInAnyOrderElementsOf(determinations.acceptedCodes());
	}

	@Test
	void everyDeterminationCarriesAUnitACallerMaySend() {
		for (Determination determination : determinations.all()) {
			assertThat(determination.display()).as("display of %s", determination.loinc()).isNotBlank();
			assertThat(determination.acceptedUnits()).as("units of %s", determination.loinc()).isNotEmpty();
			assertThat(determination.acceptedUnits())
					.as("the upstream unit of %s is one a caller may send", determination.loinc())
					.contains(determination.unit());
		}
	}

	/**
	 * Each determination feeds an MFB parameter the G-Standaard defines, except the two the dose
	 * check reads. The numbers are BST685T.MFBPANR — 1 is the nierfunctie, which 666 current rules
	 * test.
	 */
	@Test
	void everyDeterminationNamesTheMfbParameterItFeeds() {
		assertThat(determinations.forLoinc("62238-1").mfbParameter()).isEqualTo(1);
		assertThat(determinations.forLoinc("2951-2").mfbParameter()).isEqualTo(2);
		assertThat(determinations.forLoinc("2823-3").mfbParameter()).isEqualTo(3);
		assertThat(determinations.forLoinc("6298-4").mfbParameter()).isEqualTo(3);
		assertThat(determinations.forLoinc("6301-6").mfbParameter()).isEqualTo(4);
		assertThat(determinations.forLoinc("34714-6").mfbParameter()).isEqualTo(4);
		assertThat(determinations.forLoinc("14334-7").mfbParameter()).isEqualTo(71);
		assertThat(determinations.forLoinc("29247-4").mfbParameter()).isEqualTo(326);

		assertThat(determinations.forLoinc("29463-7").mfbParameter()).as("gewicht").isNull();
		assertThat(determinations.forLoinc("8302-2").mfbParameter()).as("lengte").isNull();
	}

	/** An eGFR is normalised per 1.73 m2, and may only arrive saying so. */
	@Test
	void theEgfrCarriesItsNormalisedUnit() {
		assertThat(determinations.forLoinc("62238-1").acceptedUnits())
				.containsExactly("mL/min/{1.73_m2}");
	}

	/**
	 * A height is centimetres alone. R4 applies its own bodyheight profile to every 8302-2 and
	 * binds the unit to cm or inches, so the metres this class used to convert exactly were a unit
	 * only this interface would take — and a host validating its own payload saw an error for a
	 * value that was accepted here. Inches are refused as well: the G-Standaard is metric.
	 */
	@Test
	void theHeightIsCentimetresAlone() {
		assertThat(determinations.forLoinc("8302-2").acceptedUnits()).containsExactly("cm");
	}

	/**
	 * Nothing is converted today, and the mechanism that would is still exercised: every accepted
	 * unit is the unit the upstream reads, so a factor other than 1 has no determination to prove
	 * it on. Left untested it would rot, and the first determination to need one would find out
	 * upstream.
	 */
	@Test
	void convertsByTheFactorADeterminationDeclares() {
		Determination converting = new Determination("x", 1, "Onder test", "cm",
				Map.of("m", BigDecimal.valueOf(100)), null);

		assertThat(converting.toUpstreamUnit("m", new BigDecimal("1.72")))
				.isEqualByComparingTo("172");
		assertThat(converting.toUpstreamUnit("cm", BigDecimal.ONE)).isNull();
	}

	/**
	 * One eGFR code. The G-Standaard also lists MDRD (77147-7) and cystatin C (50210-4) for the
	 * nierfunctie; Dutch laboratories report CKD-EPI, and re-labelling one formula as another would
	 * put a value in front of a prescriber under a method that did not produce it.
	 */
	@Test
	void theOtherEgfrFormulasAreNotAccepted() {
		assertThat(determinations.forLoinc("77147-7")).as("MDRD").isNull();
		assertThat(determinations.forLoinc("50210-4")).as("cystatine C").isNull();
		assertThat(determinations.forLoinc("33914-3")).as("MDRD, serum or plasma").isNull();
	}

	@Test
	void aDeterminationNoRuleReadsIsNotInTheTable() {
		assertThat(determinations.forLoinc("718-7")).as("hemoglobine").isNull();
		assertThat(determinations.forLoinc("2164-2")).as("gemeten klaring, not in BST684T").isNull();
	}

	private List<String> codesInTheValueSet() throws IOException {
		String json = Files.readString(VALUE_SET, StandardCharsets.UTF_8);
		Matcher codes = Pattern.compile("\"code\"\\s*:\\s*\"([0-9]+-[0-9])\"").matcher(json);

		List<String> found = new java.util.ArrayList<>();
		while (codes.find()) {
			found.add(codes.group(1));
		}

		return found;
	}

	/**
	 * The two determinations the dose check reads carry an NHG identity, and they are the only two.
	 * The correspondence is not a coincidence — a determination with no MFB parameter is one no
	 * beslisregel tests, and the thing that does test it reads NHG — so it is enforced in the
	 * record's constructor and pinned here.
	 */
	@Test
	void onlyTheDeterminationsTheDoseCheckReadsCarryAnNhgIdentity() {
		for (String loinc : determinations.acceptedCodes()) {
			Determination determination = determinations.forLoinc(loinc);

			assertThat(determination.nhg() != null)
					.as("%s has an NHG identity exactly when no MFB parameter reads it", loinc)
					.isEqualTo(determination.mfbParameter() == null);
		}
	}

	@Test
	void namesTheNhgIdentityTheHubSelectsOn() {
		assertThat(determinations.forLoinc("29463-7").nhg())
				.extracting(NhgEquivalent::id, NhgEquivalent::memo, NhgEquivalent::mat)
				.containsExactly(357, "GEW", "AO");
		assertThat(determinations.forLoinc("8302-2").nhg())
				.extracting(NhgEquivalent::id, NhgEquivalent::memo, NhgEquivalent::mat)
				.containsExactly(560, "LNGP", "AO");
	}

	/**
	 * A weight is kilograms in both forms; a length is centimetres in the LOINC one and metres in
	 * the NHG one, which is the unit NHG determination 560 records and the unit the Hub's Mosteller
	 * expression expects.
	 */
	@Test
	void convertsToTheUnitTheNhgDeterminationIsRecordedIn() {
		assertThat(determinations.forLoinc("29463-7").nhg().value("70")).isEqualTo("70");
		assertThat(determinations.forLoinc("8302-2").nhg().value("178")).isEqualTo("1.78");
		assertThat(determinations.forLoinc("8302-2").nhg().value("180")).isEqualTo("1.8");
	}
}
