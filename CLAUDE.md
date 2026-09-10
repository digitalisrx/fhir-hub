# CLAUDE.md

Guidance for Claude Code (claude.ai/code) when working in this repository.

## What this is

One FHIR R4 interface in front of **two applications**, on two FHIR bases.

**Prescriptor**, at `/fhir/evs`: functionally equivalent to **v2** of `../json-interface`
(Node/TypeScript) with authentication moved from the request body to HTTP Basic. Stateless proxy —
FHIR in, XML-RPC to Prescriptor, FHIR out, no session store. It reads the G-Standaard database,
read-only, to resolve current medication.

**Surveillance**, at `/fhir/surveillance`, answers `$check-medication-request` and
`$check-medication-statement`: FHIR in, a `DigitalisRx` document over SOAP to the **Digitalis Hub**
(`../hub`), a `Bundle` of `DetectedIssue` out. It runs both halves of Dutch medication surveillance
— the G-Standaard's medisch-farmaceutische beslisregels through the clinical-rules engine, and the
classic allergy, age, duplicate-medication and dose checks — and reads the same G-Standaard view,
for the same PRK + GPK pair plus the ATC.

Read `README.md` first: it holds the operation contracts, the JSON→FHIR mapping table and the open
items. This file covers why the code is shaped as it is. Unless a note says otherwise, everything
here is about the Prescriptor contract.

## Commands

```bash
mvn test                 # no network and no database — WireMock stubs Prescriptor and the Hub,
                         # H2 stands in for the medcode view
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

Run `npm run build` rather than the publisher directly: its preflight names whatever is missing
(Java, Node, Jekyll via `gem install --user-install jekyll`, or `publisher.jar` in `ig/.pkg/`) and
it assembles the PATH the publisher needs to find jekyll — a failure that otherwise surfaces as a
Java stack trace with no mention of gems.

## Stack

Java 25, Spring Boot 4.1.1, HAPI FHIR 8.12.0, Maven. Matches the house backend convention
(`formularium-api`, `nhg-formularium-adapter`, `gstandaard-jar`): **no Spring Boot parent POM**,
explicit versions via properties, package `nl.digitalis.*`, tabs for indentation.

Boot's `spring-boot-dependencies` BOM *is* imported into `dependencyManagement`, which is not the
parent POM and does not change that convention: every direct dependency declares its own version
and those win. The BOM governs **transitive** versions only, because without it nearest-wins
silently downgraded third-party transitives. `maven-enforcer-plugin` (`requireUpperBoundDeps`) makes
a reintroduced downgrade a build failure rather than a runtime surprise.

**No third-party `nl.digitalis` libraries** — see *No house libraries* below.

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
                                                  (proposals vs dossier, dosing, dates;
                                                   the statement check puts the whole
                                                   dossier under test)
      →  gstandaard/MedicationCodeResolver       PRK|HPK          → PRK+GPK(+HPK)+ATC via JDBC
      →  hub/MedicationSurveillanceRequestBuilder  internal model → SOAP + DigitalisRx
      →  hub/HubClient                           the only HTTP call out to the Hub
      →  hub/MedicationSurveillanceResponseParser  report         → internal model
      →  fhir/SurveillanceBundleMapper            internal model  → Bundle of DetectedIssue
```

`fhir/ClinicalContextMapper` sits behind both parameter mappers and holds what the two contracts
share: gender, birth date, allergies, conditions, current medication, lab determinations and the
`xisId`/`xisVersion` pair. Every rule in it is safety-relevant — which `Coding.system` is routed,
which LOINC codes are read, which unit a value must arrive in — and two copies of a fail-closed rule
is one copy that gets softened by accident. `xml/XmlWriter` is shared for the same reason.

`model/` is plain records with no FHIR and no XML types, so the FHIR mappers and the XML-RPC layer
never see each other's types and either side can change without dragging the other along.

The two bases share the filter, the validator, the profiles and the G-Standaard lookup, and nothing
else: `prescriptor/` speaks XML-RPC, `hub/` speaks SOAP.

## Things worth knowing before you change something

Each note is a decision that is easy to undo by accident, and most of them fail as a check that
quietly does not run rather than as an error. **The rule behind most of them: a check that did not
run must never answer 200 with no findings.** A prescriber who sent a medication list and saw no
signal reads it as an all-clear and cannot tell that apart from a false negative — so do not soften
a fail-closed rule into a warning, a dropped element or an empty result without a clinical decision
behind it.

**Which drug is being checked is marked twice, and both marks are load-bearing.** The one call
reaches two engines that read different attributes:

- `pending="true"` is what the Hub's own checks select on (`MbFactorySettings.get_pending_medications`).
  `MbFactory.process()` returns immediately if no drug carries it, so *none* of the classic
  G-Standaard checks run — and the response still looks like a complete answer.
