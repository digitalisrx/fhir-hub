# fhir-hub

One FHIR R4 interface in front of **two distinct Digitalis applications**, on two FHIR bases:

| | | |
| --- | --- | --- |
| **Prescriptor** | `/fhir/evs` | Prescribing, in Prescriptor's own UI. Session-based: open, hand the browser over, collect the result. Functionally equivalent to **v2** of the JSON interface in `../json-interface`, with one deliberate difference — **authentication has moved out of the message body and onto the HTTP layer** |
| **Surveillance** | `/fhir/surveillance` | Medication surveillance on its own, asked as a question and answered in the payload. Both halves of it — the G-Standaard's beslisregels and the classic checks — through the Digitalis Hub. See *A second base for medication surveillance* below |

Everything below this line is about Prescriptor unless it says otherwise, because that is the
larger contract and the one with a JSON-API predecessor.

Like its predecessor it is a stateless proxy: it accepts FHIR, translates to the XML-RPC
dialect Prescriptor speaks, and translates the answer back. No session state and no credential
store. It does read the G-Standaard database, read-only, to resolve current medication — see
*Medication surveillance* below.

**Integrating?** Read [IMPLEMENTATION_GUIDE.md](IMPLEMENTATION_GUIDE.md) — one section per
application, then every endpoint with its input and output specification. This README covers the
design rationale behind those specifications.

## The Prescriptor flow

1. The host opens a session with the patient context. It gets back a launch URL and a session id.
2. The care provider works in the Prescriptor UI at that URL.
3. The host polls for the result and receives the prescriptions and advice.

```
POST   /fhir/evs/$formulary-session        Parameters  ->  Parameters (sessionId, url)
POST   /fhir/evs/$createrx-session         Parameters  ->  Parameters (sessionId, url)
GET    /fhir/evs/$session-result?session=  ->  Bundle (MedicationRequest, Communication)
GET    /fhir/evs/metadata                  ->  CapabilityStatement (unauthenticated)
```

A second FHIR base carries the medication-surveillance contract — see *A second base for
medication surveillance* below. It talks to a different upstream (the Digitalis Hub, over SOAP)
and shares everything else:

```
POST   /fhir/surveillance/$check-medication   Parameters  ->  Bundle (DetectedIssue)
GET    /fhir/surveillance/metadata            ->  CapabilityStatement (unauthenticated)
```

`software.version` on that statement is the release of the published specification the deployment
implements, read off the profiles in the jar by `SpecificationVersion` — see *Publishing*. It is
how an integrator following the change policy finds out whether a parameter introduced in a later
release will be accepted, which matters because the inbound slicing is closed and an unknown
parameter name is a 400.

Responses are JSON by default. A browser gets a syntax-highlighted HTML rendering, because
HAPI's `ResponseHighlighterInterceptor` is registered; `?_format=json`, `?_format=xml` or
`?_format=html` overrides that for any client.

Custom operations rather than a resource REST API: the interaction is a remote procedure call
with a side effect, not CRUD over stored resources, and `Parameters` is the FHIR container
built for exactly that. It also maps one-to-one onto the three endpoints hosts already use.

## Authentication

```
Authorization: Basic base64(organization.id ":" organization.key)
```

The two halves are the same organization id and key that sit in the JSON body of v2, so no host
needs new credentials to migrate.

fhir-hub does **not** validate them. Prescriptor owns the licence administration and answers
with an XML-RPC fault when a pair is wrong; fhir-hub forwards the pair as the `PracticeID` and
`LicenseKey` members and surfaces that fault as a 401. This is what keeps the service stateless
— there is no credential store to provision, rotate, or keep in step with Prescriptor.

SMART-on-FHIR / OAuth 2.0 is the upgrade path. It slots in at `SecurityConfig` and
`CredentialsResolver`; nothing downstream of those changes.

## Opening a session

