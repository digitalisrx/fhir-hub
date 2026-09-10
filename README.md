# fhir-hub

FHIR R4 interface for two Digitalis applications, on two FHIR bases:

| | | |
| --- | --- | --- |
| **Prescriptor** | `/fhir/evs` | Prescribing, in Prescriptor's own UI. Session-based: open, hand the browser over, collect the result. Stateless proxy — FHIR in, XML-RPC to Prescriptor, FHIR out |
| **Surveillance** | `/fhir/surveillance` | Medication surveillance on its own, asked as a question and answered in the payload. Both halves of it — the G-Standaard's beslisregels and the classic checks — through the Digitalis Hub |

Neither base stores session state or credentials. Both read the G-Standaard database, read-only, to
resolve medication codes.

**Integrating?** [IMPLEMENTATION_GUIDE.md](IMPLEMENTATION_GUIDE.md) is every endpoint with its
input and output specification. This README is the design rationale behind it.

## Endpoints

```
POST   /fhir/evs/$formulary-session        Parameters  ->  Parameters (sessionId, url)
POST   /fhir/evs/$createrx-session         Parameters  ->  Parameters (sessionId, url)
GET    /fhir/evs/$session-result?session=  ->  Bundle (MedicationRequest, Communication)
GET    /fhir/evs/metadata                  ->  CapabilityStatement (unauthenticated)

POST   /fhir/surveillance/$check-medication-request     Parameters  ->  Bundle (DetectedIssue)
POST   /fhir/surveillance/$check-medication-statement   Parameters  ->  Bundle (DetectedIssue)
GET    /fhir/surveillance/metadata                      ->  CapabilityStatement (unauthenticated)
```

Prescriptor is three calls: open a session with the patient context, redirect the care provider's
browser to the `url` that comes back, fetch the result with the `sessionId`. Surveillance is one
request and one answer.

`software.version` on both CapabilityStatements is the published specification release the
deployment implements, read off the profiles in the jar by `SpecificationVersion`. An integrator
following the change policy checks it before sending a parameter from a later release, because the
inbound slicing is closed and an unknown name is a 400.

Responses are JSON by default; `?_format=json|xml|html` overrides, and a browser gets a
syntax-highlighted rendering from HAPI's `ResponseHighlighterInterceptor`.

Custom operations rather than a resource REST API: the interaction is a remote procedure call with
a side effect, not CRUD over stored resources, and `Parameters` is the FHIR container built for
that.

## Authentication

HTTP Basic, `base64(practiceId ":" licenseKey)`. Invalid credentials are a 401.
`GET /fhir/evs/metadata`, `GET /fhir/evs/OperationDefinition/**` and `GET /actuator/health/**` are
unauthenticated.

fhir-hub does not validate the pair. Prescriptor owns the licence administration and answers with
an XML-RPC fault when it is wrong; fhir-hub forwards the `PracticeID` and `LicenseKey` members and
surfaces the fault as a 401. That is what keeps the service stateless — no credential store to
provision, rotate or keep in step with Prescriptor. SMART-on-FHIR / OAuth 2.0 would slot in at
`SecurityConfig` and `CredentialsResolver`, with nothing downstream changing.

## How the JSON interface maps onto FHIR

| JSON interface | FHIR |
| --- | --- |
| `icpc` | `Coding`, ICPC-1 NL (`urn:oid:2.16.840.1.113883.2.4.4.31.1`) |
| `patient.gender` | `Patient.gender` — see *Gender* below |
| `patient.dob` | `Patient.birthDate` |
| `allergies[]` + `SSK`/`SNK`/`OGGrp` | `AllergyIntolerance.code.coding` |
| `contraIndications[]` + `CICode`/`ICPC` | `Condition.code.coding` |
| `medications[]` + `PRK`/`HPK` | `MedicationStatement.medicationCodeableConcept` — see *Medication surveillance in a session* |
| `laboratoryData[].memo`/`mat`/`bijz` | `Observation.code.coding` in LOINC, forwarded as `<LOINC num=…>` |
| `laboratoryData[].date` / `.value` | `Observation.effectiveDateTime` / `.value[x]` |
| `endSessionUrl` | `Parameters.parameter:endSessionUrl.valueUrl`, http(s) only |
| `xis.id` / `xis.version` | `Parameters.parameter:xisId` / `:xisVersion`, both `valueString` |
| `prescription` (CreateRx) | `Parameters.parameter:prescription`, a `MedicationRequest` |
| `organization.id`, `organization.key` | HTTP Basic |
| `drugs[].codes[]`, `.atc` | `MedicationRequest.medicationCodeableConcept.coding[]` |
| `drugs[].codes[].quantity` `{value, unit}` | `dispenseRequest.quantity` (G-Standaard basiseenheid, **not** UCUM) |
| `drugs[].duration` | `dispenseRequest.expectedSupplyDuration` (UCUM `d`) |
| `directions.user` | `Dosage.text` |
| `directions.coded` | the `CodedDirections` extension, verbatim — see *Dosing* below |
| `drugs[].opium` | the `OpiumActClassification` extension |
| `advices[]` + `contentType` | `Communication.payload` — `contentString` or `contentAttachment` |

