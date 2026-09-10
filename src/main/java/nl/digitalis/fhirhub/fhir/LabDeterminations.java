package nl.digitalis.fhirhub.fhir;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

/**
 * The laboratory determinations medication surveillance reads, in LOINC.
 *
 * <h2>Any LOINC observation is accepted; this list is what is meaningful</h2>
 * A host may send any LOINC code — nothing here refuses one it does not recognise. What this class
 * holds is narrower: the determinations a beslisregel or the dose-band model actually reads. A code
 * outside this list is accepted, and then forwarded nowhere, because the rules engine has nothing to
 * match it against — sending it upstream would tell a prescriber their lab data had been weighed
 * when nothing read it. Silence, not a 400: unlike an unresolvable drug code, an unread lab value is
 * not something the request depends on, so there is nothing to fail the request over.
 *
 * <h2>The list is the G-Standaard's, not ours</h2>
 * {@code BST684T} publishes, per MFB parameter, the external codes that count as that parameter:
 * rows with {@code MFBEXSRT = 4} are "LOINC / Nederlandse Labcodeset". Those rows are this list.
 * Which measurements can be tested at all is equally fixed: {@code BST685T} rows with
 * {@code THMFBP = 2000}, twelve of them, of which current rules use four.
 *
 * <h2>LOINC travels all the way through</h2>
 * The upstream takes lab data as either {@code <LOINC num="…">} or {@code <NHG memo mat bijz>}, and
 * the MFB datatest generator builds a {@code DatatestLOINC} keyed on the LOINC number for exactly
 * the codes listed here. So nothing is translated: the code a host sends is the code the engine
 * tests. That is why there is no NHG Tabel 45 mapping in this codebase — it would add a translation
 * step, a table to maintain, and a class of determinations that cannot be forwarded because no NHG
 * code exists for them.
 *
 * <h2>Units are enforced, and each code carries its own</h2>
 * The number is evaluated in the unit the rule was written in, so it has to arrive in that unit: a
 * kalium in mg/dL rather than mmol/L is a different answer, not a rounded one, and nothing
 * downstream could notice. Each determination therefore lists the UCUM codes it accepts, with the
 * factor to the unit the upstream wants, and refuses anything else. An eGFR must arrive as
 * {@code mL/min/{1.73_m2}} — its own unit — rather than as {@code mL/min}, so the payload cannot be
 * ambiguous about which quantity it carries. A lengte must arrive as {@code cm}: metres were
 * accepted and converted until R4's own {@code bodyheight} profile, which the validator applies to
 * every {@code 8302-2} whatever profile the resource claims, turned out to bind the unit to
 * {@code cm} or {@code [in_i]}. An accepted unit that a host's validator rejects is worse than a
 * narrow one. No determination converts today; the factor stays because the next one may.
 *
 * <h2>What is deliberately not here</h2>
 * The G-Standaard also lists {@code 77147-7} (MDRD) and {@code 50210-4} (cystatin C) for the
 * klaring; Dutch laboratories report CKD-EPI, so only {@code 62238-1} is accepted. Adding either is
 * one line. Note also that the G-Standaard compares every code under parameter 1 against the same
 * ml/min thresholds even though the eGFR codes are normalised per 1.73 m2 — that is its decision,
 * not one this interface can make, and it is the reason the unit is pinned per code here.
 */
@Component
public class LabDeterminations {