```jsonc
POST /fhir/evs/$formulary-session
Content-Type: application/fhir+json
Authorization: Basic ...

{
  "resourceType": "Parameters",
  "parameter": [
    { "name": "patient", "resource": {
        "resourceType": "Patient", "gender": "female", "birthDate": "1980-01-01" } },
    { "name": "reason", "valueCodeableConcept": { "coding": [ {
        "system": "urn:oid:2.16.840.1.113883.2.4.4.31.1", "code": "A01" } ] } },
    { "name": "endSessionUrl", "valueUrl": "https://host.example/done" },
    { "name": "xisId",      "valueString": "xis-001" },
    { "name": "xisVersion", "valueString": "1.0" },

    { "name": "allergyIntolerance", "resource": {
        "resourceType": "AllergyIntolerance",
        "clinicalStatus": { "coding": [ {
          "system": "http://terminology.hl7.org/CodeSystem/allergyintolerance-clinical",
          "code": "active" } ] },
        "patient": { "extension": [ {
          "url": "http://hl7.org/fhir/StructureDefinition/data-absent-reason",
          "valueCode": "unknown" } ] },
        "code": { "coding": [ {
          "system": "urn:oid:2.16.840.1.113883.2.4.4.1.750", "code": "10499" } ] } } },
    { "name": "condition", "resource": {
        "resourceType": "Condition",
        "subject": { "extension": [ {
          "url": "http://hl7.org/fhir/StructureDefinition/data-absent-reason",
          "valueCode": "unknown" } ] },
        "code": { "coding": [ {
          "system": "urn:oid:2.16.840.1.113883.2.4.4.1.902.40",
          "code": "228" } ] } } },
    { "name": "observation", "resource": {
        "resourceType": "Observation",
        "status": "final",
        "code": { "coding": [ {
          "system": "http://loinc.org", "code": "62238-1" } ] },
        "effectiveDateTime": "2024-07-04",
        "valueQuantity": { "value": 65, "unit": "mL/min/1.73m2",
                           "system": "http://unitsofmeasure.org",
                           "code": "mL/min/{1.73_m2}" } } }
  ]
}
```

`reason` is required for `$formulary-session` and optional for `$createrx-session`, matching
the ICPC requirement of the two upstream methods. ICPC codes must match
`^[A-Z][0-9]{2}(\.[0-9]{2})?$` (`A01`, `U71.01`), and `endSessionUrl` must be `http` or
`https` — both rejected with a 400 rather than passed upstream.

`xisId` and `xisVersion` identify the calling system. Both are required and neither is forwarded
to Prescriptor: they exist so a log line can be attributed to a supplier and a release, which
matters more with sixteen integrators than with one.

### Editing an existing prescription (CreateRx only)

`$createrx-session` accepts a prescription the host already holds, so the care provider opens
it for editing rather than starting over. It is modelled as a `MedicationRequest` — the mirror
image of what `$session-result` returns, so a host can hand back what it received:

```jsonc
{ "name": "prescription", "resource": {
    "resourceType": "MedicationRequest",
    "status": "active", "intent": "order",
    "subject": { "extension": [ {
      "url": "http://hl7.org/fhir/StructureDefinition/data-absent-reason",
      "valueCode": "unknown" } ] },
    "medicationCodeableConcept": { "coding": [
      { "system": "urn:oid:2.16.840.1.113883.2.4.4.10", "code": "18996",
        "display": "PARACETAMOL ZETPIL 1000MG" },
      { "system": "http://www.whocc.no/atc", "code": "N02BE01" } ] },
    "dosageInstruction": [ { "extension": [ {
      "url": "http://spec.digitalis.nl/fhir/StructureDefinition/ext-Dosage.CodedDirections",
      "valueString": "3-4D1S; gedurende max. 1 maand" } ] } ],
    "dispenseRequest": { "quantity": {
      "value": 15, "code": "ST",
      "system": "urn:oid:2.16.840.1.113883.2.4.4.1.900.2" } } } }
```

The coded directions are read from the `CodedDirections` extension, falling back to
`Dosage.text`. Sending it to `$formulary-session` is a 400.

`status`, `intent` and `subject` are mandatory in base R4 and unread here, so they carry the same
`data-absent-reason` filler as everything else the host has nothing to point at. The mirror is not
quite perfect in one place: the product must be a PRK or an HPK, and `$session-result` may have
returned the prescription at GPK level — that one has to be resolved before it can be handed
back.

## How the JSON interface maps onto FHIR

