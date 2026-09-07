package nl.digitalis.fhirhub.gstandaard;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import ca.uhn.fhir.rest.server.exceptions.InternalErrorException;
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;
import nl.digitalis.fhirhub.model.CodedItem;
import nl.digitalis.fhirhub.model.MedicationCodes;
import nl.digitalis.fhirhub.prescriptor.CodeSystemTokens;

/**
 * Resolves the patient's current medication to the full G-Standaard code set.
 *
 * <p>A host identifies a drug by one code, PRK or HPK. Prescriptor's medication surveillance
 * needs PRK and GPK together (plus HPK when the host had it), so each drug is looked up in the
 * {@code medcode} view of the G-Standaard database before the session is opened.
 *
 * <h2>Why this fails loudly</h2>
 * An unresolvable code aborts the session rather than opening one with an incomplete
 * medication list. Surveillance that silently receives fewer drugs than the patient is taking
 * does not fail visibly: it answers "no interaction found", which is a false negative in the
 * dangerous direction, and a prescriber cannot tell it apart from a genuine all-clear. So the
 * safe behaviour is to refuse and name the code that could not be resolved.
 */
@Service
public class MedicationCodeResolver {

	private static final Logger log = LoggerFactory.getLogger(MedicationCodeResolver.class);

	/**
	 * One row of {@code medcode} carries the whole chain, so a single lookup serves both
	 * directions. A drug can appear on several rows — one per packaging — so the query is
	 * ordered and the first row wins; every row carries the same PRK+GPK pair, which is all
	 * medication surveillance needs.
	 *
	 * <p>{@code atc} comes along because the view already joins BST711T to get it and the
	 * {@code /fhir/surveillance} contract needs it on every drug it sends. It is the same row and
	 * the same round trip: asking for it costs nothing and asking for it separately would cost a
	 * second query per drug.
	 */
	private static final String BY_PRK = """
			SELECT prk, gpk, hpk, atc
			FROM medcode
			WHERE prk = ?
			AND gpk > 0
			ORDER BY gpk
			""";

	private static final String BY_HPK = """
			SELECT prk, gpk, hpk, atc
			FROM medcode
			WHERE hpk = ?
			AND prk > 0
			AND gpk > 0
			ORDER BY prk
			""";

	private final DataSource dataSource;

	public MedicationCodeResolver(DataSource dataSource) {
		this.dataSource = dataSource;
	}

	public List<MedicationCodes> resolve(List<CodedItem> medications) {
		List<MedicationCodes> resolved = new ArrayList<>();
		for (CodedItem medication : medications) {
			resolved.add(resolve(medication));
		}

		return List.copyOf(resolved);
	}

	private MedicationCodes resolve(CodedItem medication) {
		boolean byHpk = CodeSystemTokens.HPK.equals(medication.codeSystem());
		long code = toCode(medication);

		// The first row is taken rather than a single row demanded: a drug on several packagings
		// is normal, and an unknown code is an expected outcome that has to produce the message
		// below rather than an exception from the query layer.
		MedicationCodes match = firstMatch(byHpk ? BY_HPK : BY_PRK, code, byHpk);

		if (match == null) {
			throw new InvalidRequestException(
					"G-Standaard has no product for %s %s, so it cannot take part in medication surveillance"
							.formatted(medication.codeSystem(), medication.code()));
		}

		log.debug("Resolved {} {} to PRK {} / GPK {}",
				medication.codeSystem(), medication.code(), match.prk(), match.gpk());

		return match;
	}

	/**
	 * The whole of this application's JDBC. {@code null} when the code is unknown, which the
	 * caller turns into the 400 that names it.
	 *
	 * <p>A {@link SQLException} is not that case and must not be confused with it: the database
	 * being unreachable means surveillance cannot be run at all, so it becomes a 500 rather than
	 * "no product found". The message is deliberately generic — a connection failure tends to
	 * carry the host, the port and the account name, and none of that belongs in a response to a
	 * caller — with the detail going to the log instead.
	 */
	private MedicationCodes firstMatch(String sql, long code, boolean byHpk) {
		try (Connection connection = dataSource.getConnection();
				PreparedStatement statement = connection.prepareStatement(sql)) {

			statement.setLong(1, code);
			statement.setMaxRows(1);

			try (ResultSet rows = statement.executeQuery()) {
				if (!rows.next()) {
					return null;
				}

				return new MedicationCodes(
						rows.getInt("prk"),
						rows.getInt("gpk"),
						byHpk ? rows.getInt("hpk") : null,
						atc(rows.getString("atc")));
			}
		}
		catch (SQLException e) {
			log.error("G-Standaard lookup failed for code {}", code, e);
			throw new InternalErrorException(
					"The G-Standaard lookup medication surveillance depends on is unavailable");
		}
	}

	/**
	 * The ATC of the product, or {@code null} when the G-Standaard has none.
	 *
	 * <p>Blank is turned into absent here rather than passed on, so that the one place that knows
	 * what an absent ATC means on the wire is the builder that writes it — the schema reserves
	 * {@code ZZZZZZ} for it, and a bandage or a homeopathic product genuinely has none.
	 */
	private String atc(String atc) {
		return atc == null || atc.isBlank() ? null : atc.trim();
	}

	private long toCode(CodedItem medication) {
		try {
			return Long.parseLong(medication.code().trim());
		}
		catch (NumberFormatException e) {
			throw new InvalidRequestException(
					"%s code '%s' is not numeric".formatted(medication.codeSystem(), medication.code()));
		}
	}
}