	/**
	 * One determination: the LOINC code a host sends, the MFB parameter it feeds, and the units the
	 * value may arrive in.
	 *
	 * @param mfbParameter {@code BST685T.MFBPANR}, or null for the two the dose check reads rather
	 *                     than the rules
	 * @param factorByUnit UCUM code to the factor for {@link #unit} — 1 for every determination as
	 *                     it stands, since the one conversion there was, {@code m} to {@code cm} at
	 *                     100, was dropped with metres
	 * @param nhg          the NHG identity to send <em>as well</em>, for the two determinations the
	 *                     dose check reads; null for everything the rules read. See
	 *                     {@link NhgEquivalent}
	 */
	public record Determination(
			String loinc,
			Integer mfbParameter,
			String display,
			String unit,
			Map<String, BigDecimal> factorByUnit,
			NhgEquivalent nhg) {

		public Determination {
			factorByUnit = Map.copyOf(factorByUnit);

			// The two facts are the same fact: a determination with no MFB parameter is not read by
			// a beslisregel at all, and the only thing that does read it reads NHG.
			if ((mfbParameter == null) != (nhg != null)) {
				throw new IllegalStateException(
						"Determination " + loinc + ": an NHG identity is for the determinations the dose"
								+ " check reads, which are exactly those with no MFB parameter");
			}
		}

		/** The value in {@link #unit}, or null when the unit is not one this determination accepts. */
		public BigDecimal toUpstreamUnit(String ucumCode, BigDecimal value) {
			BigDecimal factor = ucumCode == null ? null : factorByUnit.get(ucumCode);

			return factor == null ? null : value.multiply(factor);
		}

		/** The UCUM codes a caller may use, for the guide and for a rejection message. */
		public List<String> acceptedUnits() {
			return factorByUnit.keySet().stream().sorted().toList();
		}
	}

	/**
	 * The NHG identity of a determination, for the one consumer that cannot read a LOINC code.
	 *
	 * <p><strong>This is the exception to "no NHG mapping", and it is two rows wide.</strong> The
	 * rule stands for everything the beslisregels read: those are keyed on LOINC numbers by the MFB
	 * datatest generator, so translating them would add a table to maintain and a class of
	 * determinations that cannot be expressed at all. Weight and height are not in that group —
	 * they have no MFB parameter, no rule tests them, and the thing that does read them is the
	 * G-Standaard dose-band model, which the Hub feeds from {@code <NHG id="357">} and
	 * {@code <NHG id="560">} and from nothing else. For these two, LOINC-only means "not read":
	 * measured, before this existed, as a dose check that answered "gewicht ontbreekt" for a
	 * request that carried a weight.
	 *
	 * <p>So the NHG element is written <em>beside</em> the LOINC one rather than instead of it, and
	 * only on the surveillance contract. Adding a third row means finding a consumer that reads it
	 * and cannot read LOINC; that is the test, not tidiness.
	 *
	 * @param id     {@code NHG[@id]}, which is what the Hub's dose check selects on
	 * @param memo   the determination's mnemonic, which is what {@code evs2.0} and the rules engine
	 *               select on — {@code TCRELabValueNHG.Matches} compares memo, mat and bijz
	 * @param mat    material, {@code AO} for a measurement taken on the patient
	 * @param bijz   particularity, or null where the determination has none
	 * @param factor from the unit this interface holds the value in to the unit the NHG
	 *               determination is recorded in. Centimetres to metres for height, and nothing
	 *               for a weight, which is kilograms on both sides
	 */
	public record NhgEquivalent(int id, String memo, String mat, String bijz, BigDecimal factor) {

		/** The value as the NHG determination records it, from the value this interface holds. */
		public String value(String upstreamValue) {
			return new BigDecimal(upstreamValue).multiply(factor).stripTrailingZeros().toPlainString();
		}
	}

	private static final BigDecimal AS_IS = BigDecimal.ONE;

	private static final Map<String, BigDecimal> CONCENTRATION = Map.of("mmol/L", AS_IS);

	/** INR is a ratio. UCUM writes a dimensionless quantity as 1, and {INR} is the annotated form. */
	private static final Map<String, BigDecimal> RATIO = Map.of("{INR}", AS_IS, "1", AS_IS);

	private final Map<String, Determination> byLoinc = new LinkedHashMap<>();