**Lab determinations are LOINC end to end, from a closed list.** A host codes lab results in LOINC
like the rest of FHIR, the upstream carries them as `<LOINC num=…>`, and the MFB datatest generator
tests that same number — so nothing is translated and there is no NHG Tabel 45 mapping to maintain.
The accepted codes are the G-Standaard's own: `BST684T` rows with `MFBEXSRT = 4` say which LOINC
codes count as which MFB parameter, `BST685T` rows with `THMFBP = 2000` are every measurement a
rule can test — twelve, four used by current rules, the nierfunctie in 666 of them — plus weight
and height for dose checking. A code outside the list is a 400 rather than a silent no-op, because
a prescriber who sent a lab value and got no signal would read that as an all-clear. Units are
pinned per code for the same reason: the value is evaluated in the unit the rule was written in, so
mg/dL where mmol/L is expected is a different answer. One unit each and nothing is converted — a
height is `cm`, because R4 validates every `8302-2` against its own `bodyheight` profile and binds
the unit to `cm` or `[in_i]`, so the metres this interface used to convert exactly were a unit a
host's own validator rejects. See `fhir/LabDeterminations`.

**Only the most recent result per determination is sent.** A host may state a determination more
than once and only one is ever weighed upstream, so `ClinicalContextMapper.mostRecentPerDetermination`
keeps the latest per LOINC code — the same comparison the rules engine makes, so the value forwarded
is the one it would have picked. Selection is per code rather than per determination, because that
is the granularity the upstream tests on. It matters most for weight and height: the Hub's dose
check reads them by NHG id without looking at a date, so a stale weight beside a current one was a
dose band computed against the wrong patient.

**The time of day on a lab result is load-bearing.** It is what decides which result of a series is
the latest, here and again upstream, where `TCRELabValueList.MostRecent` compares the `date`
attribute alone and keeps the first of a tie — so a date-only value counts as midnight and two
results from one day are ordered by the sequence they were sent in. `LabResult` carries a nullable
`LocalTime`, and both builders write `yyyy-MM-dd'T'HH:mm:ss` with seconds always, because the
engine's `StringToDate` branches on the string being exactly ten characters and a formatter that
drops zero seconds matches neither form.

**Gender.** FHIR has four administrative genders; Prescriptor's `PatientGender` has three — `M`,
`F` and `X` ("Unknown") — and `male`, `female` and `unknown` map across. Sex-specific surveillance
checks cannot fire on `X`, so send the sex when you know it.

`other` and an absent gender are rejected with a 400 rather than coerced: `other` is not the same
assertion as `unknown` and has no upstream value, and an absent gender is a caller omission rather
than a statement about the patient. Guessing a patient's sex to satisfy a medication-surveillance
check would be the wrong kind of helpful.

## Medication surveillance in a session

`medicationStatement` carries what the patient is currently taking, so Prescriptor can check a new
prescription against it for interactions and duplicate therapy.

A host identifies a drug by **one** code, PRK or HPK, and may use a different level per entry.
Prescriptor needs PRK *and* GPK together (plus HPK where the host had it), so
`MedicationCodeResolver` looks each drug up in the `medcode` view before the session opens, and
emits:

```xml
<drug pending="false"><GStandaard PRK="18996" GPK="111111"/></drug>
```