| JSON interface | FHIR |
| --- | --- |
| `icpc` | `Coding`, ICPC-1 NL (`urn:oid:2.16.840.1.113883.2.4.4.31.1`) |
| `patient.gender` | `Patient.gender` — see *Gender* below |
| `patient.dob` | `Patient.birthDate` |
| `allergies[]` + `SSK`/`SNK`/`OGGrp` | `AllergyIntolerance.code.coding` |
| `contraIndications[]` + `CICode`/`ICPC` | `Condition.code.coding` |
| `medications[]` + `PRK`/`HPK` | `MedicationStatement.medicationCodeableConcept` — see *Medication surveillance* |
| `laboratoryData[].memo`/`mat`/`bijz` | `Observation.code.coding` in LOINC, forwarded as `<LOINC num=…>` — see *Lab determinations* |
| `laboratoryData[].date` / `.value` | `Observation.effectiveDateTime` / `.value[x]` — the time of day is carried, see *Lab determinations* |
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

**Lab determinations: LOINC all the way through, from a closed list.** A host codes lab results in
LOINC like the rest of FHIR, the upstream carries them as `<LOINC num=…>`, and the MFB datatest
generator tests that same number — so nothing is translated and there is no NHG Tabel 45 mapping to
maintain. The accepted codes are the G-Standaard's own: `BST684T` rows with `MFBEXSRT = 4` publish
which LOINC codes count as which MFB parameter, and `BST685T` rows with `THMFBP = 2000` are every
measurement a rule can test — twelve, four used by current rules, the nierfunctie in 666 of them —
plus weight and height for dose checking. A code outside the list is a 400, not a silent no-op,
because a prescriber who sent a lab value and got no signal would read that as an all-clear. Units
are pinned per code for the same reason: the value is evaluated in the unit the rule was written in,
so mg/dL where it expects mmol/L is a different answer. See *Lab determinations* in
`IMPLEMENTATION_GUIDE.md` and `fhir/LabDeterminations`.

**Several results for one determination: the upstream takes the most recent, so the moment is part
of the payload.** `TProtocolParserDataLOINC.GetValueExt` in the rules engine
(`../clinical-rules-engine/.../LogicUnits/data/uDataLOINC.pas`) tests `MostRecent` and ignores the
rest, and `TCRELabValueList.MostRecent` compares the `date` attribute alone, keeping the first of a
tie. `LabResult` therefore carries a `LocalTime` beside its `LocalDate` and `DigitalisRxBuilder`
writes `yyyy-MM-dd'T'HH:mm:ss` when the host stated a time — with seconds always, because
`StringToDate` reads exactly ten characters as a date and anything else as a full moment, so a
formatter that drops zero seconds matches neither. Truncating to a date, as this did until now,
ordered two same-day results by the sequence the host listed them in. This service still forwards
every result as it arrived: which one counts is the engine's decision, not one to pre-empt here.

**Gender.** FHIR has four administrative genders; Prescriptor's `PatientGender` has three —
`M`, `F` and `X` ("Unknown"). `male`, `female` and `unknown` all map across. Sex-specific
surveillance checks cannot fire on `X`, so send the sex when you know it.

`other` and an absent gender are rejected with a 400 rather than coerced: `other` is not the
same assertion as `unknown` and has no upstream value, and an absent gender is a caller
omission rather than a statement about the patient. Guessing a patient's sex to satisfy a
medication-surveillance check would be the wrong kind of helpful.

Note that this is wider than v2, whose JSON schema enumerated `["F", "M"]` only.

## Medication surveillance

`medicationStatement` carries what the patient is currently taking, so Prescriptor can check a
new prescription against it for interactions and duplicate therapy. Send one parameter per drug:

```jsonc
{ "name": "medicationStatement", "resource": {
    "resourceType": "MedicationStatement",
    "medicationCodeableConcept": { "coding": [
      { "system": "urn:oid:2.16.840.1.113883.2.4.4.10", "code": "18996" } ] } } }
```

A host identifies a drug by **one** code, PRK or HPK, and may use a different level for each
entry in the list. Prescriptor needs PRK *and* GPK together (plus HPK where the host had it), so
`MedicationCodeResolver` looks each drug up in the `medcode` view of the G-Standaard database
before opening the session, and emits:

```xml
<drug pending="false"><GStandaard PRK="18996" GPK="111111"/></drug>
```

That enrichment is also what makes a mixed list safe. The `MedicationType` member of the
open-session call is a single value for the whole list, and upstream it selects which attribute is
read off *every* `<drug>` — a drug missing that attribute is dropped from surveillance without an
error. Since every entry is resolved to a PRK, fhir-hub sends `MedicationType` 9 for any non-empty
list rather than deriving it from the entries, and the host's per-entry level stops mattering. See
`XmlRpcRequestBuilder.medicationType`.