	public LabDeterminations() {
		// Nierfunctie — MFB parameter 1, tested by 666 current rules, which is what medication
		// surveillance turns on. The unit is the code's own: an eGFR is normalised per 1.73 m2.
		add("62238-1", 1, "eGFR volgens CKD-EPI", "mL/min/{1.73_m2}",
				Map.of("mL/min/{1.73_m2}", AS_IS));

		// Kalium — MFB parameter 3, 4 current rules, thresholds at 4.5 and 5.0 mmol/l. Serum or
		// plasma and whole blood are two LOINC codes and one parameter; the G-Standaard lists both.
		add("2823-3", 3, "Kalium (serum of plasma)", "mmol/L", CONCENTRATION);
		add("6298-4", 3, "Kalium (bloed)", "mmol/L", CONCENTRATION);

		// INR — MFB parameter 4, 3 current rules, all of them about how old the value is: "is de
		// INR max. 24 uur oud".
		add("6301-6", 4, "INR (trombocytenarm plasma)", "{INR}", RATIO);
		add("34714-6", 4, "INR (bloed)", "{INR}", RATIO);

		// Sirolimusdalspiegel — MFB parameter 326, 1 current rule.
		add("29247-4", 326, "Sirolimus Cmin", "ug/L", Map.of("ug/L", AS_IS));

		// Natrium and lithium — MFB parameters 2 and 71. No current rule tests them, but they are
		// current parameters with a LOINC code of their own, so a future rule will find them.
		add("2951-2", 2, "Natrium (serum of plasma)", "mmol/L", CONCENTRATION);
		add("14334-7", 71, "Lithiumspiegel", "mmol/L", CONCENTRATION);

		// Gewicht and lengte are read by dose checking rather than by the rules: 45.700 dose bands
		// in BST643T carry a minimum weight and 1.215 a body surface bound, and evs2.0 reads both
		// out of laboratoryData by these LOINC codes.
		//
		// One unit each, and for lengte that is narrower than the conversion this class could do:
		// R4 makes the core bodyheight profile mandatory for 8302-2 and binds its unit to
		// ucum-bodylength, which has cm and [in_i] and no m. Accepting metres meant accepting a
		// payload that a host's own validator rejects, so the exact conversion was dropped rather
		// than left as a second spelling only this interface honours. Inches are not accepted
		// either: the G-Standaard is metric and a host sending them is a host to talk to first.
		//
		// They are also the two determinations that carry an NHG identity, because the Hub's dose
		// check reads them by NHG id and cannot read a LOINC code — see NhgEquivalent for why that
		// is not the NHG mapping this class refuses to have.
		add("29463-7", null, "Gewicht", "kg", Map.of("kg", AS_IS),
				new NhgEquivalent(357, "GEW", "AO", null, AS_IS));
		// NHG 560 records a length in metres, so the centimetres this interface holds are divided
		// back down. That is the unit the Hub's Mosteller expression expects — it multiplies the
		// value it finds by 100 to get the centimetres the formula wants.
		add("8302-2", null, "Lengte", "cm", Map.of("cm", AS_IS),
				new NhgEquivalent(560, "LNGP", "AO", null, new BigDecimal("0.01")));
	}

	private void add(String loinc, Integer mfbParameter, String display, String unit,
			Map<String, BigDecimal> factorByUnit) {
		add(loinc, mfbParameter, display, unit, factorByUnit, null);
	}

	private void add(String loinc, Integer mfbParameter, String display, String unit,
			Map<String, BigDecimal> factorByUnit, NhgEquivalent nhg) {
		byLoinc.put(loinc, new Determination(loinc, mfbParameter, display, unit, factorByUnit, nhg));
	}

	/**
	 * The determination for a LOINC code, or null when it is a code medication surveillance does not
	 * read — which is not a rejection, since any LOINC code is valid input; see the class Javadoc.
	 */
	public Determination forLoinc(String loincCode) {
		return loincCode == null ? null : byLoinc.get(loincCode);
	}

	/** Every LOINC code that feeds a beslisregel or the dose-band model, in the order listed above. */
	public List<String> acceptedCodes() {
		return List.copyOf(byLoinc.keySet());
	}

	/** Every determination this class holds, so the documentation can be checked against it. */
	public List<Determination> all() {
		return List.copyOf(byLoinc.values());
	}
}