That enrichment is what makes a mixed list safe. `MedicationType` is a single value for the whole
list, and upstream it selects which attribute is read off *every* `<drug>` — a drug missing that
attribute is dropped from surveillance without an error. Since every entry is resolved to a PRK,
fhir-hub sends `MedicationType` 9 for any non-empty list rather than deriving it from the entries,
and the host's per-entry level stops mattering. See `XmlRpcRequestBuilder.medicationType`.

**An unresolvable drug code fails the request with a 400.** Surveillance on an incomplete
medication list does not fail visibly — it answers "no interaction found", a false negative a
prescriber cannot tell from a genuine all-clear. The `OperationOutcome` names the code. Do not
soften this without a clinical decision behind it.

This is the one part of the interface that needs a database: a read-only reference lookup on
`gstandaard_views`, queried directly by `MedicationCodeResolver` over its own Hikari pool
(`GStandaardJdbcConfig`, configured under `gstandaard.datasource.*`).

## The second base

`POST /fhir/surveillance/$check-medication-request` asks the surveillance question directly —
patient context and one or more proposed prescriptions in, the signals that fire out — with no
session, no browser round trip and nothing to poll. `$check-medication-statement` asks it of a
patient's standing dossier instead, with every entry under test.

**The upstream is the Digitalis Hub** (`../hub`, `https://hub.digitalis.nl/call/`), a SOAP service
that runs both halves of Dutch medication surveillance and merges them into one report: the
G-Standaard's medisch-farmaceutische beslisregels through the clinical-rules engine, and the classic
allergy, age, duplicate-medication and dose checks through the G-Standaard service. The request is
a complete `DigitalisRx` document in the SOAP body — the same schema the session contract embeds in
a CDATA section, written by a second builder because what each upstream reads out of it differs as
much as the envelope does.

`hub/MedicationSurveillanceRequestBuilder` documents the attributes that decide what gets checked,
and four fail as silence rather than as an error: `pending` (the Hub's own checks do not run at all
without it), `trigger` (the rules engine reads this one instead), `date` (both engines filter the
dossier on it) and `ATC` (most beslisregels select on it, which is why `MedicationCodeResolver`
reads it).

**An empty Bundle can only mean the check ran and nothing fired.** Every other outcome —
unreachable, refused, or a response with no report in it — is a 500, enforced in
`MedicationSurveillanceResponseParser`.

**Two gaps are known and documented for integrators**, because each is a rule that stays silent: no
PDD or DDD is sent, so dose rules comparing the two cannot fire, and nothing distinguishes a partial
answer from a complete one because the upstream does not say. Weight and height are handled:
`LabDeterminations.NhgEquivalent` carries their NHG identity and the surveillance builder writes it
beside the LOINC element, because the Hub's dose check reads them as NHG. NHG 560 is in metres,
which is the one decision in it.

**The Hub does not adjudicate the credentials** — it overwrites the licence in the payload with its
own before calling the engine — so unlike a session, a wrong practice id is not rejected upstream.
`HubClient` records that; closing it is a deployment or product decision, not a code one.

**A second base rather than a segment under `/fhir/evs`**, because FHIR reserves the path space
under a base for resource type names; `FhirConfig.EVS_BASE` records it. Sharing one service is
deliberate: the two contracts share the payload profiles and their resource profiles, the
G-Standaard resolution with its fail-closed rule, the validator with its four dependencies and nine
exclusions, the authentication filter and the version stamped on the artifacts. A separate
deployable would duplicate all of it, including the SBOM and the SOUP inventory that ships with it.

Not shared: `EvsProvider` and `SurveillanceProvider` are deliberately unrelated marker types, so
neither base can advertise the other's operations and there is no common supertype to inject by
accident. `SurveillanceIntegrationTest` pins both CapabilityStatements.

The one thing that would justify splitting them is a different software safety classification: a
service whose *answer* is a clinical alert may classify higher under IEC 62304 than one that
launches a UI and forwards a medication list, and merging puts the whole codebase in the higher
class. That is a regulatory decision and it has not been taken.

## Dosing

`directions.coded` is an NHG Tabel 25 string, carried verbatim in the `CodedDirections` extension
and **not** decoded into `Dosage.timing` / `Dosage.doseAndRate`; `Dosage.text` carries the expanded
free text alongside it for readers that do not interpret Tabel 25.