**An unresolvable drug code fails the request with a 400.** This is deliberate. Surveillance
running on an incomplete medication list does not fail visibly — it answers "no interaction
found", which is a false negative in the dangerous direction, and a prescriber cannot tell it
apart from a genuine all-clear. Refusing the session and naming the offending code is the safe
behaviour. The `OperationOutcome` says which code could not be resolved.

This is the one part of the interface that needs a database. It is a read-only reference
lookup on `gstandaard_views`, queried directly by `MedicationCodeResolver` over its own Hikari
pool (`GStandaardJdbcConfig`, configured under `gstandaard.datasource.*`), and it holds no state
of its own.

## A second base for medication surveillance

Everything above happens inside a session: the host hands over the medication list, Prescriptor
runs surveillance while the care provider works, and the signals are shown in Prescriptor's own
UI. `POST /fhir/surveillance/$check-medication` asks the same question directly — patient context
and one or more proposed prescriptions in, the signals that fire out — with no session, no browser
round trip and nothing to poll.

**The upstream is the Digitalis Hub** (`../hub`, `https://hub.digitalis.nl/call/`), a SOAP service
that runs both halves of Dutch medication surveillance and merges them into one report: the
G-Standaard's medisch-farmaceutische beslisregels through the clinical-rules engine, and the
classic allergy, age, duplicate-medication and dose checks through the G-Standaard service. The
request is a complete `DigitalisRx` document in the SOAP body — the same schema the session
contract embeds in a CDATA section, written by a second builder because what each upstream reads
out of it differs as much as the envelope does. The report comes back as a `Bundle` of
`DetectedIssue`, one entry per signal.

`hub/MedicationSurveillanceRequestBuilder` is where the attributes that decide what gets checked
are documented, and there are four of them that fail as silence rather than as an error:
`pending` (the Hub's own checks do not run at all without it), `trigger` (the rules engine reads
this one instead), `date` (both engines filter the dossier on it) and `ATC` (most beslisregels
select on it, which is why `MedicationCodeResolver` now reads it). Two published examples each
carry one of the first two, which is how the split was found.

**An empty Bundle can only mean the check ran and nothing fired.** Every other outcome —
unreachable, refused, or a response with no report in it — is a 500, enforced in
`MedicationSurveillanceResponseParser`. That is the same rule as the 400 on an unresolvable drug
code, and it is why this operation answered 501 for a release rather than "no issues found".

**Three gaps are known and documented for integrators** in the guide, because each is a rule that
stays silent: no PDD or DDD is sent, so dose rules comparing the two cannot fire; weight and height
travel as LOINC and the Hub's dose check reads them from NHG-coded elements this interface does not
send yet, which surfaces as a "data missing" signal rather than a pass; and nothing distinguishes a
partial answer from a complete one, because the upstream does not say. **And the Hub does not
adjudicate the credentials** — it overwrites the licence in the payload with its own before calling
the engine — so unlike a session, a wrong practice id is not rejected upstream. `HubClient` records
that; closing it is a deployment or a product decision, not a code one.

**A second base rather than a second service**, and a second base rather than a path under
`/fhir/evs`. FHIR reserves the path space under a base for resource type names, so a second
contract cannot be a segment inside one — `FhirConfig.EVS_BASE` records that. Sharing the service
is a decision with a reason: the two contracts share the payload profiles and their resource
profiles, the G-Standaard resolution with its fail-closed rule, the validator with its four
dependencies and nine exclusions, the authentication filter and the version stamped on the
artifacts. A separate deployable would duplicate all of it, including the SBOM and the SOUP
inventory that ships with it — 92 items from 20 suppliers, twice, overlapping by almost all of it.

What is *not* shared is the provider set: `EvsProvider` and `SurveillanceProvider` are deliberately
unrelated marker types, so neither base can advertise the other's operations. There is no common
supertype to inject by accident, and `SurveillanceIntegrationTest` pins both CapabilityStatements.

The one thing that would justify splitting them is a different software safety classification: a
service whose *answer* is a clinical alert may classify higher under IEC 62304 than one that
launches a UI and forwards a medication list, and merging puts the whole codebase in the higher
class. That is a regulatory decision and it has not been taken.

## Dosing

