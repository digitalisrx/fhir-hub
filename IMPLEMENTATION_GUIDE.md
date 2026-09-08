# fhir-hub Implementation Guide

One FHIR R4 interface in front of two Digitalis applications, on two FHIR bases. Which one you
are integrating with decides most of what you need from this document.

| | | |
| --- | --- | --- |
| **[Prescriptor](#prescriptor)** | `/fhir/evs` | Prescribing, in Prescriptor's own user interface. Your system opens a session, hands the browser over and collects the result |
| **[Surveillance](#surveillance)** | `/fhir/surveillance` | Medication surveillance on its own. No interface and no session: your system asks, and reads the signals in the response |

Read the section for your application first. [Conventions](#conventions),
[Authentication](#authentication), [Lab determinations](#lab-determinations),
[Profiles](#profiles), [Code systems](#code-systems), [Extensions](#extensions) and
[Errors](#errors) are shared by both: one set of credentials, one set of payload profiles, one
release number.

> **This guide is work in progress.** Nothing is published at the canonical yet, no integrator is
> in production, and **every part of this specification may change without notice** — the payloads,
> the profiles, the operation names and the base paths included. Build against it, and agree with
> Digitalis how you want to be told about a change before you go live. The change policy takes
> effect with the first published release.

- **FHIR R4 (4.0.1).** Every payload is a `Parameters` or a `Bundle`; there is no resource REST API
  and no search.
- **Stateless.** Persist the session id and the result Bundle yourself; nothing can be re-fetched.
- **The prose and the `StructureDefinition`s are one specification**, published at
  `http://spec.digitalis.nl/fhir`. Every request is validated against its profile before it is
  processed — see [Profiles](#profiles).

## Conventions

| | |
| --- | --- |
| Base paths | `/fhir/evs` for [Prescriptor](#prescriptor), `/fhir/surveillance` for [Surveillance](#surveillance) — two FHIR bases, each with its own `metadata`. They are siblings rather than nested because FHIR reserves the path space under a base for resource type names |
| Request content type | `application/fhir+json` (`application/fhir+xml` also accepted) |
| Response content type | `application/fhir+json`, pretty-printed |
| Format override | `?_format=json`, `?_format=xml`, `?_format=html` |
| Browser rendering | A request whose highest-ranked `Accept` is `text/html` gets a syntax-highlighted HTML page instead of raw JSON |
| Errors | Always `OperationOutcome` — no endpoint returns a non-FHIR body |

Set `Accept: application/fhir+json` explicitly in machine clients; a browser-style `Accept` gets
HTML.

**An undefined parameter name is a 400, and so is a repeat of a single-valued one.** The request
profiles close the list of names and carry each cardinality, so `medicationstatement` for
`medicationStatement` is rejected rather than silently dropped. Both are caught before anything is
sent upstream — see [Profiles](#profiles).

## Authentication

```
Authorization: Basic base64(organization.id ":" organization.key)
```

Invalid credentials are a **401** on the operation you called; there is no token to obtain and
nothing to refresh. `GET /fhir/evs/metadata`, `GET /fhir/evs/OperationDefinition/**` and
`GET /actuator/health/**` are unauthenticated, so you can read what the interface accepts before
your credentials are issued. Anything else without the header is a 401 with
`WWW-Authenticate: Basic`.

## Prescriptor

**Digitalis Prescriptor 3** is the prescribing application: the care provider chooses a treatment,
writes the prescription and reads the signals in Prescriptor's own user interface. Your system
supplies the patient context, hands the browser over and collects what came out.

```
Base            /fhir/evs
Interaction     session-based — open, redirect the browser, poll for the result
```

`evs` is *elektronisch voorschrijfsysteem*; the segment is part of the base and never omitted.

| Operation | |
| --- | --- |
| [`POST /fhir/evs/$formulary-session`](#post-fhirevsformulary-session) | Open a formulary session: a treatment is chosen for a stated reason for encounter |
| [`POST /fhir/evs/$createrx-session`](#post-fhirevscreaterx-session) | Open a prescribing session without a formulary lookup, optionally starting from a prescription you already hold |
| [`GET /fhir/evs/$session-result`](#get-fhirevssession-result) | Collect the prescriptions and patient advice. Single-use: it ends the session |
| [`GET /fhir/evs/metadata`](#get-fhirevsmetadata) | The CapabilityStatement. Unauthenticated |

**Medication surveillance happens inside the session**, on the medication list you send, which is
why that list matters as much as the prescription. The signals are shown to the care provider in
Prescriptor's interface and are not returned to your system; for the signals themselves, see
[Surveillance](#surveillance).

**No resource REST API and no search.** `/fhir/evs/Patient`, `/fhir/evs/MedicationRequest` and the
like are not supported, and nothing is stored here to read back.

## The Prescriptor flow

Three calls, plus a browser round trip your system does not mediate. None of it applies to
[Surveillance](#surveillance), which has one request and one answer.

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

1. **Open a session.** Post the patient context and receive a `sessionId` and a `url`. Current
   medication is resolved against the G-Standaard *before* the session opens, because surveillance
   needs PRK and GPK together — which is why an unresolvable drug code fails the whole call.
2. **The care provider works in the Prescriptor UI** at that `url`; fhir-hub is not involved. When
   finished, Prescriptor redirects the browser to your `endSessionUrl`.
3. **Fetch the result** with the `sessionId` and receive a `Bundle` of `MedicationRequest`
   (prescriptions) and `Communication` (patient advice).

**The ordering is not advisory.** A session id is valid only between step 1 and step 3, and step 3
consumes it — a second `$session-result` for the same id is a 401. Persist the id when you receive
it and the Bundle when you fetch it.

## `GET /fhir/evs/metadata`

The FHIR CapabilityStatement, unauthenticated. **Input** — none. **Output** — a
`CapabilityStatement` listing `formulary-session`, `createrx-session` and `session-result`.

```bash
curl -sS 'http://localhost:8080/fhir/evs/metadata?_format=json'
```

**`software.version` is the release of this specification the deployment implements** — not a build
number of the service, and not the FHIR version, which is `fhirVersion`. Read it before you send
anything introduced in a later release: a parameter name the deployment does not know is a 400
rather than an ignored element.

Each operation links a generated `OperationDefinition` —
`/fhir/evs/OperationDefinition/-s-formulary-session` and its two siblings, also unauthenticated —
describing every input parameter with a `type` and a cardinality, so a request can be generated
from it. The `OperationDefinition`s give you the parameter list; the [profiles](#profiles) give you
what has to be true *inside* each parameter.

**This statement covers the Prescriptor base only.** [Surveillance](#surveillance) has its own at
`GET /fhir/surveillance/metadata`; both report the same `software.version`.

## `POST /fhir/evs/$formulary-session`

Opens a **formulary** session: the care provider picks a treatment for a stated reason for
encounter.

### Input

A `Parameters` resource. The cardinalities below are enforced; anything outside them is a 400 that
names the element.

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
| `observation` | 0..* | `Observation` | A LOINC-coded lab determination — see [Lab determinations](#lab-determinations) |
| `prescription` | 0..0 | — | Rejected here with a 400; `$createrx-session` only |

**`patient`** — `gender` must be `male`, `female` or `unknown`; `other` and an absent `gender` are
both a 400, so send `unknown` where your record holds `other`. Send the sex when you know it:
sex-specific surveillance checks cannot fire on `unknown`, and the prescriber is not told they were
skipped. `birthDate` is required. Nothing else in the `Patient` is read, forwarded or stored.

**`reason`** — a `CodeableConcept`; a bare `valueCoding` is rejected. `system` must be the ICPC-1 NL
OID `urn:oid:2.16.840.1.113883.2.4.4.31.1` and the code must match `^[A-Z][0-9]{2}(\.[0-9]{2})?$`
(`A01`, `U71.01`).

**`endSessionUrl`** — `valueUrl` only, and `http` or `https` only. A custom scheme such as an app
deep link is a 400: use an `https` landing page and redirect on from there.

**`xisId` and `xisVersion`** — your product and release, not the practice. Keep the id stable and
move only the version. They trace your calls in this service's logs and are **never forwarded to
Prescriptor**.

**`medicationStatement`** — every code is looked up in the G-Standaard, and one that cannot be
resolved **fails the whole request with a 400** naming it. Nothing is skipped and no session is
opened, and there is no free-text fallback, so refresh your codes against a current G-Standaard
first. Each entry carries its own level: PRK for one drug and HPK for the next is fine, in any
order, because every entry is resolved to a PRK + GPK pair before the session opens.

**`observation`** — a lab determination or body measurement in **LOINC** (`http://loinc.org`), with
`effectiveDateTime` and a `valueQuantity` in the unit listed under
[Lab determinations](#lab-determinations). Only the determinations surveillance reads are accepted.

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

`status` and `subject` are not read and are nonetheless **required**: base R4 makes them mandatory
and a profile can only constrain. Send them as the example does, with a `data-absent-reason` where
you have nothing to point at — see
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

Treat `url` as opaque and redirect to it unchanged: its shape is Prescriptor's and is not part of
this contract, so read the session id from `sessionId` rather than parsing it back out.

## `POST /fhir/evs/$createrx-session`

Opens a **CreateRx** session: prescribing without a formulary lookup, optionally starting from a
prescription you already hold. Identical to `$formulary-session` except:

| | |
| --- | --- |
| `reason` | **0..1** — optional, because CreateRx prescribes without a formulary lookup. Still validated against the ICPC pattern when present |
| `prescription` | **0..1** `MedicationRequest` — a prescription to open for editing |

### `prescription`

Send back the `MedicationRequest` you received from `$session-result`, with your edits. The shapes
are the same with one exception: the product must be coded as **PRK or HPK**, and `$session-result`
may have returned it at GPK level. Resolve that to a PRK or an HPK first, or open the session
without a prescription.

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

Dosing is read from the `CodedDirections` extension, and from `Dosage.text` only as a fallback.
Edits to `timing` or `doseAndRate` are **ignored**: NHG Tabel 25 passes through verbatim in both
directions and no structured dosing is derived. See [Extensions](#extensions).

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

Sending `prescription` to `$formulary-session` is a 400. Output is the same
`Parameters{sessionId, url}` as above.

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

Quote the URL: unquoted, the shell expands `$session-result` and you request `/fhir/-result`.

**One shot.** The first successful call *ends the session*; a second with the same id returns 401,
not the same Bundle. Store the Bundle as soon as you receive it, and make sure a retry after a
timeout cannot fire twice — a lost response cannot be recovered.

**Call it once, when the browser returns to your `endSessionUrl`**; that redirect is the signal
that the session is finished. Do not poll on a timer: a 401 does not distinguish "unknown id" from
"already collected".

### Output

`200` with a `Bundle`, `type: collection`: one `MedicationRequest` per prescribed drug, then one
`Communication` per piece of patient advice. Either list may be empty — a session that produced
neither yields an empty Bundle, not an error. An unknown or already-consumed session id is a 401.

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

`subject` never carries a reference — no `Patient` is stored here. Correlate the Bundle with your
own record using the session id you polled with.

Parse `quantity` as a **decimal**: partial packs are real.

Each prescription comes back at exactly one code level, whichever Prescriptor prescribed at, so
match on whichever coding is present rather than looking for PRK. That is the reverse of the input
side, where a PRK or HPK you send is expanded to a PRK + GPK (+ HPK) triple.

#### `Communication`

| Element | Card. | Notes |
| --- | --- | --- |
| `status` | 1..1 | Fixed `completed` |
| `category` | 1..1 | `instruction` in `http://terminology.hl7.org/CodeSystem/communication-category` |
| `payload.contentString` | 0..1 | Prose advice |
| `payload.contentAttachment` | 0..1 | A link (thuisarts.nl): `contentType: text/uri-list`, `url` set |

Exactly one of the two is present per `Communication` — the attachment form when the advice is a
link, the string form for prose. Switch on which element is populated rather than on the text.

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

**Surveillance** answers the medication-surveillance question on its own: no user interface, no
session, no browser round trip. Your system asks, and reads the signals in the response.

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

**What is behind it** is both halves of Dutch medication surveillance, in one call: the
G-Standaard's **medisch-farmaceutische beslisregels** — interactions, contra-indications,
nierfunctie and the rest — and the **classic G-Standaard checks** for allergy, age as a
contra-indication, duplicate medication and dose control. One report comes back with both merged
into it. Nothing is prescribed, stored or dispensed here: it weighs what you propose and answers.

> **Both the request and the response have a published profile**, so you can validate what you
> send and what you receive rather than read prose about either. The response profile has been
> measured against a handful of real reports rather than a year of them, and the severity mapping
> and the way a rule's text arrives are the parts most likely to move — as may anything else in
> this guide, which is still work in progress. Two things the answer does not cover yet are under
> [What the answer does not cover yet](#what-the-answer-does-not-cover-yet).

**How it differs from Prescriptor:**

| | Prescriptor | Surveillance |
| --- | --- | --- |
| Who decides | the care provider, in Prescriptor's UI | your system, from the signals returned |
| Session | yes, with a browser round trip | none |
| What comes back | prescriptions and patient advice | the signals themselves |
| Prescription | written in Prescriptor | proposed by your system, not created here |

### Two bases, and what follows from it

`/fhir/surveillance` is a **separate FHIR base**, not a path inside `/fhir/evs`: FHIR reserves the
path space under a base for resource type names, so
`/fhir/evs/surveillance/$check-medication-request` would parse as an operation on a resource type
called `surveillance`. Four consequences:

- **Its own CapabilityStatement**, at `GET /fhir/surveillance/metadata`, unauthenticated like
  [Prescriptor's](#get-fhirevsmetadata) and listing this base's two operations.
- **The same credentials** and the same 401s — see [Authentication](#authentication).
- **One version number for both contracts**, because there is one guide and the version is stamped
  on every artifact in it. A release that only touches surveillance still moves the number
  `GET /fhir/evs/metadata` reports; which contract a change belongs to is named in the changelog.
- **A path in neither base is a 404**, from the container rather than a FHIR `OperationOutcome`.

Everything else is shared: the content types and error shape, the profiles for patient, current
medication, allergies, contra-indications and lab results, the [Code systems](#code-systems) and
the [Lab determinations](#lab-determinations). A system that already opens Prescriptor sessions has
no new payload to learn, only a new address to post to.

## `POST /fhir/surveillance/$check-medication-request`

Given a patient's context and one or more proposed prescriptions: which signals fire?

> **An empty `Bundle` means the check ran and no rule fired.** Nothing else can produce one: every
> way for the check not to happen — unreachable upstream, refusal, a report that did not come back
> — is a **500** with an `OperationOutcome`, never a 200 with no findings. A prescriber who sent a
> medication list cannot tell an empty list of findings apart from a genuine all-clear.

### `$check-medication-request` input

A `Parameters` resource, conforming to `fhirhub-SurveillanceInput`. Anything outside the
cardinalities below is a 400 naming the element.

| Parameter | Card. | Type | Notes |
| --- | --- | --- | --- |
| `patient` | 1..1 | `Patient` | `gender` and `birthDate`, exactly as for a session |
| `xisId` | 1..1 | `string` | Your system id, non-blank |
| `xisVersion` | 1..1 | `string` | Your release version, non-blank |
| `prescription` | 1..* | `MedicationRequest` | The prescriptions to check. **Required**, and repeatable here where a session takes one |
| `medicationStatement` | 0..* | `MedicationStatement` | The patient's current medication, in PRK or HPK |
| `allergyIntolerance` | 0..* | `AllergyIntolerance` | `code.coding` in SSK, SNK or OGGrp |
| `condition` | 0..* | `Condition` | `code.coding` in CICode or ICPC |
| `observation` | 0..* | `Observation` | A LOINC-coded lab determination — see [Lab determinations](#lab-determinations) |

**Every resource is one you already build.** All of them bind the same profiles as the session
operations, and `prescription` binds the same `fhirhub-PrescriptionInput` that `$createrx-session`
takes — so a prescription can be checked here and then handed to a session without being reshaped.
Everything the session sections say about each of them applies unchanged, including that an
unresolvable drug code fails the whole request rather than being skipped.

**At least one `prescription` is required**: this operation weighs proposals against a patient's
context, and a request carrying none has nothing under test. That includes a request carrying only
`medicationStatement` — the dossier question has its own operation,
[`$check-medication-statement`](#post-fhirsurveillancecheck-medication-statement). `prescription`
is repeatable because a proposed regimen is weighed as a whole: two new drugs can interact with
each other and with nothing the patient already takes.

**`medicationStatement` is what the prescriptions are weighed against.** Sending an incomplete list
is the one error nothing downstream can detect: the answer is about the list you sent, not about
the patient.

**There is no `endSessionUrl` and no `reason`.** Nothing is launched, and the reason for encounter
drives a formulary lookup rather than surveillance. Sending either is a 400.

#### What is read beyond the code, and what each element buys you

The code identifies the drug; these decide which rules can look at it. All are optional in the
profile and none is optional in effect.

| Element | On | What it does |
| --- | --- | --- |
| `id` | every resource | Comes back on `DetectedIssue.implicated` for medication and in `evidence` for the rest, so you can show a signal against the row it is about instead of matching on codes. Send one |
| `code.coding.display`, else `code.text` | `allergyIntolerance`, `condition` | Your own wording, written into the **title and the body** of the signal — "In het dossier is een allergie (PENICILLINES) geregistreerd" — so without it the prescriber reads a sentence with a gap in it. Where you send none, the code is used |
| `dosageInstruction` — the `CodedDirections` extension, or `text` | `prescription` | The NHG Tabel 25 instruction. Dose control cannot check a dose without it, and says so in a signal |
| `dispenseRequest.quantity` | `prescription` | The amount to be dispensed, read by dose control alongside the instruction |
| `reasonCode`, with an ICPC-1 NL coding | `prescription` | The indication. Dose bands are keyed on the reason as well as the product; without one the check falls back to the "alle zorg" band |
| `dispenseRequest.validityPeriod`, else `authoredOn` | `prescription` | When the prescription starts and ends |
| `effectivePeriod`, else `effectiveDateTime` | `medicationStatement` | When the use started and, if it has, ended |

**A missing start date is read as "today", and a stated one is obeyed.** Both engines skip a drug
whose use starts in the future, so an entry with no start is treated as in use as of now — which is
what `status: active` already asserts. It runs the other way too: an `effectivePeriod` that
**ended** says the patient is not taking the medication, and that entry is not weighed. Send the
period you mean.

**Send a weight for any patient whose dose depends on it, and a height with it.** Dose bands are
selected by weight and body surface before they are compared, so a weight-dependent band with no
weight is not a check that passes — it is a red signal saying it could not run: *"Geen
doseringscontrole: onbekend actueel gewicht"*. Send them as ordinary `observation` parameters with
LOINC `29463-7` (`kg`) and `8302-2` (`cm` or `m`); body surface is derived from the two upstream,
so a height matters for any drug dosed per m².

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

Allergies, contra-indications and lab results are omitted above for length; they are identical to
the session payloads. A complete request is published as
[`$check-medication-request` example, the reference case](Parameters-ExampleSurveillanceReferenceCase.html)
— metformine proposed for a pregnant patient with an eGFR of 35 who already takes ibuprofen and
omeprazol. It is the FHIR form of the request Digitalis documents as `example-1-req.xml`, so the
two can be read side by side with the signals that request produces.

### `$check-medication-request` output

A `Bundle` with `type: collection`, one `DetectedIssue` per signal, in the order the report listed
them.

| Element | | |
| --- | --- | --- |
| `Bundle.identifier` | 0..1 | The report id (`CRID`). **Log it**: it is what Digitalis support asks for when a prescriber queries a signal, and the only handle that ties an answer to the run that produced it |
| `Bundle.timestamp` | 0..1 | When the report was produced upstream |
| `DetectedIssue.severity` | 0..1 | `high`, `moderate` or `low`, from the rule's own red, orange and green. Absent where the rule stated no level |
| `DetectedIssue.code.text` | 1..1 | The title to show. The message's own title where it has one, the rule's otherwise. No coding — see below |
| `DetectedIssue.detail` | 0..1 | The rule's full text, as plain text with the paragraphs and the numbered steps on their own lines |
| `DetectedIssue.identifier` | 0..* | The rule's own id — `MFB-0000000068-v000006` for a beslisregel, `hub-doublemedication-prkA-1090` and its siblings for the classic checks. Stable across reports; route and de-duplicate on this |
| `DetectedIssue.evidence.code` | 0..* | What the rule read: a drug as its PRK, GPK, HPK and ATC together, a lab result as its LOINC code with `text` carrying the determination and the value, a contra-indication as its CICode |
| `DetectedIssue.implicated` | 0..* | The medication the risk is in, as a logical reference: `identifier.value` is the `id` you sent on that resource and `type` says whether it was a `MedicationRequest` you proposed or a `MedicationStatement` the patient already takes |
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

**A green signal is a finding, not a near-miss.** `low` means a rule fired and concluded that no
action is needed — "Dit is GEEN contra-indicatie", "Bij deze interactie is GEEN actie nodig" — and
it answers a question the prescriber's own dossier raised. Hiding it hides that answer.

**For an allergy signal, `implicated` is how you read the verdict.** Each allergy is weighed
against each proposal and the finding comes back either way: `low` **with no `implicated`** means
the allergy was checked and nothing matched, `high` **naming a drug** means it matched. The rule's
text reads "…geregistreerd voor het onderstaande middel" in both cases, which is the upstream's
wording and is only accurate in the second — so branch on `severity` and `implicated`, not on the
sentence.

**`code` carries no coding**, only text. FHIR's own `DetectedIssue` categories are a classification
the upstream does not make, and deriving one from a rule id would be this interface guessing. Route
on `identifier`, show `code.text`.

**The response has a profile, and `meta.profile` is still not asserted.** Validate against
`fhirhub-SurveillanceBundle` explicitly — it states the fixed `collection` type, both identifier
systems, `code` carrying text and no coding, and `implicated` as a logical reference. Nothing this
service emits claims a profile, so do not route on `meta.profile`; see [Profiles](#profiles).

### What the answer does not cover yet

Two gaps, and each is a rule that stays silent rather than an error you would notice — so a host
has to decide what to tell a prescriber.

- **Rules that compare the prescribed daily dose against the defined daily dose cannot fire.**
  Computing a PDD means decoding the NHG Tabel 25 instruction, and this interface passes that
  string through undecoded (see [Extensions](#extensions)). The dose *bands* are checked, which is
  the larger half of dose control; the DDD ratio is not.
- **A partial answer is not distinguishable from a complete one.** If the rules engine answers and
  the classic checks fail, the report comes back with what ran, and the upstream does not say which
  half is missing. What this interface will never do is present nothing at all as an all-clear.

**The credentials on this base are carried upstream but not adjudicated there**, so unlike a
session, a wrong practice id is not rejected with a 401. Reach this endpoint from your server
rather than from a client you do not control, and talk to Digitalis about the deployment
restrictions in front of it.

### `$check-medication-request` errors

| Condition | Status |
| --- | --- |
| Missing or malformed Basic credentials | 401 |
| Body fails `fhirhub-SurveillanceInput`, or a G-Standaard code cannot be resolved | 400 |
| The check could not be run — upstream unreachable, refused, or answered without a report | 500 |

Every 500 says in `diagnostics` that no conclusion may be drawn about the patient's medication.
There is no status in this contract that means "the check partly ran".

## `POST /fhir/surveillance/$check-medication-statement`

The same question asked of a different subject: rather than weighing a proposal against a dossier,
it weighs **a dossier against itself**. Every `medicationStatement` you send is examined — for
allergy, for age, for dose, and against every other entry for duplicate medication — and the answer
comes back in the same shape as
[`$check-medication-request`](#post-fhirsurveillancecheck-medication-request).

> **This is not `$check-medication-request` with the prescription left out.** It takes no
> `prescription` at all, and one sent here is a **400** rather than an element quietly dropped: a
> request whose proposed drug was ignored would be answered for the patient's existing medication
> alone — a real answer to a question you did not ask.

**What it is for.** A medication review, and anything else that asks what is wrong with what a
patient is already taking. The signals only this operation can surface are the ones where nothing
new is prescribed:

- an allergy or contra-indication **recorded after** the medication was started
- a dose that no longer fits a **nierfunctie that has since dropped**, or a weight that has changed
- a **duplicate** between two drugs of which neither is new
- an age band the patient has since **crossed** — a drug that was fine at 74 and is a signal at 75

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
| `observation` | 0..* | `Observation` | A LOINC-coded lab determination — see [Lab determinations](#lab-determinations) |
| ~~`prescription`~~ | — | — | **Not accepted.** Use [`$check-medication-request`](#post-fhirsurveillancecheck-medication-request) |

**`medicationStatement` means something different here**, and it is the one thing not to assume.
In the other operation it is the *background*: the medication a proposal is weighed against. Here
every entry is a *subject*: it is itself checked, and compared with the others.

**Send the whole list.** The answer is about the list you sent, and an entry you left out is a drug
that was neither checked nor reported as unchecked. Nothing downstream can detect the omission.

**Everything else is unchanged** — the same resource profiles, the same G-Standaard code systems at
PRK or HPK level, the same LOINC determinations and units, the same `Authorization: Basic` header,
the same 400 on an unresolvable drug code. A host that can build a `$check-medication-request` body
can build this one by dropping one parameter.

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

**The same `Bundle` of `DetectedIssue`**, read as described under
[`$check-medication-request` output](#check-medication-request-output). An empty `Bundle` means the
same thing under the same guarantee: the check ran and nothing fired, or you get a 500.

**One difference, and it is in `implicated`.** A signal from this operation names your
`MedicationStatement` where the same signal from `$check-medication-request` would have named a
`MedicationRequest`:

```jsonc
"implicated": [ {
  "type": "MedicationStatement",
  "identifier": { "value": "ms-metformine" },
  "display": "METFORMINE HCL TABLET 500MG"
} ]
```

Both carry your own resource `id` as the identifier, so set one.

**The two gaps are the same two** — see
[What the answer does not cover yet](#what-the-answer-does-not-cover-yet).

### `$check-medication-statement` errors

| Condition | Status |
| --- | --- |
| Missing or malformed Basic credentials | 401 |
| Body fails `fhirhub-SurveillanceStatementInput` — including a `prescription` parameter, which this operation does not define — or a G-Standaard code cannot be resolved | 400 |
| The check could not be run — upstream unreachable, refused, or answered without a report | 500 |

Same shapes and the same rule: no 500 permits a conclusion about the patient's medication.

## Lab determinations

Lab values are coded in **LOINC** and in nothing else, and the accepted codes are a closed list.
Both halves come from the G-Standaard: it publishes which LOINC codes count as which
medisch-farmaceutische beslisregel parameter, and the rules engine tests the code you send. Nothing
is translated on the way through.

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
| Lengte | `8302-2` | `cm` or `m` | dose checking |

**A determination outside the list is a 400, not a silent no-op.** The rules can test twelve
patient measurements in total, and anything else a host holds changes no decision — but a
prescriber who supplied a lab result and saw no warning would read that as an all-clear, so the
request is refused instead.

**One eGFR code.** Dutch laboratories report CKD-EPI, so `62238-1` is the code to send. The
G-Standaard also lists `77147-7` (MDRD) and `50210-4` (cystatin C) for the same parameter and
neither is accepted; if your source reports one of them, raise it with Digitalis rather than
re-labelling the value, because the formulas do not give the same number.

**The unit is checked against the code.** `valueQuantity` must carry
`system: "http://unitsofmeasure.org"` and the `code` in the table. The number is evaluated in the
unit the rule was written in, so a kalium in mg/dL rather than mmol/L is a different answer rather
than a rounded one, and nothing downstream could notice. An eGFR must arrive as
`mL/min/{1.73_m2}`, not `mL/min`. Exact conversions are done for you: a length in `m` is forwarded
in centimetres.

**Leave `display` out unless it is LOINC's own term.** It is not ignored — a `display` you send
becomes the caption Prescriptor shows the prescriber — but one that disagrees with LOINC is a hard
validation error, because unlike the G-Standaard tables LOINC *is* distributed with the validator.
Omitting it costs nothing: this interface supplies its own caption, `eGFR volgens CKD-EPI` for
`62238-1`.

**Dates matter as much as values.** The rules test both — *is the ClCr older than 13 months*, *is
de INR max. 24 uur oud* — so `effectiveDateTime` is required, and it must be when the sample was
taken rather than when the report was released. A value older than the rule's window counts as
absent.

**Only the most recent result for a determination is used.** Send two eGFRs and the rules engine
tests the later one and ignores the earlier; it is not an average, and the older value is not
tested separately. Two consequences:

- **Same-day results need a time to be ordered.** Results stating only a date are equally recent as
  far as the engine is concerned, and it resolves the tie by taking the one that appears **first**
  in the request. With a time on both, the later one wins.
- **Weight and height do not go by date at all.** Dose checking reads the **first** `29463-7` and
  the first `8302-2` in the request whatever their dates say. Send one of each, and send the
  current one.

If you hold a history, send the determination you want weighed rather than the series: the extra
values change no decision and cost you the certainty of knowing which one did.

One determination per `observation` parameter, repeated as needed. `component` is not read — resolve
a multi-component result to one number first. `interpretation`, `referenceRange`, `method` and
`note` are ignored: this is an input to a decision, not a lab report.

## Profiles

Every payload in this document has a `StructureDefinition` you can validate against, published as
an implementation guide with the canonical `http://spec.digitalis.nl/fhir`:

| Payload | Profile |
| --- | --- |
| `$formulary-session` request | `fhirhub-FormularySessionInput` |
| `$createrx-session` request | `fhirhub-CreateRxSessionInput` |
| Session response | `fhirhub-SessionOutput` |
| `$session-result` response | `fhirhub-ResultBundle` |
| `$check-medication-request` request | `fhirhub-SurveillanceInput` |
| `$check-medication-statement` request | `fhirhub-SurveillanceStatementInput` |
| Surveillance response (both operations) | `fhirhub-SurveillanceBundle`, whose entries are `fhirhub-SurveillanceFinding` |

The input profiles slice `Parameters.parameter` by name and point each slice at a resource profile,
so validating the request body checks the resources inside it in one pass. **The slicing is
closed**: an undefined parameter name is an error. That is deliberate — the mapping layer reads the
parameters it knows and ignores the rest, so `medicationstatement` for `medicationStatement` would
otherwise open a session against a silently thinner medication list.

**These profiles are enforced.** A request body is validated before anything else happens — before
the G-Standaard lookup and before the call upstream — and a non-conformant payload is a 400 whose
`OperationOutcome` carries one issue per error with the element it failed on, so you get every
problem in one response rather than one per round trip.

Only errors reject. Warnings are normal: the G-Standaard code systems cannot be expanded, so every
G-Standaard coding produces a "could not be validated" note.

The payloads in this document are also instances in the IG, each validated against the profile it
claims on every build, so the documentation cannot drift away from the profiles.

### Elements FHIR requires that fhir-hub does not read

Base R4 makes several elements mandatory that this interface never looks at, and a profile can only
constrain, so a **conformant payload must carry them** even though sending them changes nothing:

| Resource | Element | Note |
| --- | --- | --- |
| `AllergyIntolerance` | `patient`, `clinicalStatus` | `clinicalStatus` is required by invariant `ait-1` |
| `Condition` | `subject` | |
| `MedicationStatement` | `status`, `subject` | |
| `Observation` | `status` | |
| `MedicationRequest` (`prescription`) | `status`, `intent`, `subject` | `$createrx-session` and the surveillance operations |

The patient travels as a sibling parameter rather than a contained resource, so there is nothing
for `patient` and `subject` to reference: send a `data-absent-reason` of `unknown`, as the examples
do. Validation runs before mapping, so these are required in practice and not only on paper.

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

These are the same OIDs Nictiz binds to in `nl-core-AllergyIntolerance` and
`nl-core-MedicationContraIndication`, so a coding you send here is one you can send to any Dutch
system that follows those profiles.

**Copy these strings exactly, and send nothing else.** There is one accepted `system` per row and
no lenient form: the bare code-system token — `PRK`, `HPK`, `SSK`, `SNK`, `OGGrp`, `CICode`, `ICPC`
— is not a FHIR `system`, and sending one is a 400 on the element it was on. A rejection lists the
URIs the element accepts:

```
AllergyIntolerance.code has no coding in a system this interface routes; expected one of
[urn:oid:2.16.840.1.113883.2.4.4.1.725, urn:oid:2.16.840.1.113883.2.4.4.1.750,
urn:oid:2.16.840.1.113883.2.4.4.1.902.122]
```

The G-Standaard tables are licensed and are not distributed with the profiles, so what is checked
is the `system`, not the code: a coding from a system above passes with a warning that its code
could not be verified, and a coding from any other system is an error. The one code shape that *is*
checked is ICPC-1 NL, by an invariant rather than by expansion.

## Extensions

Two, both Digitalis-defined because no national artifact covers them. Both canonicals dereference:
a browser gets the definition page, `Accept: application/fhir+json` gets the `StructureDefinition`.

**`ext-Dosage.CodedDirections`** —
`http://spec.digitalis.nl/fhir/StructureDefinition/ext-Dosage.CodedDirections`, `valueString`. The
NHG Tabel 25 coded instruction, e.g. `"3-4D1S; gedurende max. 1 maand"`.

This is the dosing instruction **in both directions**. Store it and hand it back unchanged unless
you mean to change the dose; `Dosage.text` beside it is the human-readable form, for display only.
If you do not parse Tabel 25, treat the string as opaque and pass it through — a pharmacy system
downstream reads it natively. No `timing` or `doseAndRate` is produced and any you send is
**ignored**, so to change a dose, edit this extension.

**`ext-MedicationRequest.OpiumActClassification`** —
`http://spec.digitalis.nl/fhir/StructureDefinition/ext-MedicationRequest.OpiumActClassification`,
`valueCodeableConcept`. A G-Standaard bijzonder kenmerk, present only when the product falls under
the Opiumwet.

Only code `2` ("Product valt onder Opiumwet in volle omvang") is emitted today. Read the code
rather than treating the extension as a boolean: `65` and `107` carry different handling rules for
a pharmacist and may appear later, so switch on the code and have a default branch.

## Errors

Every error is an `OperationOutcome`, the 401s included, so one error path in your client handles
all of them: no response in this API is un-parseable by a FHIR library.

| Condition | Status |
| --- | --- |
| Missing or malformed Basic credentials | 401 |
| Organization id or key rejected by Prescriptor (`Invalid organization ID or key`) | 401 |
| Unknown or already-consumed session id (`No data found for the session ID`) | 401 |
| Invalid request | 400 |
| Prescriptor unreachable (`Could not reach Prescriptor`) or unparseable | 500 |
| Medication surveillance unreachable, refused, or answering without a report | 500 |

A 400 comes from one of three places. The messages below are quoted as returned, so you can
recognise them while building.

**The profile**, which is where most of them come from, in the FHIR validator's own wording:

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

**The operation binding**, before the body is validated at all, when a parameter carries the wrong
type:

- `HAPI-0362: Request has parameter reason of type Coding but method expects type CodeableConcept`
- `HAPI-0362: Request has parameter endSessionUrl of type StringType but method expects type UrlType`

**This interface's own rules**, for the things a profile cannot express:

- `G-Standaard has no product for PRK 404040, so it cannot take part in medication surveillance`
- `LOINC code '718-7' is not a determination medication surveillance reads, so sending it would suggest it had been weighed. Accepted: [62238-1, 2823-3, …]`
- `Observation.valueQuantity for 2823-3 (Kalium (serum of plasma)) must be in [mmol/L] as a UCUM code, not 'mg/dL': the upstream carries no unit, so the value is evaluated as mmol/L`
- `PRK code '18996a' is not numeric`
- `The 'session' parameter is required` — on `$session-result`

None of this wording is part of the contract. Branch on the HTTP status, and show `diagnostics` to
whoever has to act on it rather than matching on the text.

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
extensions, trimmed here. `expression` and `location` are the two fields worth surfacing to a
developer: they name the element that failed.

## Behaviour to design around

- **`$session-result` is single-use.** Requesting it ends the session in Prescriptor; a second call
  returns 401.
- **Medication surveillance fails closed.** One unresolvable drug code fails the whole request with
  a 400 that names it; nothing is silently skipped. Refresh the code and retry rather than dropping
  the drug from the list.
- **No patient identity comes back.** Correlate the Bundle with your own record using the session
  id you requested it with.
- **Structured dosing is not round-tripped.** Edit the `CodedDirections` extension, not `timing`.
- **A GPK-coded prescription cannot be handed back** to `$createrx-session`, which needs a PRK or an
  HPK. See [`prescription`](#prescription).
- **Nothing in a request is ignored.** An unrecognised parameter name, a repeated single-valued
  parameter and a wrong value type are all 400s, so a typo surfaces as a rejection rather than as a
  session opened on partial data.
- **Lab units are dropped.** Send values in the determination's own unit; a `Quantity.unit` is
  ignored.

## Moving from the JSON API

If you are replacing an integration with the Prescriptor JSON API, the operations, the semantics
and the HTTP status codes are the same. Three differences go beyond syntax:

- **Credentials move to the HTTP layer.** The organization id and key leave the request body and
  become an HTTP Basic header. The values are unchanged.
- **Drug codes in `medicationStatement` are resolved before the session opens.** Each PRK or HPK is
  looked up in the G-Standaard and forwarded as a PRK + GPK (+ HPK) triple, and a code that cannot
  be resolved fails the request with a 400. The JSON API forwards current medication at the level
  you supply without that lookup, so a code it accepts can be rejected here. Check your codes
  against a current G-Standaard before switching over.
- **The code system stops being a separate field and becomes the `system` on the coding.** Where the
  JSON API took a code alongside a `PRK`, `SSK` or `ICPC` token, here the token is replaced by the
  URI from [Code systems](#code-systems). The tokens themselves are not accepted as a `system`.

Read the operation you are calling and the profile it names rather than porting your payloads
field by field: that carries across assumptions the JSON API allowed and this one does not.

## Current limitations

Things you may expect to be able to do, and cannot yet.

- **No `meta.profile` is asserted** on any resource, so do not filter or route on it. Validate
  against the profile URLs above explicitly instead. The IG's example instances carry one because
  the publishing tool adds it; a live payload does not.
- **nl-core is not derived from**, so do not expect these resources to satisfy nl-core. Three of the
  five are blocked on Nictiz rather than on effort: `nl-core-MedicationContraIndication` profiles
  `Flag` rather than `Condition`, `nl-core-LaboratoryTestResult` requires an `Observation.category`
  this interface neither sends nor reads, and `nl-core-MedicationUse2` is not published in the
  nl-core package. Ask Digitalis before building anything that depends on nl-core conformance.
- **The artifacts are `draft`**, and while the status is `draft` the change policy allows a breaking
  change at a minor version — see *Versioning and change policy* in the published guide. Agree with
  Digitalis how you want to be told about a change before you go live.
- **The G-Standaard code systems are not distributed**, so no validator can check a G-Standaard
  *code* — only the `system` it came from. A wrong code inside a system this interface routes
  reaches Prescriptor and comes back as a 400 from the medication lookup rather than as a validation
  error.
- **The medication-surveillance response is the youngest part of the contract.** Its profile
  describes what the service emits today and is validated against real reports on every build, but
  the severity mapping and the way a rule's text arrives are the likeliest things to move, and two
  classes of rule cannot fire yet — see
  [What the answer does not cover yet](#what-the-answer-does-not-cover-yet).

Questions, or a case this document does not cover: contact Digitalis.