Both ends of this interface speak Tabel 25 — Prescriptor emits it, and the HIS and XIS systems
consuming this API pass it to a pharmacy chain that reads it natively — so the extension is the
authoritative form in both directions. `$createrx-session` reads the same extension back, so a
prescription round-trips without loss. Structured dosing would be a Medicatieproces 9 zib
Gebruiksinstructie mapping, and is not attempted.

## Profiles

The canonical is **`http://spec.digitalis.nl/fhir`**: a subdomain rather than `digitalis.nl/fhir`
so the artifacts are independent of the corporate site's lifecycle, and `spec.` rather than `fhir.`
so the name stays free for the running service, with room under `/fhir` for the other contract
Digitalis publishes.

StructureDefinitions for every payload live in `ig/`, written in FSH and built with SUSHI —
including the surveillance response, profiled as `fhirhub-SurveillanceBundle` over
`fhirhub-SurveillanceFinding` entries. The payloads in `IMPLEMENTATION_GUIDE.md` are also instances
in the IG, and `IgExampleConformanceTest` validates each against the profile it claims on every
build, so the documentation cannot drift from the profiles. SUSHI itself checks nothing: it converts
FSH to JSON.

**A response profile is a claim about a mapper, so it is tested against one.** The two surveillance
profiles state what `SurveillanceBundleMapper` emits, and
`OutboundPayloadConformanceTest.theSurveillanceBundleSatisfiesItsProfile` validates a real report
through the mapper against them — for both `DrugsUnderTest` values, because `implicated.type` is the
one element that differs between the operations, and for a report that ran and found nothing,
because an `entry` cardinality tightened to look thorough would turn every all-clear into a
non-conformant response.

**The parent is plain R4, not nl-core.** Checked against `nictiz.fhir.nl.r4.nl-core`
`0.12.1-beta.1` by running the HL7 validator over the actual payload, not by reading cardinalities:

| Resource | nl-core profile | Result |
| --- | --- | --- |
| Patient | `nl-core-Patient` | **0 errors.** The one that is ready today |
| AllergyIntolerance | `nl-core-AllergyIntolerance` | **Undetermined.** The coding is a member of the required binding `…60.121.11.2` by construction, but a sibling ValueSet in the same composition uses a SNOMED filter tx.fhir.org does not support, so the binding returns `SERVER_ERROR`. Nothing left to fix on this side |
| Observation (lab) | `nl-core-LaboratoryTestResult` | **Fails on `Observation.category`,** which is required together with its `laboratoryCategory` slice and is neither sent nor read here. The TestCode objection is gone: LOINC satisfies `TestCodeLOINCCodelijst`, so Nictiz BITS **ZIB-639** no longer blocks this side |
| Condition | *none applicable* | `nl-core-MedicationContraIndication` is on **`Flag`**. Of the 8 nl-core `Condition` profiles none models a medication contra-indication |
| MedicationStatement | *none* | The package contains **no** `MedicationStatement` profile. `nl-core-MedicationUse2` is a Medicatieproces artifact, published separately |
| MedicationRequest, Communication | *none* | The package contains no profile for either resource type |

Every published nl-core R4 version is also a pre-release, so deriving today pins these profiles to a
moving parent for a claim that cannot yet be made about four of the five resources.

`meta.profile` is not asserted on any resource: asserting a profile that is only half met is worse
than asserting nothing. The output side is not blocked on that — the Bundle `ResultBundleMapper`
produces validates clean against `fhirhub-ResultBundle` — so asserting it there is a decision rather
than a dependency.

### Enforcement

Inbound payloads are validated against their profile at runtime, before the G-Standaard lookup and
before anything is sent upstream. A non-conformant body is a 400 carrying one `OperationOutcome`
issue per error. Only `error` and `fatal` reject — warnings are routine, because the G-Standaard
code systems are `content: not-present` and can never be expanded.

Outbound Bundles are **not** validated at runtime: putting the reference validator in the path of
every response, for a payload this service built itself, is not worth 70 ms.
`OutboundPayloadConformanceTest` closes that gap in the build instead.

`fhirhub.validation.enabled=false` turns enforcement off, and logs a warning at startup when it
does, because a validator that is present but disabled looks exactly like one that is working.

What it costs, measured rather than estimated:

| | |
| --- | --- |
| Dependencies | **+44 MB and +27 jars**, fat jar 107 MB. 22 MB of that is the R5 and DSTU3 models, for FHIR versions this service does not speak: the validator converts what it validates up to R5, and `ValidationSupportUtils` switches over DSTU3 `ValueSet`s in one method. DSTU2, DSTU2016May and R4B are excluded in `pom.xml`, along with the IG Publisher's own dependencies — see the exclusion comments there |
| First validation | ~3 s, moved into startup by `FhirValidationConfig.warmUpValidator` so no request pays it |
| Each validation | ~70 ms, on the request path |

That 70 ms is real overhead on an operation whose own work is one XML-RPC round trip. It buys
rejecting a malformed payload before a session is opened upstream — the failure that is expensive to
undo.

## Publishing

The specification is published as an Implementation Guide at the canonical, built from `ig/`:

```bash
cd ig && npm run build            # pages -> SUSHI -> HL7 IG Publisher; output in ig/output/
hosting/deploy.sh /srv/spec.digitalis.nl        # on the host
hosting/verify.sh https://spec.digitalis.nl
```

The server half is two nginx includes — `hosting/nginx-maps.conf` in the `http` context and
`hosting/nginx-fhir.conf` inside the server block — split that way because the host already owns a
working vhost with its certificate and a placeholder at `/`.

The narrative is not written in the IG. `IMPLEMENTATION_GUIDE.md` is the whole of it, and
`ig/scripts/build-pages.mjs` splits it into pages: a specification that exists twice is a
specification that disagrees with itself. The script fails if the guide's sections, its own mapping
table and the `pages:` block of `sushi-config.yaml` stop agreeing, so a section added to the guide
cannot silently go unpublished. Only the four pages about the publication rather than the interface
are written in `ig/pages/`: the front door, the change policy, the changelog and the downloads.

**The hosting is the part the publisher does not do.** It produces flat files —
`StructureDefinition-fhirhub-Patient.html`, `.json`, `.xml`, `.ttl` — while the canonical on the
wire is `/fhir/StructureDefinition/fhirhub-Patient`. `ig/hosting/nginx-fhir.conf` maps one onto the
other, picks the representation from `Accept` with `?_format=` overriding it, and sets
`Vary: Accept`. `deploy.sh` freezes each release at `/fhir/<version>/` and refuses to overwrite a
published one: an integrator who pinned a version has validated against those bytes.

Both schemes answer, and `https` is not the optional half. A canonical is an identifier and the one
in every payload is `http` — but *fetching* is separate: the HL7 validator's SSRF protection refuses
a plain-`http` fetch before making the request and is on by default, so an `http`-only deployment is
readable in a browser and unusable by tooling. `http` still serves content rather than redirecting,
because the URL integrators have in front of them is the `http` one.

**The change policy is published too**, in `ig/pages/versioning.md`: additive-only within a major,
both an unversioned and a versioned address per artifact, and — while the status is `draft` — an
explicit warning that a breaking change can still arrive at a minor version. Cutting a release means
bumping `version` in `sushi-config.yaml`, adding an entry to `ig/package-list.json` *and* to
`ig/pages/changelog.md`, and rebuilding; `deploy.sh` refuses a release missing from
`package-list.json`, which is what tooling reads to discover releases.

Building needs Java, Node and Jekyll, plus the publisher jar. `ig/README.md` has the details and the
traps.

## Extensions

Two, both checked against Nictiz first — nothing in nl-core, zib2020 or Medicatieproces 9 covers
either. Definitions are in `src/main/resources/fhir/`.

- **`ext-Dosage.CodedDirections`** — the NHG Tabel 25 string verbatim. FHIR models dosing
  structurally and has no slot for a coded string; HL7 NL registers OIDs only for Tabel 25
  *components*, not the composite. It is the authoritative dosing instruction in both directions —
  written on the way out, read back on the way in.
- **`ext-MedicationRequest.OpiumActClassification`** — a G-Standaard bijzonder kenmerk
  (BST401T / BST922T), not a boolean: codes 2 and 65 have different consequences for a pharmacist.
  Prescriptor reports only yes/no today, corresponding to rubriek 72 nr 2, so only code 2 is
  emitted; 65 and 107 can be added without a breaking change.

## Errors

Every error is an `OperationOutcome`.

| Condition | Status |
| --- | --- |
| Missing or malformed Basic credentials | 401 |
| Upstream fault — bad organization id or key | 401 |
| Unknown or already-consumed session id | 401 |
| Invalid request | 400 |
| Prescriptor unreachable or unparseable | 500 |
| Surveillance unreachable, refused, or answering without a report | 500 |