`directions.coded` is an NHG Tabel 25 string. It is carried verbatim in the `CodedDirections`
extension and **not** decoded into `Dosage.timing` / `Dosage.doseAndRate`; `Dosage.text` carries
the expanded free text alongside it for readers that do not interpret Tabel 25.

Both ends of this interface speak Tabel 25 — Prescriptor emits it, and the HIS and XIS systems
consuming this API pass it to a pharmacy chain that reads it natively — so the extension is the
authoritative form in both directions. `$createrx-session` reads the same extension back, which
means a prescription can be round-tripped without loss. Structured dosing would be a Medicatieproces 9
zib Gebruiksinstructie mapping, and is not attempted today.

## Profiles

The canonical is **`http://spec.digitalis.nl/fhir`**. A subdomain rather than `digitalis.nl/fhir`
so the artifacts are independent of the corporate site's lifecycle and can be served as static
files by whoever owns the IG, and `spec.` rather than `fhir.` so the name stays free for the
running service and leaves room under `/fhir` for the other contract Digitalis publishes,
`json-interface`'s OpenAPI. What is served there, and how, is under *Publishing* below.

StructureDefinitions for every payload live in `ig/`, written in FSH and built with SUSHI. They
cover the two session inputs, the session output, the `$session-result` Bundle, the five
resources a host sends in, the two Digitalis extensions, and the terminology behind them. The
payloads in `IMPLEMENTATION_GUIDE.md` are also instances in the IG, and
`IgExampleConformanceTest` validates each one against the profile it claims on every build — so
the documentation cannot drift from the profiles. SUSHI itself checks nothing: it converts FSH to
JSON, and one example had already drifted before that test existed.

**The parent is plain R4, not nl-core.** That is a checked decision, not a shortcut. Verified
against `nictiz.fhir.nl.r4.nl-core` `0.12.1-beta.1`:

Each row below was checked by running the HL7 validator over the actual payload against the
nl-core profile, not by reading cardinalities:

| Resource | nl-core profile | Result |
| --- | --- | --- |
| Patient | `nl-core-Patient` | **0 errors.** The one that is ready today |
| AllergyIntolerance | `nl-core-AllergyIntolerance` | **Undetermined.** Since the OIDs were pinned the coding is a member of the required binding `…60.121.11.2` by construction — that ValueSet composes `…60.40.2.8.2.14`, which includes `urn:oid:…1.750` unfiltered. The validator cannot confirm it: a sibling ValueSet in the same composition uses a SNOMED filter (`concept in 98061000146100`) that tx.fhir.org does not support, so the whole binding returns `SERVER_ERROR`. Nothing left to fix on this side |
| Observation (lab) | `nl-core-LaboratoryTestResult` | **Fails on `Observation.category`,** which is required together with its `laboratoryCategory` slice and is neither sent nor read here. The TestCode objection is gone: since lab values are LOINC, they satisfy `TestCodeLOINCCodelijst`, which composes all of `http://loinc.org` — so Nictiz BITS **ZIB-639** no longer blocks this side. Worth a fresh validator run before claiming anything |
| Condition | *none applicable* | The contra-indication profile, `nl-core-MedicationContraIndication`, is on **`Flag`**. Of the 8 nl-core `Condition` profiles none models a medication contra-indication |
| MedicationStatement | *none* | The package contains **no** `MedicationStatement` profile. `nl-core-MedicationUse2` is a Medicatieproces artifact, published separately |
| MedicationRequest, Communication | *none* | The package contains no profile for either resource type |

Every published nl-core R4 version is also a pre-release (`beta`, `rc`, `labtrial`), so deriving
today pins these profiles to a moving parent for a claim we cannot yet make about four of the
five resources.

`meta.profile` is not asserted on any resource. Asserting a profile that is only half met is
worse than asserting nothing: validators reject it, and integrators will have trusted the claim.
The output side is not blocked on that, though — the Bundle `ResultBundleMapper` produces
validates clean against `fhirhub-ResultBundle` — so asserting it there is a decision rather than
a dependency.

### Enforcement

Inbound payloads are validated against their profile at runtime, before the G-Standaard lookup
and before anything is sent upstream. A non-conformant body is a 400 carrying one
`OperationOutcome` issue per error. Only `error` and `fatal` reject — warnings are routine,
because the G-Standaard code systems are `content: not-present` and can never be expanded.

