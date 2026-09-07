package nl.digitalis.fhirhub.gstandaard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;
import nl.digitalis.fhirhub.model.CodedItem;
import nl.digitalis.fhirhub.model.MedicationCodes;

/** Exercises the medcode lookup against H2 standing in for the G-Standaard database. */
class MedicationCodeResolverTest {

	private static MedicationCodeResolver resolver;

	@BeforeAll
	static void setUp() {
		// H2's own DataSource: the resolver takes the JDBC interface, so no pool is needed to
		// exercise it.
		JdbcDataSource dataSource = new JdbcDataSource();
		dataSource.setURL(
				"jdbc:h2:mem:medcode-test;DB_CLOSE_DELAY=-1;INIT=RUNSCRIPT FROM 'classpath:gstandaard-medcode.sql'");
		dataSource.setUser("sa");
		dataSource.setPassword("");

		resolver = new MedicationCodeResolver(dataSource);
	}

	@Test
	void resolvesAPrkToItsGpk() {
		MedicationCodes codes = resolver.resolve(List.of(new CodedItem("PRK", "18996"))).getFirst();

		assertThat(codes.prk()).isEqualTo(18996);
		assertThat(codes.gpk()).isEqualTo(111111);
		// HPK is only reported when the host identified the drug at that level.
		assertThat(codes.hpk()).isNull();
		assertThat(codes.atc())
				.as("read off the same row, because a beslisregel selects on ATC more often than on a product code")
				.isEqualTo("N02BE01");
	}

	/**
	 * A product the G-Standaard carries no ATC for — a bandage, a homeopathic product — resolves
	 * like any other and reports the ATC as absent. It is the builder, not this, that knows the
	 * schema spells that {@code ZZZZZZ}.
	 */
	@Test
	void reportsAnAbsentAtcAsAbsentRatherThanBlank() {
		MedicationCodes codes = resolver.resolve(List.of(new CodedItem("PRK", "12345"))).getFirst();

		assertThat(codes.gpk()).isEqualTo(333333);
		assertThat(codes.atc()).isNull();
	}

	@Test
	void resolvesAnHpkToItsPrkAndGpk() {
		MedicationCodes codes = resolver.resolve(List.of(new CodedItem("HPK", "2106"))).getFirst();

		assertThat(codes.prk()).isEqualTo(43800);
		assertThat(codes.gpk()).isEqualTo(222222);
		assertThat(codes.hpk()).isEqualTo(2106);
	}

	@Test
	void resolvesSeveralDrugsInOrder() {
		List<MedicationCodes> codes = resolver.resolve(List.of(
				new CodedItem("PRK", "18996"),
				new CodedItem("HPK", "2106")));

		assertThat(codes).extracting(MedicationCodes::prk).containsExactly(18996, 43800);
	}

	/**
	 * An unresolvable drug must abort the session. Dropping it would leave medication
	 * surveillance answering "no interaction" over an incomplete list — a false negative the
	 * prescriber cannot distinguish from a genuine all-clear.
	 */
	@Test
	void refusesADrugThatGStandaardDoesNotKnow() {
		assertThatThrownBy(() -> resolver.resolve(List.of(new CodedItem("PRK", "404040"))))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("medication surveillance");
	}

	@Test
	void refusesAPrkThatHasNoGpk() {
		assertThatThrownBy(() -> resolver.resolve(List.of(new CodedItem("PRK", "99999"))))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("99999");
	}

	@Test
	void refusesANonNumericCode() {
		assertThatThrownBy(() -> resolver.resolve(List.of(new CodedItem("PRK", "not-a-code"))))
				.isInstanceOf(InvalidRequestException.class)
				.hasMessageContaining("not numeric");
	}

	@Test
	void resolvesNothingForAPatientOnNoMedication() {
		assertThat(resolver.resolve(List.of())).isEmpty();
	}
}