HAPI renders its own `AuthenticationException` as `text/plain`; `error/UnauthorizedException`
exists so that no response in this API is un-parseable by a FHIR client.

## Build and run

```bash
mvn test          # no network and no database needed
mvn spring-boot:run
docker compose up --build
```

| Variable | Default |
| --- | --- |
| `PRESCRIPTOR_TARGET_URL` | `https://evs.prescriptor.nl/web_current/xmlrpc_dispatch.php` |
| `GSTANDAARD_DB_URL` | `jdbc:mariadb://192.168.31.9:3306/gstandaard_views?serverTimezone=CET&useSSL=false` |
| `GSTANDAARD_DB_USER` / `GSTANDAARD_DB_PASSWORD` | `adapter` / `adapter` |
| `PORT` | `8080` |
| `LOG_LEVEL` | `INFO` |

The target URL is validated at startup; the application fails fast if it is absent or not a URL.
Tests need no database — H2 stands in for the `medcode` view, and WireMock for Prescriptor and the
Hub.

## Deliberately different from `json-interface`

**Current medication is enriched before it is sent.** `json-interface` forwards the codes a host
supplies, at the level supplied: its `getAdditionalDrugCodes` lookup is commented out, so no
PRK + GPK pair is added. fhir-hub resolves each code against the G-Standaard first, which is why a
code `json-interface` accepts can be a 400 here. Do not "restore compatibility" by dropping the
lookup.

All three allergy members are populated in both interfaces; `prescriptor-api`'s
`OpenSessionRequestBuilder.getAllergies` is the authority on which member carries which subsystem
(`Allergies`→`OGGRP`, `AlStam`→`SNK`, `AlStof`→`SSK`).

## Known quirks, inherited deliberately

Prescriptor behaviours preserved rather than corrected. Changing them alters clinical behaviour and
needs its own decision.

- **Requesting a result ends the session.** `$session-result` is idempotent in the HTTP sense only;
  a second call for the same id returns 401.

## Open items

- **Confirm the OGGrp mapping against a G-Standaard bestandsbeschrijving.** Thesaurus 122
  ("Ongewenste medicatiegroepen") is an inference — the only group-level G-Standaard system Nictiz
  publishes, and the third G-Standaard member of the CausativeAgent binding alongside SSK and SNK —
  but nothing published uses the token `OGGrp`, so it is the one of the four not read off a label.
  See `Systems.G_STANDAARD_OGGRP`.
- **Publish the guide to `spec.digitalis.nl`.** The host is up (nginx, valid certificate, `http`
  301s to `https` preserving the path) and serves a placeholder at `/`; `/fhir/` is still a 404, so
  `IMPLEMENTATION_GUIDE.md` describes canonicals that do not yet answer. Remaining: install
  `ig/hosting/nginx-maps.conf` and `ig/hosting/nginx-fhir.conf`, run `deploy.sh` on the host, then
  `verify.sh`. Until then hand out `ig/output/package.tgz` directly: a validator reports an
  unresolvable profile as *not checked* rather than as a failure — a green run that verified
  nothing.

  **Every build shows a benign symptom of this.** `PreviousVersionComparator` fetches the previous
  release to diff against, so the publisher logs `Comparing previous version <n-1>`, a 404 from
  `packages2.fhir.org` and `packages.fhir.org`, then
  `FHIRException: Unable to resolve package id nl.digitalis.fhirhub#<n-1>` with a stack trace, and
  the per-artifact change-history pages come out empty. The build stays at `0 errors` and the first
  deploy fixes it. The version it asks for is derived from the current one, **not** read from
  `package-list.json` — it asked for `0.3.0` on a build listing only the current release — so
  editing that file will not silence it.
- **`ig/hosting/nginx-fhir.conf` has never been parsed by nginx.** Every canonical was checked to
  map onto a file that exists, and the identical rules were tested end to end in their Apache
  spelling: every canonical, all four representations, `?_format=` beating `Accept`, the dotted
  extension id, the versioned snapshot, a mistyped canonical still 404, and a `validator_cli` run
  loading the package over HTTP. `nginx -t` on the host will be the first thing to read it;
  `ig/hosting/apache-htaccess` is the tested reference.