Outbound Bundles are **not** validated at runtime: putting the reference validator in the path
of every response, for a payload this service built itself, is not worth 70 ms.
`OutboundPayloadConformanceTest` closes that gap in the build instead.

Set `fhirhub.validation.enabled=false` to turn enforcement off. It logs a warning at startup
when you do, because a validator that is present but disabled looks exactly like one that is
working.

What it costs, measured rather than estimated:

| | |
| --- | --- |
| Dependencies | **+44 MB and +27 jars**, fat jar 107 MB. 22 MB of that is the R5 and DSTU3 models, for FHIR versions this service does not speak: the validator converts what it validates up to R5, and `ValidationSupportUtils` switches over DSTU3 `ValueSet`s in one method. DSTU2, DSTU2016May and R4B are excluded in `pom.xml`, along with the IG Publisher's own dependencies — see the exclusion comments there |
| First validation | ~3 s, moved into startup by `FhirValidationConfig.warmUpValidator` so no request pays it |
| Each validation | ~70 ms, on the request path |

That 70 ms is real overhead on an operation whose own work is one XML-RPC round trip. It buys
rejecting a malformed payload before a session is opened upstream, which is the failure that is
expensive to undo.

## Publishing

The specification is published as an Implementation Guide at the canonical, built from `ig/`:

```bash
cd ig && npm run build            # pages -> SUSHI -> HL7 IG Publisher; output in ig/output/
hosting/deploy.sh /srv/spec.digitalis.nl        # on the host
hosting/verify.sh https://spec.digitalis.nl
```

The server half is two nginx includes — `hosting/nginx-maps.conf` in the `http` context and
`hosting/nginx-fhir.conf` inside the `spec.digitalis.nl` server block. Split that way because the
host already owns a working vhost with its certificate and a placeholder at `/`: a whole-vhost
config would have to be merged into it by hand every time either side changed.

The narrative is not written in the IG. `IMPLEMENTATION_GUIDE.md` is the whole of it, and
`ig/scripts/build-pages.mjs` splits it into pages — a specification that exists twice is a
specification that disagrees with itself. The script fails if the guide's sections, its own
mapping table and the `pages:` block of `sushi-config.yaml` stop agreeing, so a section added to
the guide cannot silently go unpublished. Only the four pages that are about the publication
rather than the interface are written in `ig/pages/`: the front door, the change policy, the
changelog, and the download instructions.

**The hosting is the part the publisher does not do.** It produces flat files —
`StructureDefinition-fhirhub-Patient.html`, `.json`, `.xml`, `.ttl` — and the canonical on the
wire is `/fhir/StructureDefinition/fhirhub-Patient`. `ig/hosting/nginx-fhir.conf` maps one onto the
other, picks the representation from `Accept` with `?_format=` overriding it, and sets `Vary:
Accept` so nothing in between can serve a page to a validator. `deploy.sh` also freezes each
release at `/fhir/<version>/`, and refuses to overwrite one that is already published: an
integrator who pinned a version has validated against those bytes and would not be told.

Both schemes answer, and `https` is not the optional half. A canonical is an identifier, and the
one in every payload already in the field is `http` — but *fetching* is a separate matter: the HL7
validator's SSRF protection refuses a plain-`http` fetch before it makes the request, is on by
default, and by its own help text "should always be enabled in production" (measured against
`validator_cli` 6.x, not assumed). Serve only `http` and the guide is readable in a browser and
unusable by tooling. `http` still serves content rather than redirecting, because the URL
integrators have in front of them is the `http` one.

**The change policy is published too**, because with a dozen HIS suppliers there is no other way
to say what a version number means. It is in `ig/pages/versioning.md`: additive-only within a
major, both an unversioned and a versioned address per artifact, and — while the status is
`draft` — an explicit warning that a breaking change can still arrive at a minor version. Cutting
a release means bumping `version` in `sushi-config.yaml`, adding an entry to `ig/package-list.json`
*and* to `ig/pages/changelog.md`, and rebuilding; `deploy.sh` refuses a release that is missing
from `package-list.json`, which is what tooling reads to discover releases.

Building needs Java, Node and Jekyll, plus the publisher jar. `ig/README.md` has the details and
the traps.

## Extensions

Two, both checked against Nictiz first — nothing in nl-core, zib2020, or Medicatieproces 9
covers either. Definitions are in `src/main/resources/fhir/`.

