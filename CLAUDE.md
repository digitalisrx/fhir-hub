# CLAUDE.md

Guidance for Claude Code (claude.ai/code) when working in this repository.

## What this is

One FHIR R4 interface in front of **two applications**, on two FHIR bases.

**Prescriptor**, at `/fhir/evs`, is the larger of the two: functionally equivalent to **v2** of
`../json-interface` (Node/TypeScript) with authentication moved from the request body to HTTP Basic.
Stateless proxy: FHIR in, XML-RPC to Prescriptor, FHIR out. No session store. It does read the
G-Standaard database, read-only, to resolve current medication for medication surveillance.

**Surveillance**, at `/fhir/surveillance`, answers `$check-medication`: FHIR in, a `DigitalisRx`
document over SOAP to the **Digitalis Hub** (`../hub`), a `Bundle` of `DetectedIssue` out. It runs
both halves of Dutch medication surveillance — the G-Standaard's medisch-farmaceutische
beslisregels through the clinical-rules engine, and the classic allergy, age, duplicate-medication
and dose checks — and it reads the same G-Standaard view, for the same PRK + GPK pair plus the ATC.
See *The second base, and what decides whether a check happens* below. Unless a note says
otherwise, everything in this file is about the Prescriptor contract.

Read `README.md` first — it holds the operation contracts, the full JSON→FHIR mapping table,
and the open items. This file covers what the README does not: why the code is shaped as it is.

## Commands

```bash
mvn test                 # 170 tests; no network and no database — WireMock stubs
                         # Prescriptor and the Hub, H2 stands in for the medcode view
mvn spring-boot:run
mvn -o test -Dtest=X     # single test class
mvn cyclonedx:makeBom    # target/sbom.{json,xml}; not built by an ordinary install
mvn -Psbom clean verify  # ...or a release build, with the SBOM attached to the artifact
node tools/soup-list.mjs # docs/SOUP.md, from target/sbom.json
docker compose up --build

cd ig && npm run sushi   # rebuild the profiles (SUSHI + the version stamp)
cd ig && npm run build   # the whole published guide: pages -> SUSHI -> IG Publisher
# publishing: deploy.sh runs ON the web server; see ig/README.md for the tar-over-ssh push
cd ig && hosting/deploy.sh /srv/spec.digitalis.nl && hosting/verify.sh https://spec.digitalis.nl
```

`npm run build` runs its own preflight and names whatever is missing: Java, Node, Jekyll
(`gem install --user-install jekyll`) or `publisher.jar` in `ig/.pkg/`. Run it rather than the
publisher directly — it also assembles the PATH the publisher needs to find jekyll, which is a
failure that otherwise surfaces as a Java stack trace with no mention of gems. See
`ig/README.md`.

## Stack

Java 25, Spring Boot 4.1.1, HAPI FHIR 8.12.0, Maven. Matches the house backend convention
(`formularium-api`, `nhg-formularium-adapter`, `gstandaard-jar`): **no Spring Boot parent
POM**, explicit versions via properties, package `nl.digitalis.*`, tabs for indentation.

Boot's `spring-boot-dependencies` BOM *is* imported into `dependencyManagement`, which is not
the parent POM and does not change that convention — every direct dependency still declares its
own version, and those win. The BOM governs **transitive** versions only, because without it
nearest-wins silently downgraded third-party transitives. `maven-enforcer-plugin`
(`requireUpperBoundDeps`) fails the build if that starts happening again, so a dependency change
that reintroduces a downgrade is a build failure rather than a runtime surprise.

**No third-party `nl.digitalis` libraries.** fhir-hub deliberately depends on nothing from the
house except what it declares itself; see *No house libraries, and `gstandaard-jar` least of
all* below.

## Architecture

Request flow, and the one place each concern lives:

```
HTTP  →  BasicAuthenticationFilter              practiceId + licenseKey off the Basic header
      →  CredentialsResolver                   …and back off the thread, for the providers
      →  server/*Provider                        HAPI @Operation methods
      →  validation/ProfileValidator             Parameters       → 400 if it fails its profile

  /fhir/evs
      →  fhir/SessionParametersMapper            Parameters       → internal model
                                                  (xis, prescription, ICPC + URL validation)
      →  gstandaard/MedicationCodeResolver       PRK|HPK          → PRK+GPK(+HPK) via JDBC
      →  prescriptor/XmlRpcRequestBuilder        internal model   → XML-RPC
      →  prescriptor/PrescriptorClient           the only HTTP call out to Prescriptor
      →  prescriptor/XmlRpcResponseParser        XML-RPC          → internal model
      →  fhir/ResultBundleMapper                 internal model   → Bundle

  /fhir/surveillance
      →  fhir/SurveillanceParametersMapper       Parameters       → internal model
                                                  (proposals vs dossier, dosing, dates)
      →  gstandaard/MedicationCodeResolver       PRK|HPK          → PRK+GPK(+HPK)+ATC via JDBC
      →  hub/MedicationSurveillanceRequestBuilder  internal model → SOAP + DigitalisRx
      →  hub/HubClient                           the only HTTP call out to the Hub
      →  hub/MedicationSurveillanceResponseParser  report         → internal model
      →  fhir/SurveillanceBundleMapper            internal model  → Bundle of DetectedIssue
```

`fhir/ClinicalContextMapper` sits behind both parameter mappers and holds what the two contracts
share: gender, birth date, allergies, conditions, current medication, lab determinations and the
`xisId`/`xisVersion` pair. Every rule in it is safety-relevant — which `Coding.system` is routed,
which LOINC codes are read, which unit a value must arrive in — and two copies of a fail-closed
rule is one copy that gets softened by accident. `xml/XmlWriter` is shared the same way and for the
same reason: the escaping is the same escaping whichever upstream is being written to.