- `trigger="true"` is what the rules engine reads (`TCREDrug.ReadFromXML`). It deliberately does not
  treat `pending="true"` as pending; the value it looks for there is `trueevs`, because marking the
  drug under test with `pending` "had too many side effects" (RM#6332).

The published examples in `DigitalisRx-documentation/` and `../hub/docs/examples_call/` carry one
each, which is how the split was found. `MedicationSurveillanceRequestBuilderTest` pins both.

**Every drug carries a `date` and an `ATC`, and neither is decoration.** Both engines filter the
dossier on the start date — the Hub's xpath compares `substring(@date,1,10)` numerically, and an
absent attribute is not a number — so a drug without one is dropped rather than rejected.
`SurveillanceParametersMapper` reads `effectivePeriod`/`effectiveDateTime` (or
`validityPeriod`/`authoredOn` for a proposal) and falls back to *today*, which is what
`status: active` already asserts. A beslisregel selects on ATC far more often than on a product
code, which is why `MedicationCodeResolver` reads `atc` off the same `medcode` row. Dates go out as
`yyyy-mm-dd`: ten characters is the clean branch of the engine's `StringToDate`.

**An empty `caption` costs the prescriber the substance name.** The Hub's allergy check interpolates
the allergy's `caption` into the title *and* the body of the signal, so `caption=""` yields
"Allergie  (ongewenste groep)" — well-formed, schema-valid and useless. `CodedItem` therefore
carries the host's own wording and its record id (`Coding.display` or the concept's `text`, and the
resource `id`), `ClinicalContextMapper` fills both in, and the surveillance builder writes them.
`DigitalisRxBuilder` still writes them empty on the EVS contract, and says why. Found against the
live service; nothing local would have caught it.

**An allergy finding says its verdict in `implicated`, not in its text.** `allergy.py` appends the
drug to the context and escalates to level 1 only on an actual G-Standaard hit, so a green allergy
signal with an empty medication context means "checked, nothing matched" — while its text still
reads "voor het onderstaande middel". The guide tells integrators to branch on `severity` and
`implicated`.

**All three allergy subsystems are sent, and only one reaches the rules engine.**
`TCREAllergie.ReadFromXML` reads `OGGrp` and nothing else; `SNK` and `SSK` are read by the Hub's own
allergy check. So a beslisregel can only ever fire on an ongewenste groep — another reason `pending`
matters, since without it the SNK and SSK codes are read by nothing at all.

**An empty Bundle must only ever mean "the check ran and nothing fired".** Every other outcome is a
500 — unreachable, SOAP fault, or a body that parses but carries no `<report>`. That last one is not
hypothetical: `MbCompleteFacade` catches a failure of either engine and logs it, so a half-run check
is a shape that can arrive. `MedicationSurveillanceResponseParser` enforces the distinction and
`SurveillanceIntegrationTest.refusesToTurnAFailedCheckIntoAnAllClear` keeps it enforced.

**Two gaps are known, and both fail as silence** — documented for integrators in the guide, because
a host has to decide what to show a prescriber. No `PDD` or `DDD` is sent (computing a PDD means
decoding Tabel 25, which this codebase deliberately does not do), so dose rules comparing the two
cannot fire; the engine guards the division, so the effect is a silent rule rather than an error.
And nothing distinguishes a partial report from a complete one, because the upstream does not say.

A third is closed, and only the live service showed it: weight and height reached the Hub as LOINC
only, and its dose check reads them as NHG, so it answered "Geen doseringscontrole: onbekend actueel
gewicht" for a child whose weight had been sent. With the NHG form added the same prescription
evaluates, and an overdose answers "Pas de dosering aan" — the value is read as a number, not merely
present.

**The Hub does not adjudicate the credentials.** `CreCall.use_prescriptor_license` overwrites the
licence in the payload with the Hub's own before calling the engine, and the
`MedicationSurveillance` operation checks nothing else — so where a session is authenticated by
Prescriptor rejecting a bad practice id with a 401, this base accepts any well-formed Basic header.
Recorded in `HubClient` and the README's open items; closing it is a product or deployment decision,
and the property worth protecting is that this service holds no credential store.

**A second base, not a segment under `/fhir/evs`.** FHIR reserves the path space under a base for
resource type names, so `/fhir/evs/surveillance/$check-medication-request` parses as an operation on
a resource type called `surveillance`. `FhirConfig.EVS_BASE` records this; a third contract goes the
same way.

**`EvsProvider` and `SurveillanceProvider` have no common supertype, deliberately**, so neither base
can advertise the other's operations — a shared marker would make `List<BaseProvider>` the easy
thing to inject. `SurveillanceIntegrationTest` pins both CapabilityStatements. The auth filter is
registered once per base for the same reason: the paths a base leaves unauthenticated (`metadata`,
`OperationDefinition`) are relative to it. The two `RestClient` beans are qualified by name for a
duller reason — no parent POM means no `-parameters`, so Spring cannot match a constructor parameter
name against a bean name and the context fails with "expected single matching bean but found 2".

**One version number covers both contracts**, because there is one Implementation Guide and
`stamp-version.mjs` puts its version on every artifact. A release that only moves surveillance still
moves the number `GET /fhir/evs/metadata` reports, so the changelog has to name which contract each
change belongs to — `ig/pages/versioning.md` promises exactly that.