- **Agree the change policy with the integrators.** Written and published
  (`ig/pages/versioning.md`) is not agreed. What needs their answer: how they want to be told about
  a release, and how long they need between announcement and deployment — a new parameter name is
  additive for this service and a 400 for a host that sends it too early.
- **Register an OID root, or leave the OID warnings standing.** The publisher wants an OID on every
  conformance artifact — each `StructureDefinition`, `CodeSystem`, `ValueSet` and the
  `ImplementationGuide` — as a second identifier on the *artifact*, not the OID of any underlying
  table. One warning per artifact, so the count moves with every profile added;
  `grep 'could usefully have an OID' ig/output/qa.html | wc -l` is the current number. Nothing
  consumes these by OID, and this interface is R4 only with every binding resolved by canonical
  URL, so they are left visible rather than suppressed.

  Doing it properly means a root registered with HL7 NL under `2.16.840.1.113883.2.4.3.x`;
  Digitalis is not in the copy at `ig/.pkg/oidreg.txt`. The tooling permits self-assignment and
  `Systems.java` is the reason not to: those OIDs came off the register, and minting one to quiet a
  warning would put an unregistered, permanent identifier into published artifacts. If a root is
  registered, `auto-oid-root: <root>` under `parameters:` in `sushi-config.yaml` clears them all —
  but the publisher writes its assignments to `fsh-generated/resources/oids.ini`, the directory
  SUSHI wipes on every run, so that file has to live outside `fsh-generated` and be restored before
  the publisher runs, the way `stamp-version.mjs` restores the version.
- **Decide how `/fhir/surveillance` is authenticated.** The Hub overwrites the licence in the
  payload with its own and checks nothing else, so this base accepts any well-formed Basic header
  where a session is adjudicated by Prescriptor and answers 401. Three ways out: a credential check
  in the Hub, a validating call to Prescriptor per check, or a deployment restriction in front of
  the base. The first keeps this service free of a credential store, which is the property worth
  protecting.
- **Decide whether `DetectedIssue.code` should carry a coding for the class of check.** The profile
  fixes `code.coding` at `0..0` and carries the title in `code.text`, because deriving `mfb` /
  `allergy` / `dosage` / `double-medication` / `age` from a rule id would be this interface guessing
  at a classification the upstream does not make. A Digitalis code system would make the answer
  routable on something other than the rule identifier. Reopening it widens a `max` — additive
  under the published policy, so not urgent, but the one thing a host has asked about twice.
- **Decide whether the rule text should also travel as a sanitised XHTML narrative**, rather than
  only as plain text in `detail`. It does not because a `Narrative.div` is validated against a fixed
  element list and the upstream's markup carries at least one that is not on it, so carrying it
  through would put the validity of every response at the mercy of the next rule an editor writes.
  Sanitising is possible; deciding it is worth the failure mode is not a code decision.
- **Assert `meta.profile` on the surveillance response, or leave the guide's sentence standing.**
  The Bundle validates clean against `fhirhub-SurveillanceBundle`, so claiming it would be honest —
  but the guide tells integrators not to route on `meta.profile` and nothing this service emits
  claims one. Same question as the `$session-result` Bundle below; answer both the same way.
- **Close the two remaining gaps in what the check covers**: PDD and DDD, which needs Tabel 25
  decoded and is the expensive one, and a way to tell a partial report from a complete one, which
  has to come from the Hub.
- **Reconcile the unit of NHG 560 with `evs2.0`.** This interface sends the length in metres, per
  the NHG determination and the Hub's Mosteller expression, which multiplies by 100.
  `evs2.0/library/Prescriptor/OpenSession/Loader/Patient.php` reads the same element as centimetres.
  Only the Hub reads what this interface builds, so nothing is wrong today — but the two readers
  disagree about a schema element, and whichever is corrected, the other one moves.
- **A sandbox for integrator self-testing.** `../tests-digitalisrx-testpatients` is the natural
  seed.
- **No resource sets `meta.profile`.** `fhir/Profiles.java` holds the canonicals and the providers
  pass them to the validator, so every inbound payload is checked — but nothing this service emits
  claims a profile. The `$session-result` Bundle is the one that could: it validates clean against
  `fhirhub-ResultBundle` (`OutboundPayloadConformanceTest`). The guide tells integrators not to
  route on `meta.profile`; assert it or leave that sentence standing, but do not let the two
  disagree.