`model/` is plain records with no FHIR and no XML types. That boundary is the point: the FHIR
mappers and the XML-RPC layer never see each other's types, so either side can change without
dragging the other along.

**There are two FHIR bases and two upstreams.** They share the filter, the validator, the profiles
and the G-Standaard lookup, and they share nothing else: `prescriptor/` speaks XML-RPC to
Prescriptor and `hub/` speaks SOAP to the Digitalis Hub. See *The second base, and what decides
whether a check happens* below.

## Things worth knowing before you change something

**The second base, and what decides whether a check happens.**
`POST /fhir/surveillance/$check-medication` asks the medication-surveillance question directly:
patient dossier and proposed prescriptions in, a `Bundle` of `DetectedIssue` out. The upstream is
the **Digitalis Hub** (`../hub`, a Django/spyne SOAP service), which runs the clinical-rules engine
for the G-Standaard's beslisregels and then its own G-Standaard checks — allergy, age, duplicate
medication, dose control — and merges both into one `<report>`.

What follows are the decisions in that flow, and every one of them is easy to undo by accident —
most of them fail as a check that quietly does not run rather than as an error.

**Which drug is being checked is marked twice, and both marks are load-bearing.** The one call
reaches two engines that read different attributes:

- `pending="true"` is what the Hub's own checks select on (`MbFactorySettings.get_pending_medications`).
  `MbFactory.process()` returns immediately if no drug carries it, so *none* of the classic
  G-Standaard checks run — and the response still looks like a complete answer.