**The response profile is published, and `meta.profile` is still not asserted.**
`fhirhub-SurveillanceBundle` and `fhirhub-SurveillanceFinding` describe what
`SurveillanceBundleMapper` emits element for element, so a cardinality changed in one has to change
in the other —
`OutboundPayloadConformanceTest.theSurveillanceBundleSatisfiesItsProfile` fails when they
disagree. Two decisions are frozen into it and both widen additively if reopened:
`DetectedIssue.code.coding` is `0..0`, because a coding for the class of check would be this
interface guessing from a rule id; and `detail` is plain text, not a narrative. Asserting
`meta.profile` is still not done — the Bundle validates clean, so it would be honest, but the guide
tells integrators not to route on it and the two have to move together.

**The rule text is flattened to plain text on purpose.** `DetectedIssue.detail` gets a
line-per-block rendering of the upstream's XHTML with the numbered dosing steps still numbered. A
FHIR `Narrative.div` is validated against a fixed list of permitted elements and attributes, and the
CRS text arrives with at least one that is not on it (`schemaLocation` on the wrapping div) — so
carrying the markup would put the validity of every response at the mercy of the next rule an editor
writes. `MedicationSurveillanceResponseParser.plainText` also decodes the double-escaped character
references the engine writes (`Pati&#235;nt` arrives as six characters in a text node), because it
is the last place that can tell them from text.

**Do not build XML by string templating.** The predecessor did, unescaped, so a single `&` in a drug
description produced a malformed request and let caller-supplied text inject markup. Everything goes
through `XmlWriter` (StAX);
`XmlRpcRequestBuilderTest.escapesMarkupInCallerSuppliedValues` guards it. It lives in `xml/` because
there are two upstreams, and its `element(name, defaultNamespace, body)` overload qualifies the SOAP
envelope and the DigitalisRx document inside it without a prefix on every line. That namespace
argument is always a literal from a schema — a caller-supplied one would smuggle markup past the
escaping.

**Lab values are LOINC end to end, and the list is the G-Standaard's.** There is no NHG Tabel 45
mapping and there should not be one: the upstream carries lab data as `<LOINC num=…>` and the MFB
datatest generator builds a `DatatestLOINC` keyed on that number, so translating would add a table
to maintain and a class of determinations that cannot be expressed at all. `fhir/LabDeterminations`
holds the list and is a copy of published data — `BST684T` rows with `MFBEXSRT = 4` say which LOINC
codes count as which MFB parameter, `BST685T` rows with `THMFBP = 2000` are the twelve measurements
a rule can test at all. A code outside the list is refused, because forwarding it would tell a
prescriber their lab data had been weighed when nothing read it. `LabDeterminationsTest` pins the
table against `LabDeterminationVS`, and that binding *does* catch a wrong code even though LOINC is
not distributed here, because the value set enumerates its concepts — the mechanism the G-Standaard
bindings cannot use.

One eGFR code, `62238-1` (CKD-EPI), because that is what Dutch laboratories report. `77147-7` (MDRD)
and `50210-4` (cystatin C) are one line each to add, but re-labelling one formula as another is not
on: they do not give the same number.

Units are pinned per code and nothing is converted. The value is evaluated in the unit the rule was
written in, so kalium in mg/dL is a different answer, not a rounded one. The eGFR must arrive as
`mL/min/{1.73_m2}`: the G-Standaard compares it against ml/min thresholds unchanged, which is its
decision to make and not one to hide behind a permissive unit. A lengte must arrive as `cm` — R4
applies its own `bodyheight` profile to every `8302-2` and binds the unit to `ucum-bodylength`, so
the metres this interface converted exactly were a unit a host's own validator rejects. The factor
in `Determination.factorByUnit` is 1 everywhere as a result; `LabDeterminationsTest` exercises the
conversion on a synthetic determination so the mechanism does not rot before the next one needs it.

**Gewicht and lengte are the exception, and the exception has a test rather than a preference.**
They are read by the G-Standaard dose-band model instead of by the rules, and the Hub reads them
*only* as `<NHG id="357">` and `<NHG id="560">`. So those two carry an NHG identity as well, written
beside the LOINC element rather than instead of it, on the surveillance contract only. They are also
exactly the two determinations with no MFB parameter — no beslisregel tests them — and the
`Determination` constructor enforces that correspondence, so a third NHG row cannot be added without
finding a consumer that reads it and cannot read LOINC.

**NHG 560 is in metres**, which is why `NhgEquivalent` divides the centimetres this interface holds
by 100. The Hub's Mosteller expression multiplies by 100 to reach the centimetres the formula wants,
so centimetres here would be a body surface ten times too large — and a per-m² norm divides by the
BSA, so the failure would be a missed overdose rather than an error. `evs2.0` reads the same element
as centimetres (`Patient.php`); that reader never sees this document, and if one is ever routed to it
the unit has to be revisited.

