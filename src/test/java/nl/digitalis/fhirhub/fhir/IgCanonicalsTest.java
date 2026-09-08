package nl.digitalis.fhirhub.fhir;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * The IG under {@code ig/} and the constants in this package have to agree on every canonical
 * URL, because they meet only on the wire.
 *
 * <p>SUSHI derives a canonical as {@code {canonical}/StructureDefinition/{Id}}, so the FSH ids
 * are load-bearing: renaming one is a silent breaking change for every payload already in the
 * field, and nothing else in either build would notice. This test is the thing that notices.
 */
class IgCanonicalsTest {

	private static final Path FSH = Path.of("ig", "input", "fsh");

	private static final String CANONICAL = Profiles.CANONICAL;

	@Test
	void theIgDefinesTheExtensionsAtTheUrlsThisServiceEmits() throws IOException {
		String extensions = read("extensions.fsh");

		assertThat(idIn(extensions, DigitalisExtensions.CODED_DIRECTIONS))
				.as("CodedDirections is declared in the IG at the URL fhir-hub emits")
				.isTrue();
		assertThat(idIn(extensions, DigitalisExtensions.OPIUM_ACT_CLASSIFICATION))
				.as("OpiumActClassification is declared in the IG at the URL fhir-hub emits")
				.isTrue();
	}

	@Test
	void theIgDefinesTheDigitalisCodeSystem() throws IOException {
		assertThat(idIn(read("terminology.fsh"), Systems.G_STANDAARD_BIJZONDER_KENMERK)).isTrue();
	}

	/**
	 * The national OIDs have to be the ones in the value sets, or the profiles would bind
	 * something other than what the service emits.
	 */
	@Test
	void theIgBindsTheNationalGStandaardOids() throws IOException {
		String terminology = read("terminology.fsh");

		assertThat(terminology)
				.contains(Systems.G_STANDAARD_SSK)
				.contains(Systems.G_STANDAARD_SNK)
				.contains(Systems.G_STANDAARD_OGGRP)
				.contains(Systems.G_STANDAARD_CONTRA_INDICATIE);
	}

	/** The OIDs are quoted into the profiles by hand, so they are worth pinning too. */
	@Test
	void theIgUsesTheSameOidsAsSystems() throws IOException {
		String terminology = read("terminology.fsh");

		assertThat(terminology)
				.contains(Systems.ICPC_1_NL)
				.contains(Systems.PRK)
				.contains(Systems.HPK)
				.contains(Systems.GPK)
				.contains(Systems.ATC);
	}

	/**
	 * The IG's canonical and the constants have to name the same host. SUSHI derives every URL
	 * from sushi-config.yaml, so a canonical edited there and nowhere else would silently
	 * republish every artifact at an address this service does not use.
	 */
	@Test
	void theIgCanonicalMatchesTheConstants() throws IOException {
		assertThat(Files.readString(Path.of("ig", "sushi-config.yaml")))
				.contains("canonical: " + CANONICAL);

		assertThat(DigitalisExtensions.CODED_DIRECTIONS).startsWith(CANONICAL + "/");
		assertThat(DigitalisExtensions.OPIUM_ACT_CLASSIFICATION).startsWith(CANONICAL + "/");
		assertThat(Systems.G_STANDAARD_BIJZONDER_KENMERK).startsWith(CANONICAL + "/");
		assertThat(Profiles.FORMULARY_SESSION_INPUT).startsWith(CANONICAL + "/");
		assertThat(Profiles.CREATERX_SESSION_INPUT).startsWith(CANONICAL + "/");
		assertThat(Profiles.SESSION_OUTPUT).startsWith(CANONICAL + "/");
		assertThat(Profiles.RESULT_BUNDLE).startsWith(CANONICAL + "/");
		assertThat(Profiles.SURVEILLANCE_INPUT).startsWith(CANONICAL + "/");
		assertThat(Profiles.SURVEILLANCE_STATEMENT_INPUT).startsWith(CANONICAL + "/");
	}

	/**
	 * Every conformance resource the jar ships has to declare the IG's version.
	 *
	 * <p>SUSHI stops stamping {@code version} once it is generating a real Implementation Guide —
	 * the IG Publisher applies it on the way into {@code output/} instead. That is fine for the
	 * published site and wrong here, because {@code ig/fsh-generated/resources} is copied into the
	 * jar and validated against at runtime: unstamped, this service would enforce version-less
	 * profiles while the published package says 0.2.0, and an {@code OperationOutcome} would stop
	 * naming the version the rule came from. {@code ig/scripts/stamp-version.mjs} puts it back, and
	 * this test is what notices when someone runs {@code sushi .} directly instead of
	 * {@code npm run sushi}.
	 */
	@Test
	void theProfilesCarryTheIgVersion() throws IOException {
		String version = versionFromSushiConfig();
		List<Path> unstamped = new ArrayList<>();

		try (Stream<Path> files = Files.list(Path.of("ig", "fsh-generated", "resources"))) {
			for (Path file : files.sorted().toList()) {
				String name = file.getFileName().toString();
				boolean conformance = name.startsWith("StructureDefinition-")
						|| name.startsWith("ValueSet-")
						|| name.startsWith("CodeSystem-");

				if (conformance && !Files.readString(file).contains("\"version\": \"" + version + "\"")) {
					unstamped.add(file);
				}
			}
		}

		assertThat(unstamped)
				.as("conformance resources declaring version %s — run 'npm run sushi' in ig/", version)
				.isEmpty();
	}

	/**
	 * A profile constant that names nothing is the failure this whole file exists to prevent, and
	 * it is invisible at compile time: {@code ProfileValidator} would ask the validator for a
	 * canonical nothing defines, and the reference validator answers an unresolvable profile with
	 * a pass rather than an error — so the payload would be accepted unchecked.
	 */
	@Test
	void everyProfileConstantNamesAProfileTheIgDefines() throws IOException {
		String fsh = String.join("\n", read("profiles-parameters.fsh"), read("profiles-output.fsh"),
				read("profiles-surveillance.fsh"));

		for (String canonical : List.of(Profiles.FORMULARY_SESSION_INPUT, Profiles.CREATERX_SESSION_INPUT,
				Profiles.SESSION_OUTPUT, Profiles.RESULT_BUNDLE, Profiles.SURVEILLANCE_INPUT,
				Profiles.SURVEILLANCE_STATEMENT_INPUT)) {
			assertThat(idIn(fsh, canonical)).as("%s is defined in ig/input/fsh", canonical).isTrue();
		}
	}

	private String versionFromSushiConfig() throws IOException {
		Matcher version = Pattern.compile("^version: *\"?([^\"\\s]+)\"?$", Pattern.MULTILINE)
				.matcher(Files.readString(Path.of("ig", "sushi-config.yaml")));

		assertThat(version.find()).as("sushi-config.yaml declares a version").isTrue();

		return version.group(1);
	}

	/** SUSHI turns "Id: x" into {canonical}/StructureDefinition/x — or /CodeSystem/x. */
	private boolean idIn(String fsh, String canonical) {
		String id = canonical.substring(canonical.lastIndexOf('/') + 1);

		return fsh.contains("Id: " + id + "\n");
	}

	private String read(String file) throws IOException {
		Path path = FSH.resolve(file);
		assertThat(path).as("the IG source is where the build expects it").exists();

		return Files.readString(path);
	}
}