- **`ext-Dosage.CodedDirections`** — the NHG Tabel 25 string verbatim. FHIR models dosing
  structurally and has no slot for a coded string; HL7 NL registers OIDs only for Tabel 25
  *components*, not the composite. It is the authoritative dosing instruction in both
  directions — written on the way out, read back on the way in. See *Dosing* above.
- **`ext-MedicationRequest.OpiumActClassification`** — a G-Standaard bijzonder kenmerk
  (BST401T / BST922T), not a boolean. Codes 2 and 65 have different consequences for a
  pharmacist. Prescriptor reports only yes/no today, corresponding to rubriek 72 nr 2, so only
  code 2 is emitted; 65 and 107 can be added without a breaking change.

## Errors

Every error is an `OperationOutcome`, with the same status codes the JSON interface used.

| Condition | Status |
| --- | --- |
| Missing or malformed Basic credentials | 401 |
| Upstream fault — bad organization id or key | 401 |
| Unknown or already-consumed session id | 401 |
| Invalid request | 400 |
| Prescriptor unreachable or unparseable | 500 |

HAPI renders its own `AuthenticationException` as `text/plain`; `error/UnauthorizedException`
exists so that no response in this API is un-parseable by a FHIR client.

## Build and run

```bash
mvn test          # 103 tests; no network and no database needed
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
Tests need no database — H2 stands in for the `medcode` view.

## Differences from the JSON interface

**Current medication is enriched before it is sent.** `json-interface` forwards the codes a host
supplies, at the level supplied: its `getAdditionalDrugCodes` lookup is commented out, so the
`additionalCodes` list stays empty and no PRK + GPK pair is added. fhir-hub resolves each code
against the G-Standaard first — see *Medication surveillance* above — which is also why a code
that `json-interface` accepts can be a 400 here.

All three allergy members are populated in both interfaces; `prescriptor-api`'s
`OpenSessionRequestBuilder.getAllergies` is the authority on which member carries which
subsystem (`Allergies`→`OGGRP`, `AlStam`→`SNK`, `AlStof`→`SSK`).

## Known quirks, inherited deliberately

These are Prescriptor behaviours preserved from the JSON interface rather than corrected.
Changing them alters clinical behaviour and needs its own decision.

- **Requesting a result ends the session.** `$session-result` is idempotent in the HTTP sense
  only; a second call for the same id returns 401.

## Open items

- **Confirm the OGGrp mapping against a G-Standaard bestandsbeschrijving.** Thesaurus 122
  ("Ongewenste medicatiegroepen") is an inference — it is the only group-level G-Standaard
  system Nictiz publishes and the third G-Standaard member of the CausativeAgent binding
  alongside SSK and SNK — but nothing published uses the token `OGGrp`, so it is the one of the
  four that was not read off a label. See `Systems.G_STANDAARD_OGGRP`.
- **Publish the guide to `spec.digitalis.nl`.** The host is up — nginx at 37.97.148.163, a valid
  certificate, and `http` 301s to `https` preserving the path — and serves a placeholder at `/`.
  `/fhir/` is still a 404, so `IMPLEMENTATION_GUIDE.md` currently describes canonicals that do not
  yet answer. Remaining: install `ig/hosting/nginx-maps.conf` and `ig/hosting/nginx-fhir.conf`, run
  `deploy.sh` on the host, then `verify.sh`. Until then hand out `ig/output/package.tgz` directly,
  because an integrator's validator reports an unresolvable profile as *not checked* rather than as
  a failure — a green run that verified nothing.

  Since 0.2.0 this has a second visible symptom: the publisher logs
  `FHIRException: Unable to resolve package id nl.digitalis.fhirhub#0.1.0` and the per-artifact
  *change history* pages come out empty. It fetches the previous release from the canonical to
  diff against, and there is nothing at that address yet. Not an error — the build stays at
  `0 errors` — and it fixes itself with the first deploy.
- **`ig/hosting/nginx-fhir.conf` has never been parsed by nginx.** Every canonical was checked to
  map onto a file that exists, and the identical rules were tested end to end in their Apache
  spelling — every canonical, all four representations, `?_format=` beating `Accept`, the dotted
  extension id, the versioned snapshot, a mistyped canonical still 404, and a `validator_cli` run
  that loaded the package over HTTP from it. But neither nginx nor a container runtime was
  available where it was written, so `nginx -t` on the host is the first thing that will have read
  it. `ig/hosting/apache-htaccess` is the tested reference for what the rules are meant to do, and
  the one to use if the guide ever moves to the Apache host that serves `www.digitalis.nl`.