**The time of day on a lab result is load-bearing.** The rules engine takes the most recent result
per determination (`TProtocolParserDataLOINC.GetValueExt`), and `TCRELabValueList.MostRecent`
compares the `date` attribute alone and keeps the *first* of a tie — so a date-only value puts every
result of one day at midnight and hands the decision to document order. `LabResult` carries a
nullable `LocalTime`, and both `DigitalisRxBuilder.upstreamMoment` and
`MedicationSurveillanceRequestBuilder.moment` write `yyyy-MM-dd'T'HH:mm:ss` with seconds always,
because `StringToDate` branches on the string being exactly ten characters and
`ISO_LOCAL_DATE_TIME` would drop zero seconds and match neither form. A host that stated only a date
still gets a date: precision is a claim.

The schema also has a separate `time` attribute on `<LOINC>` that nothing reads. The surveillance
builder writes both — the moment folded into `date` because that is what is read, `time` beside it
because the schema defines it. Do not move the time out of `date` on the strength of the schema
looking tidier.

**Only the latest result per LOINC code leaves here.** `ClinicalContextMapper.mostRecentPerDetermination`
makes the selection the engine would have made — missing time as midnight, first of a tie — so a
repeated determination cannot be answered from an older value. Weight and height are why it is not
left to the engine: the Hub's dose check reads them by NHG id and never looks at a date, so a stale
weight beside a current one is a dose band computed against the wrong patient. Selection is per
code, not per determination: a kalium in blood does not supersede one in serum.

**The two session types read their key from different members** — `PrescriptorSessionKey` for
formulary, `SessionKey` for CreateRx. An upstream inconsistency, encoded in `SessionType` and pinned
by a test.

**`MedicationType` is a constant `9` (PRK), and that is load-bearing rather than lazy.** Upstream it
is an instruction rather than a description: the open-session handler switches on it once and then
reads *that one attribute* off every `<drug>` element
(`../evs2.0/library/Prescriptor/OpenSession/Loader/Patient.php`), silently skipping any drug whose
attribute is absent. Deriving it from the host's entries therefore makes a mixed PRK/HPK list
unsafe: announce HPK, and every PRK-coded entry vanishes from surveillance. Since every entry is
resolved to a PRK + GPK pair, PRK is the one attribute present on every drug this service sends,
which is what lets a host mix levels per entry. Two things would invalidate the constant: dropping
that enrichment, or a requirement to run surveillance at HPK level. `0` is still sent for an empty
list, and the older `../Webprescriptor/html/open_session.php` validates `MedicationType` against
`{7,8,9}` — so verify the empty-medication case against the live server before relying on it.

**The coded dosage is passed through, not decoded.** `T25DosageMapper` emits `Dosage.text` and the
`CodedDirections` extension, both verbatim, and derives no `timing` or `doseAndRate`. Decoding NHG
Tabel 25 is lossy — the b-codes and the trailing free text have nowhere to go, and Tabel 25 units
are not UCUM — no consumer reads the result, and `SessionParametersMapper` would ignore a host's
edits because it reads the extension back instead.
`T25DosageMapperTest.derivesNoStructureFromTheCodedString` pins this, and the class Javadoc records
what would have to be true to change the decision.

**`../json-interface` is the contract of record; fhir-hub tracks its version.** Currently v2
(`api/openapi.yaml`, `info.version: "2"`). When it moves again, diff these against it: the `xisInfo`
element, `MedicationType`, the `Prescription` member, the validation rules and the error wording all
moved at v2 and all live in different files here. `XmlRpcRequestBuilderTest` pins the wire format,
so start there.

**Medication surveillance fails closed.** A drug whose code G-Standaard cannot resolve aborts the
request with a 400 instead of being dropped from the list.

**`prescription` is `1..*` on `$check-medication-request`, and there is no
`fhirhub-something-to-check` invariant** — a mandatory prescription makes one unfireable, so it was
removed rather than left to assert something false. Two consequences before widening the slice back
to `0..*`: the profile is what rejects an empty check (`ProfileValidator` fires before the mapper,
and `SurveillanceParametersMapper` keeps the same rule for a deployment with validation off), and
the dossier-only capability lives in `$check-medication-statement`, so widening would give the same
question two answers with different subjects.
`SurveillanceIntegrationTest.refusesARequestCarryingOnlyCurrentMedication` pins the refusal.

**`$check-medication-statement` marks the whole dossier as pending, and that is the operation.** It
maps every `medicationStatement` into `SurveillanceRequest.proposed`, which reads like a misuse of
the field and is the mechanism: `proposed` is what the builder marks `pending` and `trigger`, and
the Hub gates *every* one of its G-Standaard checks on at least one drug carrying `pending`. Sending
that list as the standing dossier instead would answer 200 with no findings for a check that never
ran. `currentMedication` is left empty because the dossier is the subject, not the context.

The rules engine is the half that does *not* need the marking: `trigger` only narrows a rule's drug
match when the root attribute `filterOnTriggerMeds` is set, which defaults to false and this
interface does not send (`TCREDrugList.DrugsHaveMatchWithMPD`) — so it is the classic checks that
would fall silent. And the response cannot say which resource a drug came from, because the report
echoes back the same marking whichever operation asked: `SurveillanceBundleMapper.DrugsUnderTest` is
how the operation tells the mapper whether `DetectedIssue.implicated` should name a
`MedicationRequest` or a `MedicationStatement`, and it has no default because a wrong one hands a
host a reference to a resource type it never sent.

