# fhir-hub Implementation Guide

FHIR R4 interface for the Digitalis Prescriptor and Surveillance applications.

| | | |
| --- | --- | --- |
| **[Prescriptor](#prescriptor)** | `/fhir/evs` | Prescribing in Prescriptor's own interface: open a session, hand the browser over, collect the result |
| **[Surveillance](#surveillance)** | `/fhir/surveillance` | Medication surveillance alone: no interface, no session — ask, and read the signals in the response |

Shared by both: [Conventions](#conventions), [Authentication](#authentication),
[Lab determinations](#lab-determinations), [Profiles](#profiles), [Code systems](#code-systems),
[Extensions](#extensions), [Errors](#errors). One set of credentials, one set of payload profiles,
one release number.

> **Work in progress.** Nothing is published at the canonical yet, and **every part of this
> specification may change without notice** — payloads, profiles, operation names, base paths.
> The change policy takes effect with the first published release.

- **FHIR R4 (4.0.1).** Every payload is a `Parameters` or a `Bundle`. No resource REST API, no
  search.
- **Stateless.** Persist the session id and the result Bundle yourself; nothing can be re-fetched.
- **The prose and the `StructureDefinition`s are one specification**, at
  `http://spec.digitalis.nl/fhir`. Every request is validated against its profile — see
  [Profiles](#profiles).

## Conventions

| | |
| --- | --- |
| Base paths | `/fhir/evs` and `/fhir/surveillance` — two FHIR bases, each with its own `metadata`. Siblings rather than nested, because FHIR reserves the path space under a base for resource type names |
| Request | `application/fhir+json`, or `application/fhir+xml` |
| Response | `application/fhir+json`, pretty-printed |
| Format override | `?_format=json`, `?_format=xml`, `?_format=html` |
| Browser rendering | A highest-ranked `Accept: text/html` gets a syntax-highlighted page, so set `Accept: application/fhir+json` in machine clients |
| Errors | Always `OperationOutcome` — no endpoint returns a non-FHIR body |

**A parameter that is undefined, unnamed, or a repeat of a single-valued one is a 400.** The
request profiles close the list of names, so `medicationstatement` for `medicationStatement` is
rejected rather than silently dropped.

## Authentication

```
Authorization: Basic base64(organization.id ":" organization.key)
```

- Invalid credentials: **401** on the operation you called. No token to obtain, none to refresh.
- Unauthenticated: `GET /fhir/evs/metadata`, `GET /fhir/evs/OperationDefinition/**` and
  `GET /actuator/health/**` — readable before your credentials are issued.
- Anything else without the header: 401 with `WWW-Authenticate: Basic`.

## Prescriptor

**Digitalis Prescriptor 3** is the prescribing application: the care provider chooses a treatment,
writes the prescription and reads the signals in its own interface. Your system supplies the
patient context, hands the browser over and collects the result.

```
Base            /fhir/evs
Interaction     session-based — open, redirect the browser, poll for the result
```

`evs` is *elektronisch voorschrijfsysteem*; part of the base, never omitted.

| Operation | |
| --- | --- |
| [`POST /fhir/evs/$formulary-session`](#post-fhirevsformulary-session) | Open a formulary session: a treatment is chosen for a stated reason for encounter |
| [`POST /fhir/evs/$createrx-session`](#post-fhirevscreaterx-session) | Open a prescribing session without a formulary lookup, optionally from a prescription you hold |
| [`GET /fhir/evs/$session-result`](#get-fhirevssession-result) | Collect the prescriptions and patient advice. Single-use: it ends the session |
| [`GET /fhir/evs/metadata`](#get-fhirevsmetadata) | The CapabilityStatement. Unauthenticated |

- **Surveillance runs inside the session**, on the medication list you send. Its signals go to the
  care provider in Prescriptor, not back to your system — for those, see
  [Surveillance](#surveillance).
- **No resource REST API and no search.** `/fhir/evs/Patient` and the like are unsupported; nothing
  is stored here to read back.

## The Prescriptor flow

Three calls, plus a browser round trip your system does not mediate.

```
Host (XIS/HIS)                fhir-hub                 Prescriptor        G-Standaard
     |                            |                         |                  |
  1  |-- POST $…-session -------->|                         |                  |
     |                            |-- resolve PRK|HPK ------------------------->|
     |                            |<- PRK + GPK (+HPK) -------------------------|
     |                            |-- open session -------->|                  |
     |                            |<- sessionId + url ------|                  |
     |<- Parameters{sessionId,url}-|                         |                  |
     |                            |                         |                  |
  2  |  redirect the user's browser to `url` ----------------->  (Prescriptor UI)
     |                            |                         |                  |
     |  user prescribes; Prescriptor redirects to endSessionUrl <---------------|
     |                            |                         |                  |
  3  |-- GET $session-result ---->|                         |                  |
     |                            |-- request result ------>|  (session ends)  |
     |<- Bundle -------------------|<- drugs + advice -------|                  |
```

1. **Open a session.** Post the patient context; receive `sessionId` and `url`. An unresolvable
   medication code fails the call: surveillance needs PRK and GPK together.
2. **The care provider works in Prescriptor** at that `url`; fhir-hub is not involved. Prescriptor
   then redirects the browser to your `endSessionUrl`.
3. **Fetch the result** with the `sessionId`: a `Bundle` of `MedicationRequest` (prescriptions) and
   `Communication` (patient advice).

**The order is mandatory.** Step 3 consumes the id, so a second `$session-result` is a 401. Persist
the id and the Bundle as they arrive.

## `GET /fhir/evs/metadata`

Unauthenticated. **Input** — none. **Output** — a `CapabilityStatement` listing
`formulary-session`, `createrx-session` and `session-result`.

```bash
curl -sS 'http://localhost:8080/fhir/evs/metadata?_format=json'
```

- **`software.version` is the specification release this deployment implements** — not a build
  number, and not the FHIR version, which is `fhirVersion`. Read it before sending anything
  introduced in a later release: an unknown parameter name is a 400, not an ignored element.
- Each operation links a generated `OperationDefinition`
  (`/fhir/evs/OperationDefinition/-s-formulary-session` and two siblings, also unauthenticated)
  with a `type` and a cardinality per parameter. The [profiles](#profiles) add what must hold
  *inside* each parameter.
- **Prescriptor base only.** [Surveillance](#surveillance) has its own, reporting the same
  `software.version`.

## `POST /fhir/evs/$formulary-session`

Opens a **formulary** session: the care provider picks a treatment for a stated reason for
encounter.

### Input

A `Parameters` resource. Anything outside these cardinalities is a 400 naming the element.

| Parameter | Card. | Type | Notes |
| --- | --- | --- | --- |
| `patient` | 1..1 | `Patient` | `gender` (`male`, `female` or `unknown`) and `birthDate` required |
| `reason` | 1..1 | `CodeableConcept` | ICPC-1 NL. **Required** for this operation |
| `endSessionUrl` | 1..1 | `url` | Where Prescriptor returns the browser. `http` or `https` only |
| `xisId` | 1..1 | `string` | Your system id, non-blank |
| `xisVersion` | 1..1 | `string` | Your release version, non-blank |
| `allergyIntolerance` | 0..* | `AllergyIntolerance` | `code.coding` in SSK, SNK or OGGrp |
| `condition` | 0..* | `Condition` | `code.coding` in CICode or ICPC |
| `medicationStatement` | 0..* | `MedicationStatement` | `medicationCodeableConcept` in PRK or HPK |
| `observation` | 0..* | `Observation` | A LOINC-coded lab determination. Several results for one determination are allowed; only the latest is used — see [Lab determinations](#lab-determinations) |
| `prescription` | 0..0 | — | Rejected here with a 400; `$createrx-session` only |

- **`patient`** — `other` or absent `gender` is a 400; send `unknown` instead. Send the real sex
  when you have it: sex-specific checks cannot fire on `unknown`, and nobody is told they were
  skipped. Nothing else in the `Patient` is read.
- **`reason`** — a `CodeableConcept`, not a bare `valueCoding`. `system`
  `urn:oid:2.16.840.1.113883.2.4.4.31.1`, code matching `^[A-Z][0-9]{2}(\.[0-9]{2})?$` (`A01`,
  `U71.01`).
- **`endSessionUrl`** — `valueUrl`, `http` or `https` only. An app deep link is a 400: use an
  `https` landing page and redirect on.
- **`xisId` / `xisVersion`** — your product and release, not the practice. Logged here and **never
  forwarded to Prescriptor**.
- **`medicationStatement`** — every code is resolved against the G-Standaard, and one that cannot be
  **fails the whole request with a 400** naming it: nothing is skipped and there is no free-text
  fallback. Levels may be mixed per entry, each expanded to a PRK + GPK pair.
- **`observation`** — a **LOINC** determination (`http://loinc.org`) with `effectiveDateTime` and a
  `valueQuantity` in the unit under [Lab determinations](#lab-determinations).

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
    { "name": "medicationStatement", "resource": {
        "resourceType": "MedicationStatement",
        "status": "active",
        "subject": { "extension": [ {
          "url": "http://hl7.org/fhir/StructureDefinition/data-absent-reason",
          "valueCode": "unknown" } ] },
        "medicationCodeableConcept": { "coding": [ {
          "system": "urn:oid:2.16.840.1.113883.2.4.4.10", "code": "18996" } ] } } },
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

`status` and `subject` are **required** and not read. Send a `data-absent-reason` as the example
does — see
[Elements FHIR requires that fhir-hub does not read](#elements-fhir-requires-that-fhir-hub-does-not-read).

### Output

`200` with a `Parameters` resource:

| Parameter | Card. | Type | Notes |
| --- | --- | --- | --- |
| `sessionId` | 1..1 | `string` | Pass this to `$session-result` |
| `url` | 1..1 | `url` | Redirect the care provider's browser here |

```json
{
  "resourceType": "Parameters",
  "parameter": [
    { "name": "sessionId", "valueString": "sess-abc-123" },
    { "name": "url", "valueUrl": "https://evs.prescriptor.nl/web_current/index.php?sk=sess-abc-123" }
  ]
}
```

Treat `url` as opaque: its shape is Prescriptor's, not part of this contract. Read the session id
from `sessionId`.

## `POST /fhir/evs/$createrx-session`

Prescribing without a formulary lookup, optionally starting from a prescription you already hold.
Identical to `$formulary-session` except:

| | |
| --- | --- |
| `reason` | **0..1** — optional, because CreateRx prescribes without a formulary lookup. Still validated against the ICPC pattern when present |
| `prescription` | **0..1** `MedicationRequest` — a prescription to open for editing |

### `prescription`

Send back the `MedicationRequest` from `$session-result`, with your edits. One difference: the
product must be **PRK or HPK**, and `$session-result` may have returned GPK — resolve it first, or
open the session without a prescription.

| Element | Card. | Notes |
| --- | --- | --- |
| `status`, `intent` | 1..1 | Mandatory in base R4, not read. `active` and `order` |
| `subject` | 1..1 | Mandatory in base R4, not read. A `data-absent-reason` of `unknown` |
| `medicationCodeableConcept` | 1..1 | Required. Must carry a PRK **or** HPK coding; PRK wins when both are given |
| `medicationCodeableConcept.coding` (ATC) | 0..1 | `http://www.whocc.no/atc`, forwarded as-is |
| `medicationCodeableConcept.text` | 0..1 | Used as the description when a coding has no `display` |
| `dosageInstruction.extension[CodedDirections]` | 0..1 | The authoritative dosing instruction |
| `dosageInstruction.text` | 0..1 | Fallback when the extension is absent |
| `dispenseRequest.quantity` | 0..1 | `value` plus `code` (or `unit`) as the G-Standaard basiseenheid |

Dosing comes from the `CodedDirections` extension, `Dosage.text` as fallback. Edits to `timing` or
`doseAndRate` are **ignored** — see [Extensions](#extensions).

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

`prescription` sent to `$formulary-session` is a 400. Output is the same
`Parameters{sessionId, url}`.

## `GET /fhir/evs/$session-result`

Fetches what a finished session produced.

### Input

| | |
| --- | --- |
| Method | `GET` |
| `session` | 1..1, query parameter — the `sessionId` from the open-session response |

```bash
curl -sS \
  -u 'practice-123:licence-key' \
  -H 'Accept: application/fhir+json' \
  'http://localhost:8080/fhir/evs/$session-result?session=sess-abc-123'
```

- Quote the URL: unquoted, the shell expands `$session-result` and you request `/fhir/-result`.
- **One shot.** The first successful call ends the session; a second is a 401, not the same Bundle.
  Store the Bundle on arrival, and make sure a retry after a timeout cannot fire twice: a lost
  response is unrecoverable.
- **Call it once, when the browser returns to your `endSessionUrl`** — that redirect is the signal.
  Do not poll: a 401 does not distinguish "unknown id" from "already collected".

### Output

`200` with a `Bundle`, `type: collection`: one `MedicationRequest` per prescribed drug, then one
`Communication` per piece of patient advice. Either list may be empty, and a session that produced
neither yields an empty Bundle rather than an error. An unknown or consumed session id is a 401.

#### `MedicationRequest`

| Element | Card. | Notes |
| --- | --- | --- |
| `status` | 1..1 | Fixed `active` |
| `intent` | 1..1 | Fixed `order` |
| `subject` | 1..1 | **Always** a `data-absent-reason: unknown` extension, never a reference |
| `medicationCodeableConcept.coding` | 1..2 | Exactly one G-Standaard coding, at the level Prescriptor prescribed at — PRK, HPK **or** GPK, with `display` — plus ATC when known |
| `medicationCodeableConcept.text` | 0..1 | Product description |
| `dosageInstruction` | 0..1 | `text` (human-readable) plus the `CodedDirections` extension |
| `dispenseRequest.quantity` | 0..1 | `value` (decimal), `code`/`unit` = G-Standaard basiseenheid, e.g. `ST` |
| `dispenseRequest.expectedSupplyDuration` | 0..1 | Days: `unit: "dag"`, `system: UCUM`, `code: "d"` |
| `extension[OpiumActClassification]` | 0..1 | Present only when the product falls under the Opiumwet |

- `subject` never carries a reference: correlate on the session id you polled with.
- Parse `quantity` as a **decimal**: partial packs are real.
- **One code level per prescription**, whichever Prescriptor prescribed at, so match on whichever
  coding is present rather than looking for PRK.

#### `Communication`

| Element | Card. | Notes |
| --- | --- | --- |
| `status` | 1..1 | Fixed `completed` |
| `category` | 1..1 | `instruction` in `http://terminology.hl7.org/CodeSystem/communication-category` |
| `payload.contentString` | 0..1 | Prose advice |
| `payload.contentAttachment` | 0..1 | A link (thuisarts.nl): `contentType: text/uri-list`, `url` set |

Exactly one of the two per `Communication` — attachment for a link, string for prose. Switch on
which is populated, not on the text.

```jsonc
{
  "resourceType": "Bundle",
  "type": "collection",
  "entry": [
    { "fullUrl": "urn:uuid:9f1b2d34-5a67-4c89-b012-3456789abcde",
      "resource": {
        "resourceType": "MedicationRequest",
        "extension": [ {
          "url": "http://spec.digitalis.nl/fhir/StructureDefinition/ext-MedicationRequest.OpiumActClassification",
          "valueCodeableConcept": { "coding": [ {
            "system": "http://spec.digitalis.nl/fhir/CodeSystem/gstandaard-bijzonder-kenmerk",
            "code": "2", "display": "Product valt onder Opiumwet in volle omvang" } ] } } ],
        "status": "active", "intent": "order",
        "subject": { "extension": [ {
          "url": "http://hl7.org/fhir/StructureDefinition/data-absent-reason",
          "valueCode": "unknown" } ] },
        "medicationCodeableConcept": {
          "coding": [
            { "system": "urn:oid:2.16.840.1.113883.2.4.4.10", "code": "18996",
              "display": "PARACETAMOL ZETPIL 1000MG" },
            { "system": "http://www.whocc.no/atc", "code": "N02BE01" } ],
          "text": "PARACETAMOL ZETPIL 1000MG" },
        "dosageInstruction": [ {
          "extension": [ {
            "url": "http://spec.digitalis.nl/fhir/StructureDefinition/ext-Dosage.CodedDirections",
            "valueString": "3-4D1S; gedurende max. 1 maand" } ],
          "text": "3 tot 4 maal per dag 1 stuk" } ],
        "dispenseRequest": {
          "quantity": { "value": 15, "unit": "ST", "system": "urn:oid:2.16.840.1.113883.2.4.4.1.900.2",
                        "code": "ST" },
          "expectedSupplyDuration": { "value": 30, "unit": "dag",
                                      "system": "http://unitsofmeasure.org", "code": "d" } } } },
    { "fullUrl": "urn:uuid:1c2d3e4f-5678-4a9b-8c0d-1e2f3a4b5c6d",
      "resource": {
        "resourceType": "Communication",
        "status": "completed",
        "category": [ { "coding": [ {
          "system": "http://terminology.hl7.org/CodeSystem/communication-category",
          "code": "instruction" } ] } ],
        "payload": [ { "contentString": "Neem in bij voorkeur met wat water." } ] } },
    { "fullUrl": "urn:uuid:7b8c9d01-2e3f-4a5b-9c6d-7e8f9a0b1c2d",
      "resource": {
        "resourceType": "Communication",
        "status": "completed",
        "category": [ { "coding": [ {
          "system": "http://terminology.hl7.org/CodeSystem/communication-category",
          "code": "instruction" } ] } ],
        "payload": [ { "contentAttachment": {
          "contentType": "text/uri-list",
          "url": "https://www.thuisarts.nl/paracetamol" } } ] } }
  ]
}
```

## Surveillance

Medication surveillance on its own: no interface, no session, no browser round trip.

```
Base            /fhir/surveillance
Interaction     one request, one answer
Answers         a Bundle of DetectedIssue, one entry per signal
```

| Operation | |
| --- | --- |
| [`POST /fhir/surveillance/$check-medication-request`](#post-fhirsurveillancecheck-medication-request) | Weigh proposed prescriptions against a patient's context |
| [`POST /fhir/surveillance/$check-medication-statement`](#post-fhirsurveillancecheck-medication-statement) | Weigh a patient's current medication against itself and their context |
| `GET /fhir/surveillance/metadata` | The CapabilityStatement of this base. Unauthenticated |

**Behind it, in one call:** both halves of Dutch medication surveillance, merged into one report —
the G-Standaard's **medisch-farmaceutische beslisregels** (interactions, contra-indications,
nierfunctie) and the **classic checks** (allergy, age, duplicate medication, dose control). Nothing
is prescribed, stored or dispensed here.

> **Request and response both have a published profile**, so you can validate rather than read
> prose. The response profile is the young half of this contract: the severity mapping and the way
> a rule's text arrives are the likeliest things to move. Two gaps:
> [What the answer does not cover yet](#what-the-answer-does-not-cover-yet).

| | Prescriptor | Surveillance |
| --- | --- | --- |
| Who decides | the care provider, in Prescriptor's UI | your system, from the signals returned |
| Session | yes, with a browser round trip | none |
| What comes back | prescriptions and patient advice | the signals themselves |
| Prescription | written in Prescriptor | proposed by your system, not created here |

### Two bases, and what follows from it

FHIR reserves the path space under a base for resource type names, so
`/fhir/evs/surveillance/$check-medication-request` would parse as an operation on a resource type
called `surveillance`. Hence a separate base, with four consequences:

- **Its own CapabilityStatement** at `GET /fhir/surveillance/metadata`, unauthenticated, listing
  this base's two operations.
- **The same credentials** and the same 401s — see [Authentication](#authentication).
- **One version number for both contracts.** A surveillance-only release still moves the number
  `GET /fhir/evs/metadata` reports; the changelog names the contract each change belongs to.
- **A path in neither base is a 404** from the container, not a FHIR `OperationOutcome`.

Everything else is shared — content types, error shape, the resource profiles,
[Code systems](#code-systems), [Lab determinations](#lab-determinations) — so a system that already
opens sessions has a new address to post to and no new payload to learn.

## `POST /fhir/surveillance/$check-medication-request`

Given a patient's context and one or more proposed prescriptions: which signals fire?

> **An empty `Bundle` means the check ran and no rule fired.** Nothing else produces one: an
> unreachable upstream, a refusal or a report that did not come back are all a **500**, never a 200
> with no findings, because a prescriber cannot tell the two apart.

### `$check-medication-request` input

A `Parameters` resource conforming to `fhirhub-SurveillanceInput`. Anything outside these
cardinalities is a 400 naming the element.

| Parameter | Card. | Type | Notes |
| --- | --- | --- | --- |
| `patient` | 1..1 | `Patient` | `gender` and `birthDate`, exactly as for a session |
| `xisId` | 1..1 | `string` | Your system id, non-blank |
| `xisVersion` | 1..1 | `string` | Your release version, non-blank |
| `prescription` | 1..* | `MedicationRequest` | The prescriptions to check. **Required**, and repeatable here where a session takes one |
| `medicationStatement` | 0..* | `MedicationStatement` | The patient's current medication, in PRK or HPK |
| `allergyIntolerance` | 0..* | `AllergyIntolerance` | `code.coding` in SSK, SNK or OGGrp |
| `condition` | 0..* | `Condition` | `code.coding` in CICode or ICPC |
| `observation` | 0..* | `Observation` | A LOINC-coded lab determination. Several results for one determination are allowed; only the latest is used — see [Lab determinations](#lab-determinations) |

- **Every resource is one you already build**: the same profiles as the session operations, down to
  the 400 on an unresolvable drug code. `prescription` binds the same `fhirhub-PrescriptionInput` as
  `$createrx-session`, so a prescription checked here can go straight to a session.
- **At least one `prescription` is required.** A request with none — including one carrying only
  `medicationStatement` — has nothing under test; that question is
  [`$check-medication-statement`](#post-fhirsurveillancecheck-medication-statement). It is
  repeatable because a proposed regimen is weighed as a whole: two new drugs can interact.
- **`medicationStatement` is what the prescriptions are weighed against.** An incomplete list is
  the one error nothing downstream can detect: the answer is about the list you sent, not the
  patient.
- **No `endSessionUrl` and no `reason`** — either is a 400.

#### What is read beyond the code

The code identifies the drug; these decide which rules can look at it. All optional in the profile,
none optional in effect.

| Element | On | What it does |
| --- | --- | --- |
| `id` | every resource | Comes back on `DetectedIssue.implicated` for medication and in `evidence` for the rest, so a signal can be shown against the row it is about rather than matched on codes. Send one |
| `code.coding.display`, else `code.text` | `allergyIntolerance`, `condition` | Your own wording, written into the **title and the body** of the signal — "In het dossier is een allergie (PENICILLINES) geregistreerd" — so without it the prescriber reads a sentence with a gap in it. The code is used where you send neither |
| `dosageInstruction` — the `CodedDirections` extension, or `text` | `prescription` | The NHG Tabel 25 instruction. Dose control cannot check a dose without it, and says so in a signal |
| `dispenseRequest.quantity` | `prescription` | The amount to be dispensed, read by dose control alongside the instruction |
| `reasonCode`, with an ICPC-1 NL coding | `prescription` | The indication. Dose bands are keyed on the reason as well as the product; without one the check falls back to the "alle zorg" band |
| `dispenseRequest.validityPeriod`, else `authoredOn` | `prescription` | When the prescription starts and ends |
| `effectivePeriod`, else `effectiveDateTime` | `medicationStatement` | When the use started and, if it has, ended |

- **A missing start date reads as today.** Both engines skip a drug starting in the future. An
  `effectivePeriod` that has **ended** says the patient stopped, and that entry is not weighed.
- **Send a weight wherever the dose depends on it, and a height with it.** A weight-dependent dose
  band with no weight is not a pass but a red signal: *"Geen doseringscontrole: onbekend actueel
  gewicht"*. Ordinary `observation` parameters, LOINC `29463-7` (`kg`) and `8302-2` (`cm`);
  body surface is derived from both, so the height matters for anything dosed per m².

```jsonc
POST /fhir/surveillance/$check-medication-request
Content-Type: application/fhir+json
Authorization: Basic ...

{
  "resourceType": "Parameters",
  "parameter": [
    { "name": "patient", "resource": {
        "resourceType": "Patient", "gender": "female", "birthDate": "1980-01-01" } },
    { "name": "xisId",      "valueString": "xis-001" },
    { "name": "xisVersion", "valueString": "1.0" },

    { "name": "prescription", "resource": {
        "resourceType": "MedicationRequest",
        "status": "active",
        "intent": "order",
        "subject": { "extension": [ {
          "url": "http://hl7.org/fhir/StructureDefinition/data-absent-reason",
          "valueCode": "unknown" } ] },
        "medicationCodeableConcept": { "coding": [ {
          "system": "urn:oid:2.16.840.1.113883.2.4.4.10", "code": "18996" } ] },
        "dosageInstruction": [ { "extension": [ {
          "url": "http://spec.digitalis.nl/fhir/StructureDefinition/ext-Dosage.CodedDirections",
          "valueString": "3-4D1S; gedurende max. 1 maand" } ] } ] } },

    { "name": "medicationStatement", "resource": {
        "resourceType": "MedicationStatement",
        "status": "active",
        "subject": { "extension": [ {
          "url": "http://hl7.org/fhir/StructureDefinition/data-absent-reason",
          "valueCode": "unknown" } ] },
        "medicationCodeableConcept": { "coding": [ {
          "system": "urn:oid:2.16.840.1.113883.2.4.4.10", "code": "18996" } ] } } }
  ]
}
```

Allergies, contra-indications and lab results are omitted for length; they are identical to the
session payloads. A complete request:
[the reference case](Parameters-ExampleSurveillanceReferenceCase.html) — metformine proposed for a
pregnant patient with an eGFR of 35 who already takes ibuprofen and omeprazol.

### `$check-medication-request` output

A `Bundle` with `type: collection`, one `DetectedIssue` per signal, in report order.

| Element | | |
| --- | --- | --- |
| `Bundle.identifier` | 0..1 | The report id (`CRID`). **Log it**: Digitalis support asks for it when a prescriber queries a signal, and it is the only handle tying an answer to the run that produced it |
| `Bundle.timestamp` | 0..1 | When the report was produced upstream |
| `DetectedIssue.severity` | 0..1 | `high`, `moderate` or `low`, from the rule's own red, orange and green. Absent where the rule stated no level |
| `DetectedIssue.code.text` | 1..1 | The title to show: the message's own where it has one, the rule's otherwise. No coding — see below |
| `DetectedIssue.detail` | 0..1 | The rule's full text, as plain text with the paragraphs and the numbered steps on their own lines |
| `DetectedIssue.identifier` | 0..* | The rule's own id — `MFB-0000000068-v000006` for a beslisregel, `hub-doublemedication-prkA-1090` and its siblings for the classic checks. Stable across reports; route and de-duplicate on this |
| `DetectedIssue.evidence.code` | 0..* | What the rule read: a drug as its PRK, GPK, HPK and ATC together, a lab result as its LOINC code with `text` carrying the determination and the value, a contra-indication as its CICode |
| `DetectedIssue.implicated` | 0..* | The medication the risk is in, as a logical reference: `identifier.value` is the `id` you sent on that resource, `type` says whether it was a `MedicationRequest` you proposed or a `MedicationStatement` the patient already takes |
| `DetectedIssue.identifiedDateTime` | 0..1 | The report's timestamp, repeated per finding |
| `DetectedIssue.status` | 1..1 | Always `final` |

```jsonc
HTTP/1.1 200 OK

{
  "resourceType": "Bundle",
  "identifier": { "system": "http://spec.digitalis.nl/fhir/sid/crs-report",
                  "value": "83327A6E-FAED-4448-A20E-EFEA660C7627" },
  "type": "collection",
  "timestamp": "2026-09-07T11:11:16+02:00",
  "entry": [ {
    "fullUrl": "urn:uuid:6f1b...",
    "resource": {
      "resourceType": "DetectedIssue",
      "identifier": [ { "system": "http://spec.digitalis.nl/fhir/sid/crs-rule",
                        "value": "MFB-0000000068-v000006" } ],
      "status": "final",
      "code": { "text": "Nierfunctie: metformine" },
      "severity": "high",
      "identifiedDateTime": "2026-09-07T11:11:16+02:00",
      "implicated": [ { "type": "MedicationRequest",
                        "identifier": { "value": "rx-1" },
                        "display": "METFORMINE TABLET   500MG" } ],
      "detail": "Risico op lactaatacidose is verhoogd. Patiënt heeft creatinineklaring 30-60 ml/min...
1. aanvankelijk 500 mg metformine 2x per dag
2. vervolgens dosering geleidelijk verhogen tot standaardonderhoudsdosering",
      "evidence": [
        { "code": [ { "coding": [
            { "system": "urn:oid:2.16.840.1.113883.2.4.4.10", "code": "1090" },
            { "system": "urn:oid:2.16.840.1.113883.2.4.4.1",  "code": "3816" },
            { "system": "http://www.whocc.no/atc",            "code": "A10BA02" } ],
            "text": "METFORMINE TABLET   500MG" } ] },
        { "code": [ { "coding": [ { "system": "http://loinc.org", "code": "62238-1" } ],
                      "text": "kreatinineklaring: 35" } ] } ]
    }
  } ]
}
```

- **A green signal is a finding, not a near-miss.** `low` means a rule fired and concluded no
  action is needed — "Dit is GEEN contra-indicatie". Hiding it hides the answer.
- **For an allergy signal, `implicated` carries the verdict**: `low` with **no `implicated`** means
  checked and nothing matched, `high` **naming a drug** means matched. The text reads
  "…geregistreerd voor het onderstaande middel" either way, accurate only in the second — so branch
  on `severity` and `implicated`, not on the sentence.
- **`code` carries no coding**, only text: FHIR's `DetectedIssue` categories are a classification
  the upstream does not make. Route on `identifier`, show `code.text`.
- **`meta.profile` is not asserted.** Validate against `fhirhub-SurveillanceBundle` explicitly, and
  do not route on `meta.profile`.

### What the answer does not cover yet

Two gaps, each a rule that stays silent rather than an error you would notice — so decide what to
tell a prescriber.

- **Rules comparing prescribed against defined daily dose cannot fire**, because computing a PDD
  means decoding the Tabel 25 instruction, which passes through undecoded (see
  [Extensions](#extensions)). The dose *bands* are checked — the larger half of dose control.
- **A partial answer is indistinguishable from a complete one.** If the rules engine answers and
  the classic checks fail, the report carries what ran and the upstream does not say which half is
  missing. What never happens is nothing at all being presented as an all-clear.

**Credentials on this base are carried upstream but not adjudicated there**, so unlike a session, a
wrong practice id is not a 401. Call this endpoint from your server rather than a client you do not
control, and talk to Digitalis about the deployment restrictions in front of it.

### `$check-medication-request` errors

| Condition | Status |
| --- | --- |
| Missing or malformed Basic credentials | 401 |
| Body fails `fhirhub-SurveillanceInput`, or a G-Standaard code cannot be resolved | 400 |
| The check could not be run — upstream unreachable, refused, or answered without a report | 500 |

Every 500 says in `diagnostics` that no conclusion may be drawn about the patient's medication. No
status means "the check partly ran".

## `POST /fhir/surveillance/$check-medication-statement`

The same question about a different subject: a **dossier weighed against itself**. Every
`medicationStatement` is examined — for allergy, age and dose, and against every other entry for
duplicate medication — and the answer has the same shape as
[`$check-medication-request`](#post-fhirsurveillancecheck-medication-request).

> **Not `$check-medication-request` with the prescription left out.** A `prescription` sent here is
> a **400** rather than an element quietly dropped: a request whose proposed drug was ignored would
> be answered for the existing medication alone — a real answer to a question you did not ask.

**What it is for:** a medication review, or anything else asking what is wrong with what a patient
already takes. Only this operation surfaces signals where nothing new is prescribed:

- an allergy or contra-indication **recorded after** the medication was started
- a dose that no longer fits a **nierfunctie that has since dropped**, or a changed weight
- a **duplicate** between two drugs of which neither is new
- an age band the patient has since **crossed** — fine at 74, a signal at 75

### `$check-medication-statement` input

A `Parameters` resource conforming to
[fhirhub-SurveillanceStatementInput](StructureDefinition-fhirhub-SurveillanceStatementInput.html).
Identical to the other operation's input except in its medication parameters.

| Parameter | Card. | Type | Notes |
| --- | --- | --- | --- |
| `patient` | 1..1 | `Patient` | `gender` and `birthDate`, exactly as for a session |
| `xisId` | 1..1 | `string` | Your system id, non-blank |
| `xisVersion` | 1..1 | `string` | Your release version, non-blank |
| `medicationStatement` | 1..* | `MedicationStatement` | **Required**, and every entry is under test |
| `allergyIntolerance` | 0..* | `AllergyIntolerance` | `code.coding` in SSK, SNK or OGGrp |
| `condition` | 0..* | `Condition` | `code.coding` in CICode or ICPC |
| `observation` | 0..* | `Observation` | A LOINC-coded lab determination. Several results for one determination are allowed; only the latest is used — see [Lab determinations](#lab-determinations) |
| ~~`prescription`~~ | — | — | **Not accepted.** Use [`$check-medication-request`](#post-fhirsurveillancecheck-medication-request) |

- **`medicationStatement` means something different here**: the *background* in the other
  operation, the *subject* in this one — every entry is itself checked, and compared with the
  others.
- **Send the whole list.** An entry you leave out is a drug neither checked nor reported as
  unchecked, and nothing downstream can detect the omission.
- **Everything else is unchanged**, so a `$check-medication-request` body becomes this one by
  dropping a parameter.

```jsonc
POST /fhir/surveillance/$check-medication-statement
Content-Type: application/fhir+json
Authorization: Basic ...

{
  "resourceType": "Parameters",
  "parameter": [
    { "name": "patient", "resource": {
        "resourceType": "Patient", "gender": "female", "birthDate": "1948-03-11" } },
    { "name": "xisId", "valueString": "acme-his" },
    { "name": "xisVersion", "valueString": "3.2.1" },
    { "name": "medicationStatement", "resource": {
        "resourceType": "MedicationStatement",
        "id": "ms-metformine",
        "status": "active",
        "subject": { "extension": [ { "url": "http://hl7.org/fhir/StructureDefinition/data-absent-reason", "valueCode": "unknown" } ] },
        "medicationCodeableConcept": { "coding": [ {
            "system": "http://spec.digitalis.nl/fhir/CodeSystem/gstandaard-prk",
            "code": "49034", "display": "METFORMINE HCL TABLET 500MG" } ] },
        "effectivePeriod": { "start": "2024-11-02" } } },
    { "name": "medicationStatement", "resource": {
        "resourceType": "MedicationStatement",
        "id": "ms-ibuprofen",
        "status": "active",
        "subject": { "extension": [ { "url": "http://hl7.org/fhir/StructureDefinition/data-absent-reason", "valueCode": "unknown" } ] },
        "medicationCodeableConcept": { "coding": [ {
            "system": "http://spec.digitalis.nl/fhir/CodeSystem/gstandaard-prk",
            "code": "13692", "display": "IBUPROFEN TABLET 400MG" } ] },
        "effectivePeriod": { "start": "2026-08-19" } } },
    { "name": "observation", "resource": {
        "resourceType": "Observation",
        "id": "obs-egfr",
        "status": "final",
        "code": { "coding": [ { "system": "http://loinc.org", "code": "62238-1" } ] },
        "effectiveDateTime": "2026-09-01T09:14:00",
        "valueQuantity": { "value": 35, "system": "http://unitsofmeasure.org", "code": "mL/min/{1.73_m2}" } } }
  ]
}
```

A complete example is published as
[`$check-medication-statement` example](Parameters-ExampleStatementCheck.html).

### `$check-medication-statement` output

**The same `Bundle` of `DetectedIssue`** — see
[`$check-medication-request` output](#check-medication-request-output), including the guarantee on
an empty one: the check ran and nothing fired, or you get a 500.

**One difference, in `implicated`**: this operation names your `MedicationStatement` where the
other would have named a `MedicationRequest`:

```jsonc
"implicated": [ {
  "type": "MedicationStatement",
  "identifier": { "value": "ms-metformine" },
  "display": "METFORMINE HCL TABLET 500MG"
} ]
```

Both carry your own resource `id`, so set one.

**The two gaps are the same two** — see
[What the answer does not cover yet](#what-the-answer-does-not-cover-yet).

### `$check-medication-statement` errors

| Condition | Status |
| --- | --- |
| Missing or malformed Basic credentials | 401 |
| Body fails `fhirhub-SurveillanceStatementInput` — including a `prescription` parameter, which this operation does not define — or a G-Standaard code cannot be resolved | 400 |
| The check could not be run — upstream unreachable, refused, or answered without a report | 500 |

Same shapes, same rule: no 500 permits a conclusion about the patient's medication.

## Lab determinations

Lab values are coded in **LOINC** and nothing else, from a closed list. The G-Standaard publishes
which LOINC code counts as which beslisregel parameter and the rules engine tests the code you
send, so nothing is translated on the way through.

| Determination | `code` (LOINC) | `valueQuantity.code` | Read by |
| --- | --- | --- | --- |
| eGFR volgens CKD-EPI | `62238-1` | `mL/min/{1.73_m2}` | 666 current rules |
| Kalium, serum of plasma | `2823-3` | `mmol/L` | 4 current rules |
| Kalium, bloed | `6298-4` | `mmol/L` | idem |
| INR, trombocytenarm plasma | `6301-6` | `{INR}` or `1` | 3 current rules |
| INR, bloed | `34714-6` | `{INR}` or `1` | idem |
| Sirolimus Cmin | `29247-4` | `ug/L` | 1 current rule |
| Natrium, serum of plasma | `2951-2` | `mmol/L` | no current rule |
| Lithiumspiegel | `14334-7` | `mmol/L` | no current rule |
| Gewicht | `29463-7` | `kg` | dose checking |
| Lengte | `8302-2` | `cm` | dose checking |

- **A determination outside the list is a 400, not a silent no-op**, because a prescriber who
  supplied a lab result and saw no warning would read that as an all-clear.
- **One eGFR code**: Dutch laboratories report CKD-EPI, so send `62238-1`. `77147-7` (MDRD) and
  `50210-4` (cystatin C) are not accepted — raise it with Digitalis rather than re-labelling a
  value, because the formulas do not give the same number.
- **The unit is checked against the code** (`system: "http://unitsofmeasure.org"` plus the `code`
  above), because the value is evaluated in the unit the rule was written in: kalium in mg/dL is a
  different answer, not a rounded one. An eGFR must arrive as `mL/min/{1.73_m2}`, not `mL/min`.
  Nothing is converted: the unit in the table is the only one accepted, and anything else is a 400.
- **A height is `cm`; `m` is a 400.** Metres used to be accepted and converted
  exactly. They are not any more, because R4 validates every `Observation` coded `8302-2` against
  its own `bodyheight` profile — whatever profile the resource claims — and that binds the unit to
  `cm` or `[in_i]`. A unit only this interface would take is a unit your own validator rejects, so
  it is gone rather than special. Inches are not accepted either: the G-Standaard is metric.
- **Weight and height are vital signs to R4 itself.** The same rule applies `bodyweight` to
  `29463-7`, and both core profiles also require the `vital-signs` category and a `subject`. This
  interface requires neither and reads neither, so a payload without them passes here and fails a
  core validation elsewhere. Send them and you satisfy both — the
  [example](Observation-obs-lengte-cm.html) shows the shape.
- **Send no `display` unless it is LOINC's own term.** Nothing here checks it: LOINC is not in this
  service's validator, so any display passes while the `code` is still checked against the value
  set above. It is forwarded verbatim as the caption the prescriber sees, in preference to this
  interface's own (`eGFR volgens CKD-EPI`, `Gewicht`) — so a wrong one is displayed, not rejected.
  A LOINC-loaded validator does reject anything but LOINC's Dutch term.
- **Dates matter as much as values.** Rules test them — *is de ClCr ouder dan 13 maanden*, *is de
  INR max. 24 uur oud* — so `effectiveDateTime` is required, and it is when the sample was taken,
  not when the report was released. A value older than the rule's window counts as absent.
- **Several results for one determination are allowed; only the latest is used.** Latest by
  `effectiveDateTime`, per LOINC code. A date-only value counts as midnight, so among results of
  one day the first sent wins.
- One determination per `observation` parameter, repeated as needed. `component` is not read —
  resolve a multi-component result to one number first. `interpretation`, `referenceRange`, `method`
  and `note` are ignored: this is an input to a decision, not a lab report.

## Profiles

Every payload has a `StructureDefinition` to validate against, published as an implementation guide
with the canonical `http://spec.digitalis.nl/fhir`:

| Payload | Profile |
| --- | --- |
| `$formulary-session` request | `fhirhub-FormularySessionInput` |
| `$createrx-session` request | `fhirhub-CreateRxSessionInput` |
| Session response | `fhirhub-SessionOutput` |
| `$session-result` response | `fhirhub-ResultBundle` |
| `$check-medication-request` request | `fhirhub-SurveillanceInput` |
| `$check-medication-statement` request | `fhirhub-SurveillanceStatementInput` |
| Surveillance response (both operations) | `fhirhub-SurveillanceBundle`, whose entries are `fhirhub-SurveillanceFinding` |

- The input profiles slice `Parameters.parameter` by name, each slice pointing at a resource
  profile, so one pass validates the resources inside. **The slicing is closed**: an undefined name
  is an error, because `medicationstatement` would otherwise open a session against a silently
  thinner medication list.
- **Enforced before anything else** — before the G-Standaard lookup and before the call upstream. A
  non-conformant payload is a 400 with one `OperationOutcome` issue per error naming its element,
  so you get every problem in one response.
- Only errors reject; warnings are normal, because the G-Standaard code systems cannot be expanded.
- The payloads here are also IG instances, validated on every build, so this document cannot drift
  from the profiles.

### Elements FHIR requires that fhir-hub does not read

Base R4 makes several elements mandatory that this interface never reads, and a profile can only
constrain — so a **conformant payload must carry them**:

| Resource | Element | Note |
| --- | --- | --- |
| `AllergyIntolerance` | `patient`, `clinicalStatus` | `clinicalStatus` is required by invariant `ait-1` |
| `Condition` | `subject` | |
| `MedicationStatement` | `status`, `subject` | |
| `Observation` | `status` | |
| `MedicationRequest` (`prescription`) | `status`, `intent`, `subject` | `$createrx-session` and the surveillance operations |

The patient travels as a sibling parameter, so `patient` and `subject` have nothing to reference:
send a `data-absent-reason` of `unknown`, as the examples do.

## Code systems

Emitted on output and accepted on input:

| Concept | `system` |
| --- | --- |
| PRK (voorschrijfproducten) | `urn:oid:2.16.840.1.113883.2.4.4.10` |
| HPK (handelsproduct) | `urn:oid:2.16.840.1.113883.2.4.4.7` |
| GPK (generiek product) | `urn:oid:2.16.840.1.113883.2.4.4.1` |
| G-Standaard basiseenheid | `urn:oid:2.16.840.1.113883.2.4.4.1.900.2` |
| ICPC-1 NL | `urn:oid:2.16.840.1.113883.2.4.4.31.1` |
| LOINC (lab determinations, see [Lab determinations](#lab-determinations)) | `http://loinc.org` |
| ATC | `http://www.whocc.no/atc` |
| UCUM | `http://unitsofmeasure.org` |

The four G-Standaard subsystems are identified by national OIDs as well:

| Concept | `system` | Used by |
| --- | --- | --- |
| SSK (stofnaamcode incl. toedieningsweg) | `urn:oid:2.16.840.1.113883.2.4.4.1.725` | `allergyIntolerance` |
| SNK (stofnaamcode / generieke namen) | `urn:oid:2.16.840.1.113883.2.4.4.1.750` | `allergyIntolerance` |
| OGGrp (ongewenste medicatiegroep, thesaurus 122) | `urn:oid:2.16.840.1.113883.2.4.4.1.902.122` | `allergyIntolerance` |
| CICode (contra-indicatie, thesaurus 40) | `urn:oid:2.16.840.1.113883.2.4.4.1.902.40` | `condition` |

These are the OIDs Nictiz binds to in `nl-core-AllergyIntolerance` and
`nl-core-MedicationContraIndication`, so a coding you send here also works in any Dutch system
following those profiles.

**Copy these strings exactly.** One accepted `system` per row, no lenient form: a bare token —
`PRK`, `HPK`, `SSK`, `SNK`, `OGGrp`, `CICode`, `ICPC` — is not a FHIR `system` and is a 400 on that
element. A rejection lists the accepted URIs:

```
AllergyIntolerance.code has no coding in a system this interface routes; expected one of
[urn:oid:2.16.840.1.113883.2.4.4.1.725, urn:oid:2.16.840.1.113883.2.4.4.1.750,
urn:oid:2.16.840.1.113883.2.4.4.1.902.122]
```

The G-Standaard tables are licensed and not distributed with the profiles, so the `system` is
checked and the code is not: a coding from a system above passes with a warning, one from any other
system is an error. The only code shape checked is ICPC-1 NL, by an invariant.

## Extensions

Two, both Digitalis-defined because no national artifact covers them. Both canonicals dereference:
a browser gets the definition page, `Accept: application/fhir+json` the `StructureDefinition`.

**`ext-Dosage.CodedDirections`** —
`http://spec.digitalis.nl/fhir/StructureDefinition/ext-Dosage.CodedDirections`, `valueString`. The
NHG Tabel 25 coded instruction, e.g. `"3-4D1S; gedurende max. 1 maand"`.

The dosing instruction **in both directions**: store it and hand it back unchanged unless you mean
to change the dose. `Dosage.text` beside it is display only. If you do not parse Tabel 25, treat
the string as opaque — a pharmacy system downstream reads it natively. No `timing` or `doseAndRate`
is produced, and any you send is **ignored**.

**`ext-MedicationRequest.OpiumActClassification`** —
`http://spec.digitalis.nl/fhir/StructureDefinition/ext-MedicationRequest.OpiumActClassification`,
`valueCodeableConcept`. A G-Standaard bijzonder kenmerk, present only when the product falls under
the Opiumwet.

Only code `2` ("Product valt onder Opiumwet in volle omvang") is emitted today, but switch on the
code with a default branch rather than treating the extension as a boolean: `65` and `107` carry
different handling rules for a pharmacist and may appear later.

## Errors

Every error is an `OperationOutcome`, the 401s included, so one error path handles all of them.

| Condition | Status |
| --- | --- |
| Missing or malformed Basic credentials | 401 |
| Organization id or key rejected by Prescriptor (`Invalid organization ID or key`) | 401 |
| Unknown or already-consumed session id (`No data found for the session ID`) | 401 |
| Invalid request | 400 |
| Prescriptor unreachable (`Could not reach Prescriptor`) or unparseable | 500 |
| Medication surveillance unreachable, refused, or answering without a report | 500 |

A 400 comes from one of three places, quoted as returned so you can recognise them.

**The profile**, where most come from, in the FHIR validator's own wording:

- `Slice 'Parameters.parameter:endSessionUrl': a matching slice is required, but not found` — a
  missing parameter
- `This element does not match any known slice defined in the profile … and slicing is CLOSED` — a
  misspelled parameter name
- `Parameters.parameter:endSessionUrl: max allowed = 1, but found 2` — a repeated single-valued
  parameter
- `Parameters.parameter:prescription: max allowed = 0, but found 1` — a prescription sent to
  `$formulary-session`
- `Patient.birthDate: minimum required = 1, but only found 0`
- `MedicationRequest.subject: minimum required = 1, but only found 0` — the `data-absent-reason`
  idiom is missing; see [Elements FHIR requires that fhir-hub does not read](#elements-fhir-requires-that-fhir-hub-does-not-read)
- `The value provided ('other') was not found in the value set 'Administrative gender accepted by
  Prescriptor' …, and a code is required from this value set`
- `None of the codings provided are in the value set 'Contra-indication (CICode or ICPC-1 NL)' …,
  and a coding from this value set is required) (codes = …)`
- `Constraint failed: fhirhub-icpc-shape: 'An ICPC-1 NL code is a letter and two digits, optionally
  followed by a dot and two more (A01, U71.01).'`
- `Constraint failed: fhirhub-http-url: 'Only http and https are accepted: …'`

**The operation binding**, before the body is validated, when a parameter carries the wrong type:

- `HAPI-0362: Request has parameter reason of type Coding but method expects type CodeableConcept`
- `HAPI-0362: Request has parameter endSessionUrl of type StringType but method expects type UrlType`

**This interface's own rules**, for what a profile cannot express:

- `G-Standaard has no product for PRK 404040, so it cannot take part in medication surveillance`
- `LOINC code '718-7' is not a determination medication surveillance reads, so sending it would suggest it had been weighed. Accepted: [62238-1, 2823-3, …]`
- `Observation.valueQuantity for 2823-3 (Kalium (serum of plasma)) must be in [mmol/L] as a UCUM code, not 'mg/dL': the upstream carries no unit, so the value is evaluated as mmol/L`
- `PRK code '18996a' is not numeric`
- `Parameters.parameter[5] has no name, so nothing here could tell what it is`
- `The 'session' parameter is required` — on `$session-result`

None of this wording is part of the contract: branch on the status, and show `diagnostics` to
whoever has to act on it.

```json
{
  "resourceType": "OperationOutcome",
  "issue": [ {
    "severity": "error",
    "code": "processing",
    "details": { "coding": [ {
      "system": "http://hl7.org/fhir/java-core-messageId",
      "code": "Validation_VAL_Profile_Minimum_SLICE" } ] },
    "diagnostics": "Slice 'Parameters.parameter:endSessionUrl': a matching slice is required, but not found (from http://spec.digitalis.nl/fhir/StructureDefinition/fhirhub-FormularySessionInput). Note that other slices are allowed in addition to this required slice",
    "location": [ "Parameters", "Line[1] Col[894]" ],
    "expression": [ "Parameters" ]
  } ]
}
```

Validator issues also carry `operationoutcome-issue-line`, `-issue-col` and `-message-id`
extensions, trimmed here. `expression` and `location` name the element that failed.

## Behaviour to design around

- **`$session-result` is single-use.** Requesting it ends the session in Prescriptor; a second call
  returns 401.
- **Medication surveillance fails closed.** One unresolvable drug code fails the whole request with
  a 400 naming it. Refresh the code rather than dropping the drug.
- **No patient identity comes back.** Correlate on the session id you requested the Bundle with.
- **Structured dosing is not round-tripped.** Edit the `CodedDirections` extension, not `timing`.
- **A GPK-coded prescription cannot be handed back** to `$createrx-session`, which needs a PRK or
  an HPK. See [`prescription`](#prescription).
- **Nothing in a request is ignored.** An unrecognised parameter name, a repeated single-valued
  parameter and a wrong value type are all 400s, so a typo cannot open a session on partial data.
- **Lab units are dropped.** Send values in the determination's own unit; a `Quantity.unit` is
  ignored.

## Moving from the JSON API

The operations, the semantics and the HTTP status codes are the same. Three differences go beyond
syntax:

- **Credentials move to the HTTP layer.** The organization id and key leave the request body and
  become an HTTP Basic header, unchanged in value.
- **Drug codes in `medicationStatement` are resolved before the session opens**, and one that
  cannot be resolved fails the request with a 400. The JSON API adds no such lookup, so a code it
  accepts can be rejected here — check your codes against a current G-Standaard before switching
  over.
- **The code system becomes the `system` on the coding.** Where the JSON API took a code alongside
  a `PRK`, `SSK` or `ICPC` token, the token is replaced by the URI from
  [Code systems](#code-systems). The tokens are not accepted as a `system`.

Read the operation and the profile it names rather than porting your payloads field by field, which
carries across assumptions the JSON API allowed and this one does not.

## Current limitations

Things you may expect to be able to do, and cannot yet.

- **No `meta.profile` is asserted** on any resource, so validate against the profile URLs above
  explicitly rather than routing on it. The IG's examples carry one because the publishing tool
  adds it; a live payload does not.
- **nl-core is not derived from**, and three of the five resources are blocked on Nictiz rather
  than on effort: `nl-core-MedicationContraIndication` profiles `Flag` rather than `Condition`,
  `nl-core-LaboratoryTestResult` requires an `Observation.category` this interface neither sends nor
  reads, and `nl-core-MedicationUse2` is not published in the nl-core package. Ask Digitalis before
  depending on nl-core conformance.
- **The artifacts are `draft`**, and while they are, the change policy allows a breaking change at
  a minor version — see *Versioning and change policy*.
- **The G-Standaard code systems are not distributed**, so no validator can check a G-Standaard
  *code*, only the `system` it came from. A wrong code inside a routed system reaches Prescriptor
  and comes back as a 400 from the medication lookup rather than as a validation error.
- **The medication-surveillance response is the youngest part of the contract.** The severity
  mapping and the way a rule's text arrives are the likeliest things to move, and two classes of
  rule cannot fire yet — see
  [What the answer does not cover yet](#what-the-answer-does-not-cover-yet).

Questions, or a case this document does not cover: contact Digitalis.