- **Agree the change policy with the integrators.** It is written and published
  (`ig/pages/versioning.md`), which is not the same as agreed. The part that needs their answer is
  how they want to be told about a release, and how long they need between the announcement and
  the deployment — a new parameter name is additive for this service and a 400 for a host that
  sends it too early, because the inbound slicing is closed.
- **Register an OID root for the terminology, or leave the ten warnings standing.** The publisher
  asks for an OID on each `CodeSystem` and `ValueSet` — a second identifier on the *artifact*, so
  a consumer that names terminology by OID rather than by URI can reference it. Note that this is
  not the OID of the underlying table: `gstandaard-bijzonder-kenmerk` uses a Digitalis URI because
  no OID is registered for BST401T, and an artifact OID would not change that.

  Nothing consumes these by OID today. The warning's own rationale is "possible use with OID based
  terminology systems e.g. CDA usage", and this interface is FHIR R4 only — every binding is
  resolved by canonical URL, in the published IG and at runtime. So the warnings are left visible
  rather than suppressed or answered.

  Doing it properly means a root registered with HL7 NL under `2.16.840.1.113883.2.4.3.x`, the
  register's "Assigning authorities via HL7 NL" branch; Digitalis is not in the copy at
  `ig/.pkg/oidreg.txt`. The tooling permits self-assignment and `Systems.java` is the reason not
  to: those OIDs came off the register, and minting one here to quiet a warning would put an
  unregistered, permanent identifier into published artifacts.

  If a root is ever registered: `auto-oid-root: <root>` under `parameters:` in `sushi-config.yaml`
  clears all ten. One trap, measured — the publisher writes its assignments to
  `fsh-generated/resources/oids.ini`, which is the directory SUSHI wipes on every run, and that
  file says of itself that it must be committed. Assignment is per resource type and alphabetical
  (`ValueSet = <root>.48.1…`), so it survives a wipe unchanged only while the set of artifacts
  does. Adopting it therefore needs the file kept outside `fsh-generated` and restored before the
  publisher runs, the way `stamp-version.mjs` restores the version.
- **Decide how `/fhir/surveillance` is authenticated.** The Hub overwrites the licence in the
  payload with its own before it calls the rules engine and checks nothing else, so this base
  currently accepts any well-formed Basic header — where a session is adjudicated by Prescriptor
  and answers 401. Three ways out: a credential check in the Hub, a validating call to Prescriptor
  from this service (which costs a round trip per check), or a deployment restriction in front of
  the base. The first keeps this service free of a credential store, which is the property worth
  protecting.
- **Publish a response profile for `$check-medication`, once the shape has been reviewed against
  real reports.** The guide describes the Bundle and the `DetectedIssue` elements in prose and
  asserts no `meta.profile`, which is honest for a shape a week old and is not where it should
  stay. What to settle first: whether `DetectedIssue.code` should carry a coding for the class of
  check (a Digitalis code system for `mfb` / `allergy` / `dosage` / `double-medication` / `age`
  would make the answer routable), and whether the rule text should also travel as a sanitised
  XHTML narrative rather than only as plain text in `detail`.
- **Close the three gaps in what the check covers**: PDD and DDD (which needs Tabel 25 decoded, so
  it is the expensive one), weight and height reaching dose control (which needs the NHG element
  the Hub reads, and a decision about metres versus centimetres — the Hub's BSA formula multiplies
  the length it finds by 100), and a way to tell a partial report from a complete one, which has
  to come from the Hub.
- **A sandbox for integrator self-testing.** `../tests-digitalisrx-testpatients` is the
  natural seed.
- **No resource sets `meta.profile`.** `fhir/Profiles.java` holds the canonicals and the
  providers pass them to the validator, so every inbound payload is checked against its profile —
  but nothing this service emits claims one. The Bundle is the one that could: it validates clean
  against `fhirhub-ResultBundle` (`OutboundPayloadConformanceTest`), so asserting it there is a
  decision rather than a dependency. The Implementation Guide tells integrators not to route on
  `meta.profile`; assert it or leave that sentence standing, but do not let the two disagree.