### Terminology and profiles

**A `Coding.system` is a URI, and the upstream tokens are not.** `PRK`, `SSK`, `ICPC` and the rest
are XML attribute names in the DigitalisRx document, and `CodeSystemRegistry` maps in one direction
only: `tokenFor` takes a system URI, `systemFor` produces one. Accepting a token as a
`Coding.system` would give the interface a second inbound dialect that no profile describes — and it
would be the form a host finds first, because it is shorter and it is what `json-interface` takes. A
rejection names the accepted URIs rather than the tokens, for the same reason.

**Do not define the G-Standaard code systems in `ig/`.** It reads like an improvement and is the
opposite: the tables are licensed and undistributable, so a definition would have to be
`content: not-present`, which makes any value set including it unexpandable and turns every coding
in it into a validation *error*. Left undefined, the system falls to
`UnknownCodeSystemWarningValidationSupport` and becomes a warning, which is the only way a
`required` binding onto a licensed table is ever satisfied. `TerminologyEnforcementTest` fails if
someone acts on the intuition.

**OIDs in `fhir/Systems.java` came from the HL7 NL OID register, not from memory.** A wrong digit in
a `system` is a silent interoperability bug that surfaces months later. Check the register before
editing one.

**The profiles live in `ig/`, and the ids in them are load-bearing.** SUSHI derives a canonical as
`{canonical}/StructureDefinition/{Id}`, and those canonicals are on the wire, so renaming an id
silently breaks every payload in the field. `IgCanonicalsTest` fails if the FSH and the constants in
`fhir/` drift apart. The corollary: base R4 requires several elements this interface never reads
(`AllergyIntolerance.patient` and `clinicalStatus`, `Condition.subject`,
`MedicationStatement.status`/`subject`, `Observation.status`), a profile can only constrain, and
validation runs before mapping — so a payload that omits them is a 400 even though nothing here
would have read them.

**The canonical is `http://spec.digitalis.nl/fhir`**, and one URL under it is read as well as
written: `DigitalisExtensions.CODED_DIRECTIONS` is the extension `$createrx-session` reads back off
a prescription a host hands in. Changing that one breaks a round trip in a way the others cannot,
because falling through to `Dosage.text` loses the coded instruction silently rather than failing.
`IgCanonicalsTest` pins the canonical against `sushi-config.yaml`.

**nl-core is not derived from, and the reasons were checked** — README's *Profiles* has the
resource-by-resource verdict from an actual validator run. Re-check against the package before
reopening it, and do not assert nl-core conformance anywhere on the strength of the intention.

**Only assert `meta.profile` where the resource validates clean.** Nothing here asserts one. The
Bundle `ResultBundleMapper` produces does validate clean against `fhirhub-ResultBundle`
(`OutboundPayloadConformanceTest`), so asserting it there is a decision rather than a dependency —
but the guide tells integrators not to route on `meta.profile`, so the two have to move together.

### The validator, and what it cost

**Runtime validation cost four dependencies rather than one.** `hapi-fhir-validation` alone gets you
a service that fails on the first request. What it needs, and how each absence shows up:

- `hapi-fhir-validation-resources-r4` — the R4 core StructureDefinitions ship separately from the
  model classes. Without it every request dies with `HAPI-0705: Unknown base definition`.
- `hapi-fhir-caching-caffeine` — HAPI finds its cache through a `ServiceLoader` and ships none, so
  `CachingValidationSupport` throws `HAPI-2200` at construction.
- A jackson-bom pin, because `org.hl7.fhir.validation` needs a jackson-databind that Boot's BOM
  manages downwards. `requireUpperBoundDeps` caught it, which is why that rule is configured.
- `UnknownCodeSystemWarningValidationSupport` with `setNonExistentCodeSystemSeverity(WARNING)`.
  Constructing it is not enough — the default is an error, and then every real payload fails,
  because a `required` binding onto a `content: not-present` G-Standaard system can never be
  satisfied.

Cost, measured and recorded in README under *Enforcement*: +44 MB and +27 jars, ~3 s for the first
validation (moved into startup by `warmUpValidator`), ~70 ms per request after that.

**A nameless `Parameters.parameter` is refused before the validator sees it, and that guard is not
redundant.** `ParametersValidator.validateParameter` looks the name up in a `Map.ofEntries`, which
throws on a null key rather than missing, so org.hl7.fhir.validation 6.9.12 dies with an NPE before
it can report the `name` base R4 requires — and HAPI renders the crash as HAPI-0389, a 500 saying
this service failed. `ProfileValidator.refuseNamelessParameters` makes it a 400, and runs *before*
the `enabled` check because every mapper looks parameters up by name: with validation off the
parameter would not be an error but an omission. `FhirHubIntegrationTest.refusesAParameterWithNoName`
pins the status.

**It also decides the XML this service sends, which is why `XmlWriter` has a line that looks
redundant.** `hapi-fhir-validation` brings Woodstox, which wins `XMLOutputFactory.newInstance()` and
serialises an empty element as `<medication/>` where the JDK writes `<medication></medication>` — so
the provider on the classpath would otherwise decide the shape of every XML-RPC request.
`XmlWriter.element` writes a zero-length text event before the end tag to pin one form under both.