- `trigger="true"` is what the rules engine reads (`TCREDrug.ReadFromXML`). It deliberately does
  not treat `pending="true"` as pending; the value it looks for there is `trueevs`, because marking
  the drug under test with `pending` "had too many side effects" (RM#6332).

The two published examples in `DigitalisRx-documentation/` and `../hub/docs/examples_call/` each
carry one of them, which is how the split was found. `MedicationSurveillanceRequestBuilderTest`
pins both.

**Every drug carries a `date` and an `ATC`, and neither is decoration.** Both engines filter the
dossier on the start date — the Hub's xpath compares `substring(@date,1,10)` numerically, and an
absent attribute is not a number — so a drug without one is dropped from the check rather than
rejected. `SurveillanceParametersMapper` therefore reads `effectivePeriod`/`effectiveDateTime`
(or `validityPeriod`/`authoredOn` for a proposal) and falls back to *today*, which is what
`status: active` on a `MedicationStatement` already asserts. And a beslisregel selects on ATC far
more often than on a product code, which is why `MedicationCodeResolver` now reads `atc` off the
same `medcode` row and `MedicationCodes` carries it. Dates go out as `yyyy-mm-dd`: ten characters
is the clean branch of the engine's `StringToDate`.

**An empty `caption` costs the prescriber the substance name, and only the live service shows it.**
The Hub's allergy check interpolates the `caption` attribute of the allergy into the title *and*
the body of the signal it raises, so `caption=""` produced "Allergie  (ongewenste groep)" and "In
het dossier is een allergie ( (SNK)) geregistreerd" — well-formed, schema-valid, and useless.
`CodedItem` therefore carries the host's own wording and its record id (from `Coding.display` or
the concept's `text`, and from the resource `id`), `ClinicalContextMapper` fills both in, and the
surveillance builder writes them. `DigitalisRxBuilder` still writes them empty on the EVS
contract, and says why. This one was found by posting the payload at `hub.digitalis.nl` and
reading the titles that came back; nothing local would have caught it.

**An allergy finding says its verdict in `implicated`, not in its text.** `allergy.py` appends
the drug to the context and escalates to level 1 only on an actual G-Standaard hit, so a green
allergy signal with an empty medication context means "checked, nothing matched" — while its text
still reads "voor het onderstaande middel". The guide tells integrators to branch on `severity`
and `implicated` rather than on the sentence.

**All three allergy subsystems are sent, and only one of them reaches the rules engine.**
`TCREAllergie.ReadFromXML` reads `OGGrp` and nothing else; `SNK` and `SSK` are read by the Hub's
own allergy check, which asks the G-Standaard service about them. So all three earn their place,
but a beslisregel can only ever fire on an ongewenste groep — which is another reason `pending`
matters, since without it the SNK and SSK codes are read by nothing at all.

**An empty Bundle must only ever mean "the check ran and nothing fired".** Every other outcome is
a 500 — unreachable, SOAP fault, a body that parses but carries no `<report>`. That last one is not
hypothetical: `MbCompleteFacade` catches a failure of either engine and logs it, so a half-run
check is a shape that can arrive. `MedicationSurveillanceResponseParser` is where the distinction
is enforced and `SurveillanceIntegrationTest.refusesToTurnAFailedCheckIntoAnAllClear` is what keeps
it enforced. Do not soften a parse failure into an empty result; a prescriber who sent a medication
list and saw no signal reads it as an all-clear.

**Three gaps are known, and each fails as silence.** They are documented for integrators in the
guide rather than only here, because a host has to decide what to show a prescriber. No `PDD` or
`DDD` is sent (computing a PDD means decoding Tabel 25, which this codebase deliberately does not
do), so dose rules comparing the two cannot fire — the engine guards the division, so the effect is
a silent rule rather than an error. Weight and height travel as LOINC and the Hub's dose check
reads them from `<NHG id="357">` and `<NHG id="560">`, which this interface does not send; that one
surfaces as a "data missing" signal rather than a pass, and note before adding it that the Hub's
BSA formula multiplies the length it finds by 100, so its element is in metres where
`LabDeterminations` normalises to centimetres. And nothing distinguishes a partial report from a
complete one, because the upstream does not say.

**The Hub does not adjudicate the credentials.** `CreCall.use_prescriptor_license` overwrites the
licence in the payload with the Hub's own before calling the engine, and the
`MedicationSurveillance` operation checks nothing else — so where a session is authenticated by
Prescriptor rejecting a bad practice id with a 401 this interface passes through, this base accepts
any well-formed Basic header. It is recorded in `HubClient` and in the README's open items; closing
it is a product or deployment decision, and the property worth protecting is that this service
holds no credential store.

**A second base, not a segment under `/fhir/evs`.** FHIR reserves the path space under a base for
resource type names, so `/fhir/evs/surveillance/$check-medication` parses as an operation on a
resource type called `surveillance`. `FhirConfig.EVS_BASE` records this; a third contract goes the
same way.

**`EvsProvider` and `SurveillanceProvider` have no common supertype, deliberately.** They replaced
one `BaseProvider`, and the split is what keeps each base's CapabilityStatement to its own
operations — a shared marker would make `List<BaseProvider>` the easy thing to inject and would
advertise every session operation on the surveillance base. `SurveillanceIntegrationTest` pins both
statements. The auth filter is registered once per base for the same reason: the paths a base
leaves unauthenticated (`metadata`, `OperationDefinition`) are relative to it. The two
`RestClient` beans are qualified by name for a duller reason — there is no Boot parent POM and so
no `-parameters`, so Spring cannot match a constructor parameter name against a bean name and the
context fails to start with "expected single matching bean but found 2".

**One version number covers both contracts**, because there is one Implementation Guide and
`stamp-version.mjs` puts its version on every artifact. So a release that only moves surveillance
still moves the number `GET /fhir/evs/metadata` reports, and the changelog has to name which
contract each change belongs to — `ig/pages/versioning.md` promises exactly that.

**No response profile is published, and `meta.profile` is not asserted.** The request profile has
been enforced since 0.2.0 and did not move when the operation was implemented; the response shape
has produced instances for days rather than years. The guide describes it in prose instead, and `fhirhub-SurveillanceInput` stays
`experimental` for the response's sake rather than its own. Two things to settle before publishing
one, both recorded in the README's open items: whether `DetectedIssue.code` should carry a coding
for the class of check, and whether the rule text should travel as a sanitised XHTML narrative as
well as plain text in `detail`.

**The rule text is flattened to plain text on purpose.** The upstream sends XHTML, and
`DetectedIssue.detail` gets a line-per-block rendering of it with the numbered dosing steps still
numbered. A FHIR `Narrative.div` is validated against a fixed list of permitted elements and
attributes, and the CRS text arrives with at least one that is not on it (`schemaLocation` on the
wrapping div) — so carrying the markup would put the validity of every response at the mercy of
the next rule an editor writes. `MedicationSurveillanceResponseParser.plainText` also decodes the
double-escaped character references the engine writes (`Pati&#235;nt` arrives as six characters in
a text node), because it is the last place that can tell them from text.

**Do not build XML by string templating.** The predecessor did, unescaped, which meant a single
`&` in a drug description or lab value produced a malformed request and let caller-supplied
text inject markup. Everything goes through `XmlWriter` (StAX), which escapes text and
attributes. `XmlRpcRequestBuilderTest.escapesMarkupInCallerSuppliedValues` guards this.

**Lab values are LOINC end to end, and the list of them is the G-Standaard's.** There is no NHG
Tabel 45 mapping and there should not be one: the upstream carries lab data as `<LOINC num=…>`, and
the MFB datatest generator (`g-standaard/GStandaard/apps/mfb/functions`) builds a `DatatestLOINC`
keyed on that number, so translating would add a table to maintain and a class of determinations
that cannot be expressed at all.

`fhir/LabDeterminations` holds the list, and it is a copy of published data: `BST684T` rows with
`MFBEXSRT = 4` ("LOINC / Nederlandse Labcodeset") say which LOINC codes count as which MFB
parameter, and `BST685T` rows with `THMFBP = 2000` are the twelve measurements a rule can test at
all — current rules use four, the nierfunctie in 666 of them. Weight and height are read by dose
checking rather than by the rules, through `evs2.0`'s own LOINC xpath. A code outside the list is
refused, because forwarding it would tell a prescriber their lab data had been weighed when nothing
read it.

One eGFR code, `62238-1` (CKD-EPI), because that is what Dutch laboratories report. The G-Standaard
also lists `77147-7` (MDRD) and `50210-4` (cystatin C) for the same parameter — adding them is one
line each, but re-labelling one formula as another is not on, since they do not give the same
number.

**The time of day on a lab result is load-bearing.** The rules engine resolves several results for
one determination by taking the most recent (`TProtocolParserDataLOINC.GetValueExt`), and
`TCRELabValueList.MostRecent` compares the `date` attribute alone and keeps the *first* of a tie. So
a date-only value puts every result of one day at midnight and hands the decision to document
order. `LabResult` carries a nullable `LocalTime`, and both `DigitalisRxBuilder.upstreamMoment` and
`MedicationSurveillanceRequestBuilder.moment` write `yyyy-MM-dd'T'HH:mm:ss` with seconds always — the engine's `StringToDate` branches on the string
being exactly ten characters, so `ISO_LOCAL_DATE_TIME` would drop zero seconds and match neither
form. A host that stated only a date still gets a date: precision is a claim.

Note that the DigitalisRx schema also has a separate `time` attribute on `<LOINC>`, which the Hub's
own examples carry — and which nothing in either the Hub or the engine reads. The surveillance
builder writes both: the moment folded into `date`, because that is the attribute that is read, and
`time` beside it because the schema defines it. Do not move the time out of `date` on the strength
of the schema looking tidier.

Nothing here de-duplicates or reorders the results — which one counts is the engine's decision, and
weight and height do not even go by date (`evs2.0`'s own xpath takes the first node). That is
documented for integrators under *Lab determinations*, and it is the kind of thing they cannot
discover from the profiles.

Units are pinned per code and converted only where the conversion is exact (`m` → `cm`). The value
is evaluated in the unit the rule was written in, so kalium in mg/dL is a different answer, not a
rounded one. The eGFR must arrive as `mL/min/{1.73_m2}`: the G-Standaard compares it against ml/min
thresholds unchanged, which is its decision to make and not one to hide behind a permissive unit.

`LabDeterminationsTest` pins the table against `LabDeterminationVS`, which the profile binds — and
note that binding *does* catch a wrong code even though LOINC is not distributed here, because the
value set enumerates its concepts. That is the mechanism the G-Standaard bindings cannot use.

**The two session types read their key from different members** — `PrescriptorSessionKey` for
formulary, `SessionKey` for CreateRx. An upstream inconsistency, encoded in `SessionType` and
pinned by a test.

**No house libraries, and `gstandaard-jar` least of all.** It is the obvious one to reach for,
and there are three types in it this service could use — `T25Parser`, `GStandaardDatabase`, and
(transitively, from `formularium-api`) the `T25` DTO. None of its sixteen DAOs, and none of the
three worth the price: it drags in `formularium-api`, which brings Jackson 3 and collides with
HAPI's Jackson 2, and it pins its own Spring Boot version, which splits this application's Boot
modules across two minors.

What stands in for each:

- `GStandaardDatabase` + `GStandaardJdbcBeanFactory` → `gstandaard/GStandaardJdbcConfig`, which
  builds the same named Hikari pool from the same `gstandaard.datasource.*` properties.
  Deployment configuration is the house one either way. Hikari is configured directly and
  `MedicationCodeResolver` uses plain JDBC, so there is no `spring-boot-starter-jdbc` and nothing
  to exclude — Boot's DataSource auto-configuration had to be switched off by name while that
  starter was present, or it went looking for a `spring.datasource.url` that is deliberately not
  set.
- `T25Parser` → nothing. See *The coded dosage is passed through, not decoded* below.

`MedicationCodeResolver` queries `medcode` directly, because the DAOs do not cover the PRK+GPK
pair it needs. Adding a house library to get them back trades a working query for a dependency
collision.

**The coded dosage is passed through, not decoded.** `T25DosageMapper` emits `Dosage.text` and
the `CodedDirections` extension, both verbatim, and derives no `timing` or `doseAndRate`.
Decoding NHG Tabel 25 into structured elements is lossy — the b-codes and the trailing free text
have nowhere to go, and Tabel 25 units are not UCUM — no consumer reads the result, and
`SessionParametersMapper` would ignore a host's edits to it, because it reads the extension back
instead. `T25DosageMapperTest.derivesNoStructureFromTheCodedString` pins this. The class Javadoc
records what would have to be true to change the decision: the target is the zib
Gebruiksinstructie model from Medicatieproces 9, not a component split.

**`../json-interface` is the contract of record; fhir-hub tracks its version.** Currently v2
(`api/openapi.yaml`, `info.version: "2"`). When it moves again, diff these against it: the
`xisInfo` element, `MedicationType`, the `Prescription` member, the validation rules, and the
error wording all changed in v2 and all live in different files here. `XmlRpcRequestBuilderTest`
pins the wire format, so start there.

**`MedicationType` is a constant `9` (PRK), and that is load-bearing rather than lazy.** Upstream
it is not a description of the codes but an instruction: the open-session handler switches on it
once and then reads *that one attribute* off every `<drug>` element
(`../evs2.0/library/Prescriptor/OpenSession/Loader/Patient.php`), skipping any drug whose
attribute is absent — silently, because an empty code is dropped rather than rejected. Deriving
the value from the host's entries therefore makes a mixed PRK/HPK list unsafe: announce HPK, and
every PRK-coded entry vanishes from medication surveillance. `MedicationCodeResolver` resolves
every entry to a PRK + GPK pair, so PRK is the one attribute present on every drug this service
sends, whatever level the host used — which is what lets a host mix levels per entry.

Two things would invalidate the constant: dropping that enrichment, or a requirement to run
surveillance at HPK level. `0` is still sent for an empty list; note that the older
`../Webprescriptor/html/open_session.php` validates `MedicationType` against `{7,8,9}`, so verify
the empty-medication case against the live server before relying on it.

**Runtime validation is real, and it cost four dependencies rather than one.** Adding
`hapi-fhir-validation` alone gets you a service that fails on the first request. What it needs:

- `hapi-fhir-validation-resources-r4` — the R4 core StructureDefinitions ship separately from
  the model classes. Without it `DefaultProfileValidationSupport` resolves *nothing*
  (`fetchAllStructureDefinitions()` returns 0) and every request dies with `HAPI-0705: Unknown
  base definition: .../Parameters`.
- `hapi-fhir-caching-caffeine` — HAPI finds its cache through a `ServiceLoader` and ships none,
  so `CachingValidationSupport` throws `HAPI-2200` at construction.
- A jackson-bom pin, because `org.hl7.fhir.validation` needs jackson-databind 2.22.1 and Boot
  4.1.1's BOM manages it down to 2.21.5. `requireUpperBoundDeps` caught it, which is the whole
  reason that rule is configured.
- `UnknownCodeSystemWarningValidationSupport` with `setNonExistentCodeSystemSeverity(WARNING)`.
  Constructing it is not enough — the default is an error. Without the setter, every real
  payload fails, because a `required` binding onto a `content: not-present` G-Standaard system
  can never be satisfied.

**It also decides the XML this service sends, which is why `XmlWriter` has a line that looks
redundant.** `hapi-fhir-validation` brings Woodstox, which registers itself as the StAX provider
and wins `XMLOutputFactory.newInstance()`. Woodstox serialises an empty element as
`<medication/>` where the JDK writes `<medication></medication>`, so the provider on the
classpath would otherwise decide the shape of every XML-RPC request. `XmlWriter.element` writes a
zero-length text event before the end tag to pin one form under both providers. Deleting that
line makes the wire format depend on dependency resolution.

`XmlWriter` lives in `xml/` rather than in `prescriptor/` because there are two upstreams now, and
it grew one method for the second: `element(name, defaultNamespace, body)` declares a default
namespace so the SOAP envelope and the DigitalisRx document inside it are each qualified without a
prefix on every line. The namespace argument is always a literal from a schema — a caller-supplied
one would be a way to smuggle markup past the escaping everything else goes through.

**The cost is measured, in README under *Enforcement*:** +44 MB and +27 jars of dependencies, ~3 s
for the first validation (moved into startup by `warmUpValidator`), ~70 ms per request after that.

**Nine of the validator's dependencies are excluded, and the exclusions are load-bearing.** The
reference validator is packaged for the IG Publisher and the validator CLI, so it arrives with a
diagram renderer, a git client, an XSLT processor, an HTTP client, a SQLite driver and an SMTP
client. Each is reachable from exactly one entry point this service never calls — the four in
`hapi-fhir-validation` and `hapi-fhir-structures-r4` are named with their entry points in the
`<exclusions>` comments in `pom.xml`. That is 66 MB and 23 jars off the tree, and the point is
the jars rather than the megabytes: a dependency nobody calls is still a dependency somebody has
to answer a CVE about.

Three that look identical are *not* excluded, and were each tried: **thymeleaf**
(`hapi-fhir-base` calls its `Validate` from `ValidationSupportContext`, on every validation),
**icu4j** (`InstanceValidator` formats plural messages through `I18nBase.getPluralKey`) and
**nimbus-jose-jwt** (`InstanceValidator.checkSpecials` needs it to validate any `Bundle`). Static
analysis missed all three — the reference is in a different jar from the one that declares the
dependency in two of the cases — so verify by running the suite, not by grepping. `mvn test` is
the check: `warmUpValidator` performs a real validation at startup, so a missing class fails the
Spring context rather than a request. Note that runner catches `RuntimeException` and a
`NoClassDefFoundError` is an `Error`, which is what makes it fail loudly instead of degrading to
a slow first request.

**Three of the five FHIR versions come out too.** The validator converts what it validates up to
R5, so R4 and R5 are live; DSTU3 is as well, because `ValidationSupportUtils.extractCodeSystemForCode`
switches over dstu3/r4/r5 `ValueSet`s in one method and will not link without it. DSTU2, DSTU2016May
and R4B are reached only from per-version branches of two chain classes that dispatch on the
`FhirContext` version, and `FhirConfig` creates exactly one, `FhirContext.forR4()` — so they are
unreachable by construction, not merely untested. Another 21 MB. Introduce a second context at a
different version and they must come back; the failure is a `NoClassDefFoundError` from inside
terminology validation rather than anything the compiler sees.

**`org.ogce:xpp3` is excluded for its paperwork, not its 0.4 MB.** It is a re-publication of a
project last released in 2005, declares no licence in its POM, and carries `jakarta-regexp` 1.4,
which declares none either — two SOUP items with no identifiable supplier and no anomaly list to
review. It is also the exclusion with the least margin: its `org.xmlpull` classes are referenced
two thousand times across the validator. Almost all of that is `org.hl7.fhir.r5.formats.XmlParser`,
which parses R5 XML *text* and is not how anything gets parsed here — HAPI reads inbound XML with
StAX and the validator walks the element model over DOM. `XhtmlNarrativeParsingTest` is what makes
that safe to rely on, because a host may put a narrative on any resource it sends.

`maven-enforcer`'s `bannedDependencies` lists all eleven as well, because an exclusion prunes one
subtree only and which HAPI artifact reaches which jar is HAPI's business: four of these needed
an exclusion on one dependency and would have returned through another. If the rule fires, add
the exclusion on whatever now reaches it rather than relaxing the rule.

**The dependency list is a regulatory artifact, and it is generated.** This service is destined
for an MDR submission, where every third-party item that ships is SOUP under IEC 62304 and needs a
supplier, a version, a stated purpose and a route to its anomaly list — and, under MDCG 2019-16
and IEC 81001-5-1, a machine-readable inventory to track vulnerabilities against after release.
`cyclonedx-maven-plugin` writes `target/sbom.{json,xml}` and `tools/soup-list.mjs` turns that into
`docs/SOUP.md`. Neither is hand-maintained on purpose: a SOUP list written by hand is wrong the
first time a transitive version moves, and nothing says so.

The plugin is in `pluginManagement` with no execution bound, so **an ordinary `mvn install`
generates nothing** — the SBOM costs a dependency re-resolution and a schema validation, on a file
that only matters at release. Ask for it with `mvn cyclonedx:makeBom` or bind it to a release build
with `mvn -Psbom clean verify`; the configuration lives in `pluginManagement` precisely so both
routes produce the same file. `tools/soup-list.mjs` exits with those two commands in the message if
the SBOM is absent, so the indirection cannot turn into a confusing stack trace.

Two things there are decisions rather than defaults. The SBOM is configured
`includeTestScope=false`, because SOUP is what ships — WireMock, H2 and JUnit carry a
tool-selection obligation instead, and having the build draw that line makes it auditable rather
than asserted. And `tools/soup-list.mjs` exits non-zero when a direct dependency has no purpose
recorded in its `PURPOSE` map, so adding a dependency to the POM cannot quietly skip the paperwork.

The number that matters for that submission is items and suppliers, not megabytes: **92 items from
20 suppliers**, 18 of which are HAPI FHIR and `org.hl7.fhir.core` released in lockstep by the same
team, which is one supplier to watch rather than eighteen. Before changing a dependency, consider
what it does to those two numbers.

**The canonical is `http://spec.digitalis.nl/fhir`** — a host of its own for the artifacts,
independent of the corporate site and of whatever serves the API, with room under `/fhir` for the
other contract Digitalis publishes. One of the URLs under it is read as well as written:
`DigitalisExtensions.CODED_DIRECTIONS` is the extension `$createrx-session` reads back off a
prescription a host hands in. Changing that one breaks a round trip in a way the others cannot,
because falling through to `Dosage.text` loses the coded instruction silently rather than
failing. `IgCanonicalsTest` pins the canonical against `sushi-config.yaml`.

**A `Coding.system` is a URI, and the upstream tokens are not.** `PRK`, `SSK`, `ICPC` and the
rest are XML attribute names in the DigitalisRx document, and `CodeSystemRegistry` maps in one
direction only: `tokenFor` takes a system URI, `systemFor` produces one. Accepting a token as a
`Coding.system` would give the interface a second inbound dialect that no profile describes — and
it would be the form a host finds first, because it is shorter and it is what `json-interface`
takes. A rejection names the accepted URIs rather than the tokens, for the same reason.

**Do not define the G-Standaard code systems in `ig/`.** It reads like an improvement and is the
opposite: the tables are licensed and undistributable, so a definition would have to be
`content: not-present`, which makes any value set including it unexpandable and turns every
coding in it into a validation *error*. Left undefined, the system falls to
`UnknownCodeSystemWarningValidationSupport` and becomes a warning, which is the only way a
`required` binding onto a licensed table is ever satisfied. `TerminologyEnforcementTest` fails if
someone acts on the intuition.

**The profiles live in `ig/`, and the ids in them are load-bearing.** They are also published —
see *The published specification* below.
 SUSHI derives a canonical
as `{canonical}/StructureDefinition/{Id}`, and those canonicals are on the wire, so renaming an
id silently breaks every payload in the field. `IgCanonicalsTest` fails if the FSH
and the constants in `fhir/` drift apart. Note the corollary — base R4 requires
several elements this interface never reads (`AllergyIntolerance.patient` and `clinicalStatus`,
`Condition.subject`, `MedicationStatement.status`/`subject`, `Observation.status`), and a
profile can only constrain, so callers must send them — and since validation runs before
mapping, they are required in practice and not only on paper. A payload that omits them is a 400
even though nothing here would have read them.

**nl-core is not derived from, and the reasons were checked.** `nl-core-MedicationContraIndication`
profiles `Flag` rather than `Condition`; `nl-core-MedicationUse2` is not in the nl-core package
at all; `nl-core-LaboratoryTestResult` wants an `Observation.category` this interface does not read
(its TestCode objection died with the move to LOINC, so ZIB-639 is no longer the blocker); and every
published nl-core R4 version is a pre-release. Only `nl-core-Patient` would validate clean today.
Re-check against the package before reopening this, and do not assert nl-core conformance
anywhere on the strength of the intention.

**Only assert `meta.profile` where the resource validates clean.** Half-meeting a profile and
claiming it is worse than claiming nothing, and nothing here asserts one. The Bundle
`ResultBundleMapper` produces does validate clean against `fhirhub-ResultBundle`
(`OutboundPayloadConformanceTest`), so asserting it there is a decision rather than a dependency
— but the Implementation Guide tells integrators not to route on `meta.profile`, so the two have
to move together.

**OIDs in `fhir/Systems.java` came from the HL7 NL OID register, not from memory.** A wrong
digit in a `system` is a silent interoperability bug that surfaces months later. Check the
register before editing one.

**Medication surveillance fails closed.** A drug whose code G-Standaard cannot resolve aborts
the session with a 400 instead of being dropped from the list. Surveillance over an incomplete
list answers "no interaction found" — a false negative a prescriber cannot distinguish from a
genuine all-clear. Do not soften this into a warning without a clinical decision behind it.

**Authentication is a servlet filter, and Spring Security was removed on purpose.** What this
interface authenticates is "is there a well-formed practice id and license key on the request" —
Prescriptor holds the licence administration and is the authority on whether they are a real pair,
so there is no user store, no roles, no session and no CSRF surface. Spring Security expressed that
in an `AuthenticationProvider`, a `ProviderManager` with credential erasure switched off, a filter
chain and a `SecurityContextHolder`, to carry two strings from a header to `CredentialsResolver`.
`auth/BasicAuthenticationFilter` does it in one readable class and takes six jars with it.

Two things to keep if you touch it. The 401 carries an `OperationOutcome` body — Spring Security's
did not, and a rejection before the servlet has no more right than any other error to be the one
un-parseable response. And `CurrentCredentials` is a thread-local, which is safe only because
nothing here dispatches asynchronously; if that changes, the failure is a request served with
another request's licence key, so move the credentials onto the request rather than making the
holder cleverer.

**Errors must be `OperationOutcome`.** Throw HAPI's `BaseServerResponseException` subclasses
and let the server render them. Use `error/UnauthorizedException` rather than HAPI's
`AuthenticationException` for 401s — HAPI special-cases the latter into a `text/plain` body,
which would be the one un-parseable response in the API.

**The Spring surface is deliberately small, and three starters have already been removed.**
`spring-boot-starter-security` became `auth/BasicAuthenticationFilter` (above),
`spring-boot-starter-jdbc` became a `HikariConfig` and one `PreparedStatement`, and
`spring-boot-starter-web` became `spring-boot-starter` + `spring-boot-starter-tomcat` +
`spring-web` — together 18 jars off the tree.

That last one is the one to understand before adding a dependency back. **There is no Spring MVC
on the classpath**: no `DispatcherServlet`, no `@RestController`, no handler mappings. The FHIR
servlet is registered against the container directly, so MVC was configuring itself on every boot
for a request path it never saw. Two consequences. Boot's `BasicErrorController` is gone with it,
so `server.error.*` does nothing and the property was removed — a path outside the two FHIR
bases gets the container's own 404 page rather than Boot's JSON body. And an `@RestController` added later
will silently never be routed; if one is ever genuinely needed, the honest move is adding
`spring-boot-starter-web` back rather than hunting for why the endpoint 404s.

Tomcat itself is not optional: HAPI's `RestfulServer` is a `jakarta.servlet.HttpServlet` and
something has to serve it. `spring-web` is there for `RestClient` in `PrescriptorClient` and
nothing else, so moving that one call to the JDK's `HttpClient` would remove it. What Boot still provides is worth keeping and should not be removed by reflex:
the DI container, `@ConfigurationProperties` binding with its relaxed names, the embedded servlet
container, the executable jar — and `spring-boot-dependencies`, which is the BOM that keeps a
hundred transitives coherent and is the reason the tree is manageable at all. Removing Spring
entirely would take about 12% of the bytes and hand you that arbitration by hand; the mass is
`hapi-fhir-validation` and its tail (plantuml, icu4j, sqlite-jdbc), not Spring.

**Jackson comes from HAPI, and from nowhere else.** `maven-enforcer`'s `bannedDependencies` fails
the build if `spring-boot-starter-jackson` reappears, so only one Jackson is on the classpath,
pinned by HAPI, which serialises every `/fhir/*` response. It was an `<exclusion>` on
`starter-web` until that starter was dropped; nothing pulls Boot's Jackson starter today, and the
ban is what keeps that true through the next upgrade. Nothing here needs Spring to serialise JSON. Jackson 3 reaches this
codebase through `formularium-api`, which arrives with `gstandaard-jar` — see *No house
libraries* above — and a Jackson 2 / Jackson 3 collision does not fail at the boundary that
caused it: expect the context to fail at `RestClient` construction with
`NoClassDefFoundError: …JsonSerializeAs`.

## The published specification

`http://spec.digitalis.nl/fhir` is a real site, built from `ig/` by SUSHI and the HL7 IG
Publisher and deployed as static files. `ig/README.md` covers the build; the parts that decide how
the rest of the repo has to behave are here.

**The narrative has one source, and it is `IMPLEMENTATION_GUIDE.md`.**
`ig/scripts/build-pages.mjs` splits it into `ig/input/pagecontent/` and rewrites its
intra-document anchors into cross-page links. **Do not edit anything under
`ig/input/pagecontent/`** — it is generated, not committed, and the edit is gone on the next
build. Three lists have to agree, and the script exits non-zero naming the offender when they do
not: the guide's `##` sections, the `SECTIONS` table in the script, and the `pages:` block of
`sushi-config.yaml`. Adding a section to the guide is therefore a two-line change in two files,
by design — the alternative is a section that never appears on the site and nothing that says so.

The four pages that are about the publication rather than the interface — the front door, the
change policy, the changelog, the download instructions — are hand-written in `ig/pages/`. The
front door splices in the guide's preamble at a `<!-- GUIDE-PREAMBLE -->` marker, so the
orientation text is not written twice either.

**A change to the guide can now be a change to a published contract.** The prose is the
specification, so an edit that alters what a host may send belongs to a version bump under
`ig/pages/versioning.md`, an entry in `ig/pages/changelog.md`, and a line in
`ig/package-list.json` — `deploy.sh` refuses a release missing from the last of those. Editorial
edits are free.

**`fsh-generated` is versioned by a script, because SUSHI stopped doing it.** With `FSHOnly: true`
SUSHI stamped `version` on every artifact; now that a real IG is built, it leaves `version`,
`publisher` and `jurisdiction` to the publisher, which applies them into `output/` only. But
`fsh-generated/resources` is what the Maven build copies into the jar, so unstamped the *service*
would enforce version-less profiles while the published package says `0.2.0`, and an
`OperationOutcome` would stop naming the version its rule came from — which the change policy
promises it does. `ig/scripts/stamp-version.mjs` puts it back and runs as part of `npm run sushi`;
`IgCanonicalsTest.theProfilesCarryTheIgVersion` fails if someone runs `sushi .` directly.

**`GET /fhir/evs/metadata` reports the specification release, and that is load-bearing rather than
decorative.** `SpecificationVersion` reads the version off a profile in the jar and `FhirConfig`
puts it in `CapabilityStatement.software.version`; HAPI would otherwise announce itself as "HAPI
FHIR Server" at HAPI's version. The change policy tells integrators to check it before sending a
parameter introduced in a later release, because the inbound slicing is closed and an unknown name
is a 400 rather than an ignored element — so without this the policy has no way to be followed.
It comes off the profiles rather than out of `pom.xml` on purpose: those artifacts are what
decides what the service accepts, and a number declared anywhere else would be the copy that goes
stale. `FhirHubIntegrationTest.reportsTheSpecificationReleaseInTheCapabilityStatement` pins it.

**The canonicals dereference because of `ig/hosting/nginx-fhir.conf`, not because of the publisher.**
The publisher writes flat files — `StructureDefinition-fhirhub-Patient.html`, `.json`, `.xml`,
`.ttl` — and the canonical on the wire has a path segment. That config maps one onto the other,
negotiates on `Accept` with `?_format=` overriding, sets `Vary: Accept`, and keeps
`/fhir/<version>/` as a frozen snapshot. `ig/hosting/apache-htaccess` is the same rules for Apache,
which is what `www.digitalis.nl` actually runs, and is the one that has been tested end to end
against a local httpd; the nginx spelling has never been parsed by nginx.

Three traps are recorded in their comments and are all easy to undo by accident.
**`text/html` has to be tested before `application/xml`** when negotiating on `Accept`, because a
browser sends both — get it backwards and every browser is handed a raw XML file for a profile it
asked to read, which is the same trap as the CapabilityStatement rendering in `FhirConfig`, one
layer down. **`https` is required and `http` is additional**: a canonical is an identifier and the
one on the wire is `http`, but the HL7 validator's SSRF protection refuses a plain-http *fetch*
before making the request and is on by default, so an http-only deployment is readable in a
browser and unusable by tooling. **The `ImplementationGuide` canonical needs its own rule**,
because the publisher renders no page for it — with the fallback kept out of the general rule, so
a mistyped canonical is a 404 rather than the landing page dressed up as a profile.

**`history.html` is built after the publisher, not by it.** The publish box on every rendered page
links to `{canonical}/history.html` and the publisher writes no such file — it reserves the name for
the publication process, which here is `hosting/deploy.sh`. Adding `history.md` to `pages:` is the
obvious move and the publisher rejects it by name, with three broken links thrown in because the
absolute release URLs resolve to nothing inside `output/`. `ig/scripts/build-history.mjs` runs after
the publisher, reads `package-list.json` — the file `deploy.sh` already gates a release on — and
clones its chrome from a page the publisher just rendered, so the template stays single-sourced.

**`ig.ini` takes no comments.** A `;` line makes the publisher report that it cannot find an
`ig.ini` at all.

**The narrative language is Dutch, inferred from `jurisdiction: NL`,** which is why the QA report
is in Dutch and why a LOINC `display` in an example must be LOINC's *Dutch* term or none at all.
The examples send no LOINC display: a wrong one is a validation error rather than a warning,
because unlike the G-Standaard, LOINC *is* distributed.

**A clean build is `0 errors`, not `0 warnings`.** `ig/input/ignoreWarnings.txt` suppresses four
template-fragment warnings and nothing else. The G-Standaard "code cannot be validated" notes, the
missing narratives and the display-less `data-absent-reason` references are all expected, are
documented for integrators on the Downloads page, and are the class of message that would hide a
real problem if it were muted. `hosting/deploy.sh` gates on the error count in `output/qa.json` —
note the key is `errs`, not `errors`.

## Deliberately different from `json-interface`

**Current medication is enriched before it is sent, and fails closed.** `json-interface` emits
`<medication>` from the host's own codes at the level supplied — its `getAdditionalDrugCodes`
call is commented out in `translate-xml.ts`, so `additionalCodes` stays empty and no PRK + GPK
pair is added. `MedicationCodeResolver` does that lookup here, which is what makes an
unresolvable code a 400 rather than a silently thinner medication list. Do not "restore
compatibility" by dropping the lookup.

`prescriptor-api`'s `OpenSessionRequestBuilder.getAllergies` is the authority on which allergy
member carries which subsystem (`Allergies`→OGGRP, `AlStam`→SNK, `AlStof`→SSK). Both interfaces
populate all three. Re-read `translate-xml.ts` before asserting a difference here: it moves, and
a difference recorded from memory tends to describe a version of it that no longer runs.

## Behaviour that looks like a bug and is not

- `$session-result` ends the session upstream, so a second call returns 401. Inherited from
  Prescriptor, documented in the README under *Known quirks*.
- **`Unknown element 'author' found while parsing`, three times at startup.** Nothing to do with
  this codebase. It comes from `HapiFhirStorageResponseCode.json`, a CodeSystem shipped inside
  `hapi-fhir-base` and loaded lazily by `DefaultProfileValidationSupportBundleStrategy` the first
  time the validator resolves a ValueSet — so since the validator warm-up, at every boot. The
  file carries `CodeSystem.author`, which exists in R5 but not in R4, so the R4 parser drops it
  and the lenient handler says so. It is HAPI's own storage-response CodeSystem, irrelevant to a
  stateless proxy, and the element it drops is a name.

  **Do not silence `ca.uhn.fhir.parser.LenientErrorHandler` to make it go away.** Inbound request
  bodies are parsed by the same lenient handler and log through the same logger, so muting it
  also mutes the only signal that an integrator is sending elements this interface silently
  ignores — which is precisely the kind of quiet data loss the rest of this file is about.

## Testing

Unit tests per mapper, plus `FhirHubIntegrationTest` which exercises all three session operations
over real HTTP with WireMock standing in for Prescriptor, and `SurveillanceIntegrationTest` which
does the same for the second base with WireMock standing in for the Hub. Those two are where
credential forwarding, OperationOutcome rendering and the CapabilityStatements are pinned — the
things unit tests cannot see. Fixtures live in `src/test/resources/xmlrpc/` and
`src/test/resources/hub/`; the stand-in `medcode` view is
`src/test/resources/gstandaard-medcode.sql`.

The surveillance fixtures are the documented request and response from `DigitalisRx-documentation/`
plus three shapes that are not documented anywhere and are what the parser has to get right: a
report that ran and found nothing, an envelope with no report in it, and a SOAP fault. The second
of those is the dangerous one — see *An empty Bundle* above — and it exists as a file so that
deleting the test that reads it is visible.

Three tests guard claims the rest of the build cannot see, and all three look deletable:

- `IgExampleConformanceTest` validates every example in `ig/fsh-generated` against the profile it
  declares. SUSHI validates nothing — it converts FSH to JSON — so without this an example can
  contradict both its profile and the payload the mappers build, and the build stays green.
- `TerminologyEnforcementTest` pins which `Coding.system` forms a session can be opened with, and
  fails if a G-Standaard code system is defined in `ig/`. See *Do not define the G-Standaard code
  systems in `ig/`* above.
- `IgCanonicalsTest.theProfilesCarryTheIgVersion` fails when the committed profiles have lost the
  `version` SUSHI no longer stamps. Nothing else would notice: validation keeps working, and only
  the version in an `OperationOutcome` quietly disappears. See *The published specification*
  above.
- `OutboundPayloadConformanceTest.theSurveillanceBundleIsValidFhir` validates the response of
  `$check-medication` against base R4 and against no profile, because none is published for it.
  That is weaker than the check beside it and still the only thing standing between a
  `DetectedIssue` missing a mandatory element and a host's parser.

`json-interface` had no tests at all. Keep this one from going the same way.