**Eleven dependencies are excluded, and the exclusions are load-bearing.** The reference validator
is packaged for the IG Publisher and the validator CLI, so it arrives with a diagram renderer, a git
client, an XSLT processor, an HTTP client, a SQLite driver, an SMTP client and three of the five FHIR
versions — each reachable from exactly one entry point this service never calls, and each named with
that entry point in the `<exclusions>` comments in `pom.xml`. Read those before touching one. 87 MB
and 44 jars off the tree, and the point is the jars: a dependency nobody calls is still a dependency
somebody has to answer a CVE about. `bannedDependencies` lists all eleven as well, because an
exclusion prunes one subtree only and four of these would have returned through another; if the rule
fires, add the exclusion on whatever now reaches it rather than relaxing the rule.

Two things not to rediscover the hard way. **Three that look identical are *not* excluded** —
thymeleaf, icu4j and nimbus-jose-jwt, all reached from inside validation on every run, and static
analysis missed all three because in two cases the reference is in a different jar from the one
declaring the dependency. Verify by running the suite, not by grepping: `warmUpValidator` performs a
real validation at startup, so a missing class fails the Spring context rather than a request. And
**DSTU3 links in even though nothing speaks it**, because
`ValidationSupportUtils.extractCodeSystemForCode` switches over dstu3/r4/r5 `ValueSet`s in one
method; the three that are gone are unreachable only because `FhirConfig` creates exactly one
`FhirContext`, at R4. Introduce a second and they must come back.

### The dependency list is a regulatory artifact, and it is generated

This service is destined for an MDR submission, where every third-party item that ships is SOUP
under IEC 62304. `cyclonedx-maven-plugin` writes `target/sbom.{json,xml}` and `tools/soup-list.mjs`
turns that into `docs/SOUP.md`. Neither is hand-maintained: a SOUP list written by hand is wrong the
first time a transitive version moves, and nothing says so.

The plugin is in `pluginManagement` with no execution bound, so **an ordinary `mvn install`
generates nothing** — ask for it with `mvn cyclonedx:makeBom`, or `mvn -Psbom clean verify` for a
release build. Two decisions rather than defaults: `includeTestScope=false`, because SOUP is what
ships and having the build draw that line makes it auditable rather than asserted; and
`tools/soup-list.mjs` exits non-zero when a direct dependency has no purpose in its `PURPOSE` map,
so adding a dependency cannot quietly skip the paperwork.

The number that matters is items and suppliers, not megabytes: **92 items from 20 suppliers**, 18 of
them HAPI FHIR and `org.hl7.fhir.core` released in lockstep by one team. Before changing a
dependency, consider what it does to those two numbers.

### Spring, and how little of it there is

**Authentication is a servlet filter, and Spring Security was removed on purpose.** What this
interface authenticates is "is there a well-formed practice id and license key on the request" —
Prescriptor is the authority on whether they are a real pair — so there is no user store, no roles,
no session and no CSRF surface. `auth/BasicAuthenticationFilter` does in one readable class what
Spring Security needed an `AuthenticationProvider`, a `ProviderManager`, a filter chain and a
`SecurityContextHolder` for, and takes six jars with it.

Two things to keep if you touch it. The 401 carries an `OperationOutcome` body — a rejection before
the servlet has no more right than any other error to be the one un-parseable response. And
`CurrentCredentials` is a thread-local, safe only because nothing here dispatches asynchronously; if
that changes, the failure is a request served with another request's licence key, so move the
credentials onto the request rather than making the holder cleverer.

**Errors must be `OperationOutcome`.** Throw HAPI's `BaseServerResponseException` subclasses and let
the server render them. Use `error/UnauthorizedException` rather than HAPI's
`AuthenticationException` for 401s — HAPI special-cases the latter into a `text/plain` body.

**Three starters have already been removed** — security became the filter above, jdbc became a
`HikariConfig` and one `PreparedStatement`, and web became `spring-boot-starter` +
`spring-boot-starter-tomcat` + `spring-web`, together 18 jars off the tree. That last one matters
before adding a dependency back: **there is no Spring MVC on the classpath**, because the FHIR
servlet is registered against the container directly. So `server.error.*` does nothing and a path
outside the two FHIR bases gets the container's own 404 page, and an `@RestController` added later
will silently never be routed — if one is genuinely needed, add `spring-boot-starter-web` back
rather than hunting for why it 404s. Tomcat is not optional (HAPI's `RestfulServer` is a
`jakarta.servlet.HttpServlet`), and `spring-web` is there for `RestClient` in `PrescriptorClient`
and nothing else.

**Jackson comes from HAPI, and from nowhere else.** `bannedDependencies` fails the build if
`spring-boot-starter-jackson` reappears, so one Jackson is on the classpath, pinned by HAPI, which
serialises every `/fhir/*` response — nothing here needs Spring to serialise JSON. Jackson 3 reaches
this codebase through `formularium-api`, which arrives with `gstandaard-jar`, and the collision does
not fail at the boundary that caused it: expect the context to fail at `RestClient` construction
with `NoClassDefFoundError: …JsonSerializeAs`.

**No house libraries, and `gstandaard-jar` least of all.** It is the obvious one to reach for, and
the three types in it this service could use — `T25Parser`, `GStandaardDatabase` and the `T25` DTO —
are not worth the price: it drags in `formularium-api` with the Jackson collision above, and it pins
its own Spring Boot version, splitting this application's Boot modules across two minors.
`gstandaard/GStandaardJdbcConfig` stands in for `GStandaardDatabase`, building the same named Hikari
pool from the same `gstandaard.datasource.*` properties; `T25Parser` is replaced by nothing (see
*The coded dosage is passed through, not decoded* above). `MedicationCodeResolver` queries `medcode`
directly with plain JDBC, because the sixteen DAOs do not cover the PRK + GPK pair it needs — so
there is no `spring-boot-starter-jdbc` either, and nothing to exclude. Note that Boot's DataSource
auto-configuration had to be switched off by name while that starter was present, or it went looking
for a `spring.datasource.url` that is deliberately not set.

## The published specification

`http://spec.digitalis.nl/fhir` is built from `ig/` by SUSHI and the HL7 IG Publisher and deployed
as static files. README's *Publishing* covers the build and the hosting; `ig/README.md` has the
traps. What decides how the rest of the repo behaves:

**The narrative has one source, and it is `IMPLEMENTATION_GUIDE.md`.**
`ig/scripts/build-pages.mjs` splits it into `ig/input/pagecontent/` and rewrites its intra-document
anchors into cross-page links. **Do not edit anything under `ig/input/pagecontent/`** — it is
generated, not committed, and the edit is gone on the next build. Three lists have to agree and the
script exits non-zero naming the offender when they do not: the guide's `##` sections, the
`SECTIONS` table in the script, and the `pages:` block of `sushi-config.yaml`. Adding a section is
therefore a two-line change in two files, by design — the alternative is a section that never
appears on the site and nothing that says so. The four pages about the publication rather than the
interface are hand-written in `ig/pages/`, and the front door splices in the guide's preamble at a
`<!-- GUIDE-PREAMBLE -->` marker so the orientation text is not written twice.

**A change to the guide is a change to a published contract.** The prose is the specification, so an
edit that alters what a host may send belongs to a version bump, a changelog entry and a line in
`ig/package-list.json` — `deploy.sh` refuses a release missing from the last of those. Editorial
edits are free.

**`fsh-generated` is versioned by a script, because SUSHI stopped doing it.** Now that a real IG is
built, SUSHI leaves `version`, `publisher` and `jurisdiction` to the publisher, which applies them
into `output/` only. But `fsh-generated/resources` is what the Maven build copies into the jar, so
unstamped the *service* would enforce version-less profiles and an `OperationOutcome` would stop
naming the version its rule came from — which the change policy promises it does.
`ig/scripts/stamp-version.mjs` puts it back as part of `npm run sushi`;
`IgCanonicalsTest.theProfilesCarryTheIgVersion` fails if someone runs `sushi .` directly.

**`GET /fhir/evs/metadata` reports the specification release, and that is load-bearing.**
`SpecificationVersion` reads the version off a profile in the jar and `FhirConfig` puts it in
`CapabilityStatement.software.version`; HAPI would otherwise announce itself as "HAPI FHIR Server"
at HAPI's version. The change policy tells integrators to check it before sending a parameter
introduced in a later release, because the inbound slicing is closed and an unknown name is a 400 —
so without this the policy cannot be followed. It comes off the profiles rather than out of
`pom.xml` because those artifacts decide what the service accepts, and a number declared anywhere
else would be the copy that goes stale.
`FhirHubIntegrationTest.reportsTheSpecificationReleaseInTheCapabilityStatement` pins it.

**The canonicals dereference because of `ig/hosting/nginx-fhir.conf`, not the publisher**, which
writes flat files while the canonical on the wire has a path segment. Three traps are recorded in
that config's comments and are all easy to undo: `text/html` has to be tested before
`application/xml` (a browser sends both, and backwards every browser gets raw XML — the same trap as
the CapabilityStatement rendering in `FhirConfig`); `https` is required and `http` additional,
because a canonical is an identifier but the HL7 validator refuses a plain-http *fetch*; and the
`ImplementationGuide` canonical needs its own rule, with the fallback kept out of the general one so
a mistyped canonical is a 404 rather than the landing page dressed up as a profile.
`ig/hosting/apache-htaccess` is the tested spelling; the nginx one has never been parsed by nginx.

**`history.html` is built after the publisher, not by it.** Every rendered page links to
`{canonical}/history.html` and the publisher writes no such file — it reserves the name for the
publication process. Adding `history.md` to `pages:` is the obvious move and the publisher rejects
it by name. `ig/scripts/build-history.mjs` runs after the publisher, reads `package-list.json`, and
clones its chrome from a page the publisher just rendered so the template stays single-sourced.

**`ig.ini` takes no comments.** A `;` line makes the publisher report that it cannot find an
`ig.ini` at all.

**The narrative language is Dutch, inferred from `jurisdiction: NL`,** which is why the QA report is
in Dutch and why a LOINC `display` in an example must be LOINC's *Dutch* term or none at all. The
examples send none: a wrong one is a validation error rather than a warning, because unlike the
G-Standaard, LOINC *is* distributed.

**A body height or weight example is validated against a profile it does not claim.** R4 makes the
core `bodyheight`/`bodyweight` profiles mandatory for LOINC `8302-2` and `29463-7`, and the IG
Publisher enforces it — so `ExampleLengthInCentimetres` carries a `vital-signs` category and a
`subject` this interface never reads, and there is no metres example, because `m` is not in
`ucum-bodylength` and no example of one can pass. Removing either element, or adding that example
back, is 3 to 5 QA errors and a failed `deploy.sh`. The service no longer accepts metres either —
see *Lab values are LOINC end to end* — so the example and the contract now agree.

**A clean build is `0 errors`, not `0 warnings`.** `ig/input/ignoreWarnings.txt` suppresses four
template-fragment warnings and nothing else. The G-Standaard "code cannot be validated" notes, the
missing narratives and the display-less `data-absent-reason` references are all expected, are
documented for integrators on the Downloads page, and are the class of message that would hide a
real problem if muted. `hosting/deploy.sh` gates on the error count in `output/qa.json` — note the
key is `errs`, not `errors`.

## Deliberately different from `json-interface`

**Current medication is enriched before it is sent, and fails closed.** `json-interface` emits
`<medication>` from the host's own codes at the level supplied — its `getAdditionalDrugCodes` call is
commented out in `translate-xml.ts`, so no PRK + GPK pair is added. `MedicationCodeResolver` does
that lookup here, which is what makes an unresolvable code a 400 rather than a silently thinner
medication list. Do not "restore compatibility" by dropping the lookup.

`prescriptor-api`'s `OpenSessionRequestBuilder.getAllergies` is the authority on which allergy member
carries which subsystem (`Allergies`→OGGRP, `AlStam`→SNK, `AlStof`→SSK). Both interfaces populate
all three. Re-read `translate-xml.ts` before asserting a difference here: it moves, and a difference
recorded from memory tends to describe a version that no longer runs.

## Behaviour that looks like a bug and is not

- `$session-result` ends the session upstream, so a second call returns 401. Inherited from
  Prescriptor, documented in the README under *Known quirks*.
- **`Unknown element 'author' found while parsing`, three times at startup.** Nothing to do with this
  codebase: it comes from `HapiFhirStorageResponseCode.json`, a CodeSystem inside `hapi-fhir-base`
  loaded lazily by `DefaultProfileValidationSupportBundleStrategy` the first time the validator
  resolves a ValueSet — so, since the warm-up, at every boot. The file carries `CodeSystem.author`,
  which exists in R5 but not R4, so the R4 parser drops it and the lenient handler says so.

  **Do not silence `ca.uhn.fhir.parser.LenientErrorHandler` to make it go away.** Inbound request
  bodies are parsed by the same handler and log through the same logger, so muting it also mutes the
  only signal that an integrator is sending elements this interface silently ignores.

## Testing

Unit tests per mapper, plus `FhirHubIntegrationTest`, which exercises all three session operations
over real HTTP with WireMock standing in for Prescriptor, and `SurveillanceIntegrationTest`, which
does the same for the second base against the Hub. Those two are where credential forwarding,
OperationOutcome rendering and the CapabilityStatements are pinned — the things unit tests cannot
see. Fixtures live in `src/test/resources/xmlrpc/` and `src/test/resources/hub/`; the stand-in
`medcode` view is `src/test/resources/gstandaard-medcode.sql`.

The surveillance fixtures are the documented request and response from `DigitalisRx-documentation/`
plus three shapes documented nowhere and which the parser has to get right: a report that ran and
found nothing, an envelope with no report in it, and a SOAP fault. The second is the dangerous one —
see *An empty Bundle* above — and it exists as a file so that deleting the test that reads it is
visible.

Four tests guard claims the rest of the build cannot see, and all four look deletable:

- `IgExampleConformanceTest` validates every example in `ig/fsh-generated` against the profile it
  declares. SUSHI validates nothing — it converts FSH to JSON — so without this an example can
  contradict both its profile and the payload the mappers build, and the build stays green.
- `TerminologyEnforcementTest` pins which `Coding.system` a session can be opened with, and fails if
  a G-Standaard code system is defined in `ig/`.
- `IgCanonicalsTest.theProfilesCarryTheIgVersion` fails when the committed profiles have lost the
  `version` SUSHI no longer stamps. Nothing else would notice: validation keeps working, and only
  the version in an `OperationOutcome` quietly disappears.
- `OutboundPayloadConformanceTest.theSurveillanceBundleIsValidFhir` validates the surveillance
  response against `fhirhub-SurveillanceBundle`, for both `DrugsUnderTest` values and for a report
  that ran and found nothing. It is the only thing standing between a `DetectedIssue` that no
  longer matches its published profile and a host's parser.

`json-interface` had no tests at all. Keep this one from going the same way.
