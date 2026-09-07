# fhir-hub Implementation Guide

One FHIR R4 interface in front of **two distinct Digitalis applications**. They are separate
contracts on separate FHIR bases, and which one you are integrating with decides most of what you
need from this document:

| | | |
| --- | --- | --- |
| **[Prescriptor](#prescriptor)** | `/fhir/evs` | Prescribing, in Prescriptor's own user interface. Your system opens a session, hands the browser over and collects the result |
| **[Surveillance](#surveillance)** | `/fhir/surveillance` | Medication surveillance on its own, asked as a question and answered in the payload. No interface, no session: your system asks, and reads the signals in the response |

Read the section for your application first. Everything after them —
[Lab determinations](#lab-determinations), [Profiles](#profiles),
[Code systems](#code-systems), [Extensions](#extensions), [Errors](#errors) — is shared by both,
and so are [Conventions](#conventions) and [Authentication](#authentication) before them. That
sharing is deliberate: one set of credentials, one set of payload profiles for patient, medication,
allergies, contra-indications and lab results, one release number.

- **FHIR version** — R4 (4.0.1). All payloads are `Parameters` or `Bundle`; there is no resource
  REST API and no search.
- **Relationship to the Prescriptor JSON API** — the Prescriptor contract has the same operations,
  the same semantics and the same HTTP status codes as the JSON API, with authentication moved from
  the request body onto HTTP Basic. If you are moving across from it, the differences that affect
  you are collected under [Moving from the JSON API](#moving-from-the-json-api). Surveillance has no
  JSON-API predecessor.
- **Statelessness** — no session store, so persist the session id yourself, and store the result
  Bundle when you receive it. Nothing can be re-fetched.
- **Normative status** — the prose and the `StructureDefinition`s are one specification, published
  together under the canonical `http://spec.digitalis.nl/fhir`. Every payload described here has a
  profile you can validate against, and every request is checked against its profile before it is
  processed — the unimplemented one included. See [Profiles](#profiles).

## Table of contents

- [Conventions](#conventions)
- [Authentication](#authentication)
- [**Prescriptor**](#prescriptor)
  - [The Prescriptor flow](#the-prescriptor-flow)
  - [`GET /fhir/evs/metadata`](#get-fhirevsmetadata)
  - [`POST /fhir/evs/$formulary-session`](#post-fhirevsformulary-session)
  - [`POST /fhir/evs/$createrx-session`](#post-fhirevscreaterx-session)
  - [`GET /fhir/evs/$session-result`](#get-fhirevssession-result)
- [**Surveillance**](#surveillance)
  - [`POST /fhir/surveillance/$check-medication`](#post-fhirsurveillancecheck-medication)
- [Lab determinations](#lab-determinations)
- [Profiles](#profiles)
- [Code systems](#code-systems)
- [Extensions](#extensions)
- [Errors](#errors)
- [Behaviour to design around](#behaviour-to-design-around)
- [Moving from the JSON API](#moving-from-the-json-api)
- [Current limitations](#current-limitations)

## Conventions

| | |
| --- | --- |
| Base paths | `/fhir/evs` for [Prescriptor](#prescriptor), `/fhir/surveillance` for [Surveillance](#surveillance) — two FHIR bases, each with its own `metadata`, each addressed in full. They are siblings rather than nested because FHIR reserves the path space under a base for resource type names |
| Request content type | `application/fhir+json` (`application/fhir+xml` also accepted) |
| Response content type | `application/fhir+json`, pretty-printed |
| Format override | `?_format=json`, `?_format=xml`, `?_format=html` |
| Browser rendering | A request whose highest-ranked `Accept` is `text/html` gets a syntax-highlighted HTML page instead of raw JSON |
| Errors | Always `OperationOutcome` — no endpoint returns a non-FHIR body |

Set `Accept: application/fhir+json` explicitly in machine clients; relying on the default works,
but a browser-style `Accept` header will get HTML.

**A parameter name this interface does not define is a 400, and so is a repeat of a single-valued
one.** The request profiles close the list of parameter names and carry each one's cardinality, so
`medicationstatement` for `medicationStatement` is rejected — *"does not match any known slice
defined in the profile … and slicing is CLOSED"* — rather than silently dropped, and a second
`endSessionUrl` is rejected as *"max allowed = 1, but found 2"*. Both are caught before anything is
sent upstream; see [Profiles](#profiles).

## Authentication

```
Authorization: Basic base64(organization.id ":" organization.key)
```

The two halves are the same organization id and key the JSON API takes in its request body, so no
new credentials are issued for the move.

Credentials are checked by Prescriptor, not here, which has two consequences for you. A wrong pair
reaches you as a **401 on the operation you called**, not as a separate login step — there is no
token to obtain and nothing to refresh. And a licence change takes effect on the next call, so a
401 that appears without a code change is worth checking with Digitalis before you debug your
client.

Unauthenticated paths: `GET /fhir/evs/metadata`, `GET /fhir/evs/OperationDefinition/**` and
`GET /actuator/health/**` — the discovery documents, so you can read what the interface accepts
before your credentials are issued. Everything else requires the header; a missing or malformed one
is a 401 with `WWW-Authenticate: Basic`.

## Prescriptor

**Digitalis Prescriptor 3** is the prescribing application: the care provider chooses a treatment,
writes the prescription and reads the signals in Prescriptor's own user interface. Your system
supplies the patient context, hands the browser over, and collects what came out.

```
Base            /fhir/evs
Interaction     session-based — open, redirect the browser, poll for the result
Implemented     yes
```

`evs` is *elektronisch voorschrijfsysteem*; the segment is part of the base and never omitted.

| Operation | |
| --- | --- |
| [`POST /fhir/evs/$formulary-session`](#post-fhirevsformulary-session) | Open a formulary session: a treatment is chosen for a stated reason for encounter |
| [`POST /fhir/evs/$createrx-session`](#post-fhirevscreaterx-session) | Open a prescribing session without a formulary lookup, optionally starting from a prescription you already hold |
| [`GET /fhir/evs/$session-result`](#get-fhirevssession-result) | Collect the prescriptions and patient advice. Single-use: it ends the session |
| [`GET /fhir/evs/metadata`](#get-fhirevsmetadata) | The CapabilityStatement. Unauthenticated |

Read [The Prescriptor flow](#the-prescriptor-flow) first: three calls plus a browser round trip
your system does not mediate, and the ordering constraints between them.

**Medication surveillance happens inside a Prescriptor session too**, which is why the current
medication you send matters as much as the prescription. Prescriptor weighs the treatment against
the patient's medication, allergies, contra-indications and lab results while the care provider
works, and shows the signals in its own interface — they are not returned to your system. If what
you want is the signals themselves, in a payload, that is the other application:
[Surveillance](#surveillance).

**There is no resource REST API and no search.** Reads or writes against `/fhir/evs/Patient`,
`/fhir/evs/MedicationRequest` and the like are not supported, and no resource is stored here to
read back.

## The Prescriptor flow

How the three [Prescriptor](#prescriptor) operations fit together: three calls, plus a browser
round trip your system does not mediate. Nothing here applies to
[Surveillance](#surveillance), which has no session and no flow — one request, one answer.

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

1. **Open a session.** The host posts the patient context — demographics, allergies,
   contra-indications, current medication, lab results — and receives a `sessionId` and a `url`.
   Current medication is resolved against the G-Standaard *before* the session is opened, because
   surveillance needs PRK and GPK together.
2. **The care provider works in the Prescriptor UI** at that `url`. fhir-hub is not involved.
   When finished, Prescriptor redirects the browser to the `endSessionUrl` the host supplied.
3. **The host fetches the result** with the `sessionId` and receives a `Bundle` of
   `MedicationRequest` (prescriptions) and `Communication` (patient advice) resources.

**The ordering is not advisory.** A session id is only valid between step 1 and step 3; there is no
store here to look one up in, and step 3 consumes it — a second `$session-result` for the same id is
a 401. Persist the id when you receive it and the Bundle when you fetch it.

Note where surveillance happens in this flow: at step 1, inside Prescriptor, on the medication list
your system supplied — which is why an unresolvable drug code fails the whole call rather than
being skipped. The signals are shown to the care provider in Prescriptor's interface and are not
part of the step 3 Bundle. To receive the signals themselves, see [Surveillance](#surveillance).

## `GET /fhir/evs/metadata`

The FHIR CapabilityStatement. Unauthenticated, so you can read it before your credentials are
issued.

**Input** — none. **Output** — `CapabilityStatement` listing `formulary-session`,
`createrx-session` and `session-result`.

```bash
curl -sS 'http://localhost:8080/fhir/evs/metadata?_format=json'
```

**`software.version` is the release of this specification the deployment implements**, e.g.
`0.3.0` — not a build number of the service, and not the FHIR version, which is `fhirVersion`.
Read it before you start sending anything introduced in a later release: a parameter name this
deployment does not know is a 400 rather than an ignored element, because the request profiles
close the list of names. `implementation.description` names the same release alongside the
canonical.

The statement links each operation to a generated `OperationDefinition`:
`/fhir/evs/OperationDefinition/-s-formulary-session`, `…/-s-createrx-session` and
`…/-s-session-result`. Each describes **every** input parameter with `use: in`, a `type` and a
cardinality, so a request can be generated from the definition alone — `prescription` reads
`max: "0"` on `$formulary-session` and `max: "1"` on `$createrx-session`. Those URLs are readable
unauthenticated, like the statement that advertises them.

The `OperationDefinition`s give you the parameter list; the [profiles](#profiles) give you what has
to be true *inside* each parameter. Use the CapabilityStatement to confirm the operations and the
FHIR version, and this document for the payloads.

**This statement describes the Prescriptor base only.** [Surveillance](#surveillance) is a separate
contract and has its own, at `GET /fhir/surveillance/metadata`, which lists `check-medication` and
nothing else. Both report the same `software.version`, because both come from one release of this
guide.

## `POST /fhir/evs/$formulary-session`

Opens a **formulary** session: the care provider picks a treatment for a stated reason for
encounter.

### Input

A `Parameters` resource. The cardinalities below are enforced; anything outside them is a 400 that
names the element.

| Parameter | Card. | Type | Notes |
| --- | --- | --- | --- |
| `patient` | 1..1 | `Patient` | `gender` (`male`, `female` or `unknown`) and `birthDate` required — see below |
| `reason` | 1..1 | `CodeableConcept` | ICPC-1 NL. **Required** for this operation |
| `endSessionUrl` | 1..1 | `url` | Where Prescriptor returns the browser. `http` or `https` only |
| `xisId` | 1..1 | `string` | Your system id, non-blank |
| `xisVersion` | 1..1 | `string` | Your release version, non-blank |
| `allergyIntolerance` | 0..* | `AllergyIntolerance` | `code.coding` in SSK, SNK or OGGrp |
| `condition` | 0..* | `Condition` | `code.coding` in CICode or ICPC |
| `medicationStatement` | 0..* | `MedicationStatement` | `medicationCodeableConcept` in PRK or HPK |
| `observation` | 0..* | `Observation` | A LOINC-coded lab determination — see [Lab determinations](#lab-determinations) |
| `prescription` | 0..0 | — | Rejected here with a 400; `$createrx-session` only |

**`patient`** — `gender` must be `male`, `female` or `unknown`. Send the sex when you know it:
sex-specific surveillance checks cannot fire when it is unknown, and the prescriber is not told that
they were skipped.

`other` and an absent `gender` are both a 400. If your record holds `other`, send `unknown`. If it
holds nothing at all, fill the gap before opening a session — this interface will not choose a value
for you.

`birthDate` is required. Nothing else in the `Patient` is read, so you can leave the rest out: no
name, identifier or address is forwarded to Prescriptor or stored here.

**`reason`** — send a `CodeableConcept`; a bare `valueCoding` is rejected, because
`OperationDefinition.parameter.type` is a single code and a declared parameter cannot also be
polymorphic. The `system` must be the ICPC-1 NL OID `urn:oid:2.16.840.1.113883.2.4.4.31.1`, and the
code must match `^[A-Z][0-9]{2}(\.[0-9]{2})?$` (`A01`, `U71.01`); anything else is a 400.

**`endSessionUrl`** — where Prescriptor sends the care provider's browser when the session ends.
Send it as `valueUrl`, and only as `valueUrl`: a `valueString` is refused by the operation binding
and a `valueUri` by the profile. Only `http` and `https` are accepted, so a custom scheme such as an
app deep link is a 400: use an `https` landing page and redirect on from there.

**`xisId` and `xisVersion`** — identify your product, not the practice. Send your own system id and
release version; keep the id stable across releases and move only the version. It is used to trace your calls in this
service's logs when you raise a support question, and is **never forwarded to Prescriptor**.

**`medicationStatement`** — every code is looked up in the G-Standaard. A code that cannot be
resolved **fails the whole request with a 400** naming that code; it is not skipped, and the session
is not opened. So refresh codes against a current G-Standaard before sending, and do not expect to
pass a drug you cannot code — there is no free-text fallback.

**Each entry carries its own code level.** PRK for one drug and HPK for the next is fine, in any
order — every entry is resolved to a PRK + GPK pair before the session opens, so the level you use
per entry is your choice and does not affect the others.

**`observation`** — a laboratory determination or a body measurement, coded in **LOINC**
(`http://loinc.org`), with `effectiveDateTime` and a `valueQuantity` in the unit below. Only the
determinations medication surveillance reads are accepted; see
[Lab determinations](#lab-determinations) for the list, the units and why it is closed.

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

`status` and `subject` on the input resources are not read — and are nonetheless **required**. Base
R4 makes them mandatory and a profile can only constrain, so a payload without them fails
validation. Send them as the example does, with a `data-absent-reason` where you have nothing to
point at, and do not expect either to influence the session. See
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

Treat `url` as opaque: redirect to it unchanged. Its shape is Prescriptor's and is not part of this
contract, so do not parse the session id back out of it — `sessionId` is where it lives.

## `POST /fhir/evs/$createrx-session`

Opens a **CreateRx** session: prescribing without a formulary lookup, optionally starting from a
prescription the host already holds.

Identical to `$formulary-session` except:

| | |
| --- | --- |
| `reason` | **0..1** — optional, because CreateRx prescribes without a formulary lookup. Still validated against the ICPC pattern when present |
| `prescription` | **0..1** `MedicationRequest` — a prescription to open for editing |

### `prescription`

Send back the `MedicationRequest` you received from `$session-result`, with whatever edits you
intend. The two shapes are the same, with one exception: the product must be coded as **PRK or
HPK**, and `$session-result` may have returned it at GPK level. A GPK-coded prescription cannot be
handed back — resolve it to a PRK or an HPK first, or open the session without a prescription.

| Element | Card. | Notes |
| --- | --- | --- |
| `status`, `intent` | 1..1 | Mandatory in base R4, not read. `active` and `order` |
| `subject` | 1..1 | Mandatory in base R4, not read. A `data-absent-reason` of `unknown`, as on the way out |
| `medicationCodeableConcept` | 1..1 | Required. Must carry a PRK **or** HPK coding; PRK wins when both are given |
| `medicationCodeableConcept.coding` (ATC) | 0..1 | `http://www.whocc.no/atc`, forwarded as-is |
| `medicationCodeableConcept.text` | 0..1 | Used as the description when a coding has no `display` |
| `dosageInstruction.extension[CodedDirections]` | 0..1 | The authoritative dosing instruction |
| `dosageInstruction.text` | 0..1 | Fallback when the extension is absent |
| `dispenseRequest.quantity` | 0..1 | `value` plus `code` (or `unit`) as the G-Standaard basiseenheid |

Dosing is read from the `CodedDirections` extension first and from `Dosage.text` only as a
fallback. Edits to `timing` or `doseAndRate` are **ignored** — this interface passes NHG Tabel 25
through verbatim in both directions and derives no structured dosing. See
[Extensions](#extensions).

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

**One shot.** The first successful call *ends the session*; a second call with the same id returns
401, not the same Bundle. Store the Bundle as soon as you receive it, and make sure a retry after a
timeout cannot fire twice — a lost response cannot be recovered.

**Call it once, when the browser returns to your `endSessionUrl`** — that redirect is the signal
that the session is finished. Do not poll on a timer: a 401 does not distinguish "unknown id" from
"already collected", so a polling loop cannot tell a not-yet-finished session from one whose result
it has already consumed and lost.

### Output

`200` with a `Bundle`, `type: collection`. Entries are, in order, one `MedicationRequest` per
prescribed drug followed by one `Communication` per piece of patient advice. Either list may be
empty — a session that produced no prescriptions and no advice yields an empty Bundle, not an
error. An *unknown or already-consumed* session id is a 401 instead.

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

`subject` never carries a reference — this service never stored the `Patient` you sent — so do not
try to read a patient identity out of the Bundle. Correlate it with your own record using the
session id you polled with, and populate `subject` yourself afterwards if your own storage needs it.

Parse `quantity` as a **decimal**. Whole numbers are not guaranteed: partial packs are real.
Clients that assume an integer here will truncate a real quantity.

Do not expect a code hierarchy on the way out. Each prescription comes back at exactly one code
level, whichever Prescriptor prescribed at, so match on whichever coding is present rather than
looking for PRK. This is the reverse of the input side, where a PRK or HPK you send is expanded to a
PRK + GPK (+ HPK) triple for surveillance.

#### `Communication`

| Element | Card. | Notes |
| --- | --- | --- |
| `status` | 1..1 | Fixed `completed` |
| `category` | 1..1 | `instruction` in `http://terminology.hl7.org/CodeSystem/communication-category` |
| `payload.contentString` | 0..1 | Prose advice |
| `payload.contentAttachment` | 0..1 | A link (thuisarts.nl): `contentType: text/uri-list`, `url` set |

Exactly one of the two is present per `Communication`. The attachment form is used when the advice
is a link — its text starts with `https://www.thuisarts.nl` — and the string form for prose, so
switch on which element is populated rather than inspecting the text yourself.

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

**Surveillance** answers the medication-surveillance question on its own: patient context and one
or more proposed prescriptions in, the signals that fire out. No user interface, no session and no
browser round trip — your system asks, and reads the answer in the response.

```
Base            /fhir/surveillance
Interaction     one request, one answer
Answers         a Bundle of DetectedIssue, one entry per signal
```

| Operation | |
| --- | --- |
| [`POST /fhir/surveillance/$check-medication`](#post-fhirsurveillancecheck-medication) | Weigh prescriptions against a patient's context |
| `GET /fhir/surveillance/metadata` | The CapabilityStatement of this base. Unauthenticated |

**What is behind it** is both halves of Dutch medication surveillance, in one call: the
G-Standaard's **medisch-farmaceutische beslisregels** — interactions, contra-indications,
nierfunctie and the rest — and the **classic G-Standaard checks** for allergy, age as a
contra-indication, duplicate medication and dose control. One report comes back with the signals
from both merged into it.

> **The request contract is stable; the response is still `experimental`.** What you send has been
> published and enforced since 0.2.0 and did not change when the check went live at 0.3.0. What you
> get back is younger than that, no response profile is published for it, and the severity mapping
> and the way a rule's text arrives may still move. Build against it, and agree with Digitalis how
> you want to be told about a change before you go live. Three things it does not cover yet are
> listed under [`$check-medication`](#post-fhirsurveillancecheck-medication).

**How it differs from Prescriptor**, beyond the base and the shape of the call:

| | Prescriptor | Surveillance |
| --- | --- | --- |
| Who decides | the care provider, in Prescriptor's UI | your system, from the signals returned |
| Session | yes, with a browser round trip | none |
| What comes back | prescriptions and patient advice | the signals themselves |
| Prescription | written in Prescriptor | proposed by your system, not created here |

Nothing is prescribed, stored or dispensed through this application: it weighs what you propose
and answers. The signals it reports are the ones Prescriptor already runs inside a session —
interactions, duplicate medication, allergy, contra-indication and dose signals, and the
G-Standaard's medisch-farmaceutische beslisregels — with the difference that here they come back to
you rather than being shown to a prescriber in Prescriptor's own screen. See
[`POST /fhir/surveillance/$check-medication`](#post-fhirsurveillancecheck-medication).

### Two bases, and what follows from it

`/fhir/surveillance` is a **separate FHIR base**, not a path inside `/fhir/evs`. FHIR reserves the
path space under a base for resource type names, so a second contract cannot be a segment within
one: `/fhir/evs/surveillance/$check-medication` would parse as an operation on a resource type
called `surveillance`, and there is no such type. Four consequences for you:

- **Its own CapabilityStatement**, at `GET /fhir/surveillance/metadata`, unauthenticated like
  [Prescriptor's](#get-fhirevsmetadata), listing `check-medication` and nothing else, and linking
  its generated `OperationDefinition` at
  `/fhir/surveillance/OperationDefinition/-s-check-medication`.
- **The same credentials.** One `Authorization: Basic` header, the same practice id and licence key,
  the same 401s — see [Authentication](#authentication).
- **One version number for both contracts.** There is one guide and one release, stamped on every
  artifact in it, so a release that only touches surveillance still moves the number
  `GET /fhir/evs/metadata` reports. Which contract a change belongs to is named in the changelog.
- **A path in neither base is a 404**, from the container rather than a FHIR `OperationOutcome`.
  `/fhir/$check-medication` and `/fhir/surveillance/$formulary-session` are both misses.

**What the two applications share** is not an accident and is most of the interface: the same
credentials ([Authentication](#authentication)), the same content types and error shape
([Conventions](#conventions), [Errors](#errors)), the same profiles for patient, current
medication, allergies, contra-indications and lab results ([Profiles](#profiles)), the same
[Code systems](#code-systems) and the same [Lab determinations](#lab-determinations). A system that
already opens Prescriptor sessions has no new payload to learn — only a new address to post to.

## `POST /fhir/surveillance/$check-medication`

The one operation of the [Surveillance](#surveillance) application: given a patient's context and
one or more proposed prescriptions, which signals fire? No browser round trip and no session — one
request, one answer. Read [Surveillance](#surveillance) first for what the application is and how
it differs from [Prescriptor](#prescriptor).

> **An empty `Bundle` means the check ran and no rule fired.** Nothing else can produce one. Every
> way for the check not to happen — an unreachable upstream, a refusal, a report that did not come
> back — is a **500** with an `OperationOutcome`, never a 200 with no findings, because an empty
> list of findings cannot be told apart from a genuine all-clear by a prescriber who sent a
> medication list. It is the same false negative that makes an unresolvable drug code a 400 rather
> than a dropped drug, and it is why this endpoint spent a release answering 501 rather than
> "no issues found".

### `$check-medication` input

A `Parameters` resource. The cardinalities below are enforced today; anything outside them is a 400
that names the element.

| Parameter | Card. | Type | Notes |
| --- | --- | --- | --- |
| `patient` | 1..1 | `Patient` | `gender` and `birthDate`, exactly as for a session |
| `xisId` | 1..1 | `string` | Your system id, non-blank |
| `xisVersion` | 1..1 | `string` | Your release version, non-blank |
| `prescription` | 0..* | `MedicationRequest` | The prescriptions to check. **Repeatable here**, where a session takes one |
| `medicationStatement` | 0..* | `MedicationStatement` | The patient's current medication, in PRK or HPK |
| `allergyIntolerance` | 0..* | `AllergyIntolerance` | `code.coding` in SSK, SNK or OGGrp |
| `condition` | 0..* | `Condition` | `code.coding` in CICode or ICPC |
| `observation` | 0..* | `Observation` | A LOINC-coded lab determination — see [Lab determinations](#lab-determinations) |

**Every resource is one you already build.** `patient`, `medicationStatement`,
`allergyIntolerance`, `condition` and `observation` bind the same profiles as the session
operations, and `prescription` binds the same `fhirhub-PrescriptionInput` that `$createrx-session`
takes — so a prescription can be checked here and then handed to a session without being reshaped.
Everything the session pages say about each of them applies unchanged, including that an
unresolvable drug code will fail the whole request rather than be skipped.

**At least one `prescription` or one `medicationStatement` is required.** Not both — either. A
request carrying neither has nothing to evaluate, and answering it would mean reporting "no
signals" about a patient whose medication never arrived. This is the invariant
`fhirhub-something-to-check`.

**`prescription` is repeatable** because a proposed regimen is weighed as a whole: two new drugs can
interact with each other and with nothing the patient already takes.

**There is no `endSessionUrl` and no `reason`.** Nothing is launched, so there is no browser to
return; and the reason for encounter drives a formulary lookup rather than surveillance. Send either
and the closed slicing makes it a 400.

#### What is read beyond the code, and what each element buys you

The code identifies the drug; these decide which rules can look at it. All of them are optional in
the profile and none of them is optional in effect.

| Element | On | What it does |
| --- | --- | --- |
| `id` | every resource | Comes back on `DetectedIssue.implicated` for medication and in `evidence` for the rest, so you can show a signal against the row it is about instead of matching on codes. Send one |
| `code.coding.display`, else `code.text` | `allergyIntolerance`, `condition` | Your own wording for the allergy or contra-indication. It is written into the **title and the body** of the signal — "In het dossier is een allergie (PENICILLINES) geregistreerd" — so without it the prescriber reads a sentence with a gap in it. Where you send none, the code is used |
| `dosageInstruction` — the `CodedDirections` extension, or `text` | `prescription` | The NHG Tabel 25 instruction. Dose control cannot check a dose without it and says so in a signal |
| `dispenseRequest.quantity` | `prescription` | The amount to be dispensed, read by dose control alongside the instruction |
| `reasonCode`, with an ICPC-1 NL coding | `prescription` | The indication. Dose bands are keyed on the reason as well as the product; without one the check falls back to the "alle zorg" band |
| `dispenseRequest.validityPeriod`, else `authoredOn` | `prescription` | When the prescription starts and ends |
| `effectivePeriod`, else `effectiveDateTime` | `medicationStatement` | When the use started and, if it has, ended |

**A missing start date is read as "today", and a stated one is obeyed.** Both engines skip a drug
whose use starts in the future, and an entry with no start at all would be skipped as well — so a
`MedicationStatement` that says nothing about dates is treated as in use as of now, which is what
`status: active` already asserts. The consequence is the other way round: an `effectivePeriod` that
**ended** says the patient is not taking the medication, and that entry is not weighed. Send the
period you mean.

**Send a weight for any patient whose dose depends on it, and a height with it.** Dose bands are
selected by weight and body surface before they are compared, so a weight-dependent band with no
weight is not a check that passes — it is a red signal saying it could not run: *"Geen
doseringscontrole: onbekend actueel gewicht"*. With one, the dose is evaluated: the same
prescription for the same child answers *"Geen doseringsaanpassing"* when the dose fits the band
and *"Pas de dosering aan"* when it does not.

Send them as ordinary `observation` parameters with LOINC `29463-7` and `8302-2` — a weight in
`kg`, a height in `cm` or `m`. Nothing else changes on your side; this interface adds the
NHG-coded form the dose check reads. Body surface is derived from the two upstream, so a height
matters for any drug dosed per m².

```jsonc
POST /fhir/surveillance/$check-medication
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

Allergies, contra-indications and lab results are omitted from the example for length; they are
identical to the session payloads.

### `$check-medication` output

A `Bundle` with `type: collection`, one `DetectedIssue` entry per signal, in the order the report
listed them. `Bundle.identifier` carries the report id the clinical-rules service assigned, and
`Bundle.timestamp` the moment it ran.

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
it is the answer to a question the prescriber's own dossier raised. Hiding it hides that answer.

**For an allergy signal, `implicated` is how you read the verdict.** Each allergy in the dossier
is weighed against each proposal, and the finding comes back either way: `low` **with no
`implicated`** means the allergy was checked and nothing matched, and `high` **naming a drug**
means it did. The rule's text reads "…geregistreerd voor het onderstaande middel" in both cases,
which is the upstream's wording and is only accurate in the second — so branch on `severity` and
`implicated`, not on the sentence.

**`code` carries no coding**, only text. The rule identifier is an identifier, and FHIR's own
`DetectedIssue` categories (drug interaction, duplicate therapy, and so on) are a classification
the upstream does not make; deriving one from a rule id would be this interface guessing. Route on
`identifier`, show `code.text`.

**No response profile is published**, and `meta.profile` is not asserted. The shape above is what
is described instead, until it has been reviewed against real reports. Read `severity`,
`code.text`, `detail` and `identifier`, treat everything else as additive, and do not route on
`meta.profile` — see [Profiles](#profiles).

### What the answer does not cover yet

Two gaps, and both of them are a rule that stays silent rather than an error you would notice.
They are listed here because a host has to decide what to tell a prescriber.

- **Rules that compare the prescribed daily dose against the defined daily dose cannot fire.**
  Computing a PDD means decoding the NHG Tabel 25 instruction, and this interface passes that string
  through undecoded (see [Extensions](#extensions)). The dose *bands* are checked, which is the
  larger half of dose control; the DDD ratio is not.
- **A partial answer is not distinguishable from a complete one.** If the rules engine answers and
  the classic checks fail, the report comes back with what ran. The upstream does not say which
  half is missing, so this interface cannot either. What it will never do is present nothing at all
  as an all-clear.

**And one thing to know about the credentials on this base**: they are carried upstream but not
adjudicated there, so unlike a session, a wrong practice id is not rejected with a 401 by the
application behind this endpoint. Treat the endpoint as one to reach from your server rather than
from a client you do not control, and talk to Digitalis about the deployment restrictions in front
of it.

### `$check-medication` errors

| Condition | Status |
| --- | --- |
| Missing or malformed Basic credentials | 401 |
| Body fails `fhirhub-SurveillanceInput`, or a G-Standaard code cannot be resolved | 400 |
| The check could not be run — upstream unreachable, refused, or answered without a report | 500 |

The 500s all say so in `diagnostics`, and all of them say that no conclusion may be drawn about the
patient's medication. There is no status in this contract that means "the check partly ran".

## Lab determinations

Lab values are coded in **LOINC** and in nothing else, and the accepted codes are a closed list.
Both halves come from the G-Standaard rather than from us: it publishes, per medisch-farmaceutische
beslisregel parameter, the LOINC codes that count as that parameter, and the rules engine tests the
code you send — nothing is translated on the way through.

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

**A determination outside the list is a 400, not a silent no-op.** The rules can test twelve patient
measurements in total and current rules use four; anything else a host holds changes no decision. A
prescriber who supplied a lab result and saw no warning would read that as an all-clear, so the
request is refused instead — the same reason an unresolvable drug code fails the whole session.

**One eGFR code.** Dutch laboratories report CKD-EPI, so `62238-1` is the code to send. The
G-Standaard also lists `77147-7` (MDRD) and `50210-4` (cystatin C) for the same parameter and this
interface does not accept either; if your source reports one of them, raise it with Digitalis rather
than re-labelling the value, because the formulas do not give the same number.

**The unit is checked against the code.** `valueQuantity` must carry `system:
"http://unitsofmeasure.org"` and the `code` in the table; anything else is a 400. The number is
evaluated in the unit the rule was written in, so a kalium in mg/dL rather than mmol/L is a
different answer rather than a rounded one, and nothing downstream could notice. Note that an eGFR
must arrive as `mL/min/{1.73_m2}`, its own unit, rather than as `mL/min` — the G-Standaard compares
it against ml/min thresholds unchanged, and the payload should at least be unambiguous about which
quantity it carries. Where a conversion is exact it is done for you: a length in `m` is forwarded in
centimetres.

**Leave `display` out unless it is LOINC's own term.** It is not ignored — a `display` you send
becomes the caption Prescriptor shows the prescriber — but a display that disagrees with LOINC is a
hard validation error rather than a warning, because unlike the G-Standaard tables LOINC *is*
distributed with the validator, and a helpful local label is exactly the kind of thing it rejects.
Omitting it costs nothing: this interface then supplies its own caption, which for `62238-1` is
`eGFR volgens CKD-EPI`. So send the code alone, or LOINC's published term for it verbatim.

**Dates matter as much as values.** The rules test both — *is the ClCr older than 13 months*, *is
the kaliumspiegel older than 72 hours*, *is de INR max. 24 uur oud* — so `effectiveDateTime` is
required, and it must be when the sample was taken rather than when the report was released. A
value older than the rule's window counts as absent.

**Send the time of day when you have it.** `effectiveDateTime` may be a date alone, and a date alone
is forwarded as a date. But the time is used when it is there, and the paragraph below is why it is
worth sending: it is what orders two results from one day.

**Only the most recent result for a determination is used.** Send two eGFRs and the rules engine
tests the later one and ignores the earlier. It is not an average, and the older value is not tested
separately against the same rule — so a result you send is not necessarily a result that was weighed.
Two consequences worth designing around:

- **Same-day results need a time to be ordered.** Two results whose `effectiveDateTime` states only
  a date are equally recent as far as the engine is concerned, and it resolves that tie by taking
  the one that appears **first** in the request. Sending this morning's value before this
  afternoon's would then let the morning value decide. With a time on both, the later one wins as
  you would expect.
- **Weight and height are the exception, and they do not go by date at all.** They are read by dose
  checking rather than by the rules, and that reads the **first** `29463-7` and the first `8302-2`
  in the request whatever their dates say. Send one of each, and send the current one.

If you hold a history, send the determination you want weighed rather than the series. Nothing is
gained by sending more: the extra values change no decision, and they cost you the certainty of
knowing which one did.

**Sending several.** One determination per `observation` parameter, repeated as needed — subject to
the paragraph above. `component` is not read — resolve a multi-component result to one number first.
`interpretation`, `referenceRange`, `method` and `note` are ignored: this is an input to a decision,
not a lab report.

## Profiles

Every payload in this document has a `StructureDefinition` you can validate against. They are
published as an implementation guide with the canonical `http://spec.digitalis.nl/fhir`:

| Payload | Profile |
| --- | --- |
| `$formulary-session` request | `fhirhub-FormularySessionInput` |
| `$createrx-session` request | `fhirhub-CreateRxSessionInput` |
| Session response | `fhirhub-SessionOutput` |
| `$session-result` response | `fhirhub-ResultBundle` |

The input profiles slice `Parameters.parameter` by name and point each slice at a resource
profile, so validating the request body checks the resources inside it in one pass. **The slicing
is closed**: a parameter name this interface does not define is an error. That is deliberate. The
mapping layer behind the validator reads the parameters it knows and ignores the rest, so
`medicationstatement` for `medicationStatement` would otherwise open a session against a silently
thinner medication list — the same class of false negative that makes an unresolvable drug code a
400 rather than a warning. The closed slice turns the typo into a rejected request.

Both session requests, the session response and the result Bundle in this document are also
instances in the IG, and each is validated against the profile it claims on every build — so the
documentation cannot drift away from the profiles.

**These profiles are enforced.** A request body is validated against its profile before anything
else happens — before the G-Standaard lookup and before the call upstream — and a payload that
does not conform is a 400 whose `OperationOutcome` carries one issue per error, each with the
element it failed on. You therefore get every problem in one response rather than one per round
trip.

Only errors reject. Warnings are normal here and are not failures: the G-Standaard code systems
cannot be expanded, so every G-Standaard coding produces a "could not be validated" note.

### Elements FHIR requires that fhir-hub does not read

Base R4 makes several elements mandatory that this interface never looks at. A profile can only
constrain, never relax, so a **conformant payload must carry them** even though sending them
changes nothing:

| Resource | Element | Note |
| --- | --- | --- |
| `AllergyIntolerance` | `patient`, `clinicalStatus` | `clinicalStatus` is required by invariant `ait-1` |
| `Condition` | `subject` | |
| `MedicationStatement` | `status`, `subject` | |
| `Observation` | `status` | |
| `MedicationRequest` (`prescription`) | `status`, `intent`, `subject` | `$createrx-session` only |

The patient travels as a sibling parameter rather than a contained resource, so there is nothing
for `patient` and `subject` to reference. Send a `data-absent-reason` of `unknown`, as the
examples above do — the same idiom `$session-result` uses for `MedicationRequest.subject` on the
way out. The mapping layer itself would accept a payload without these; it is the profiles, and
base FHIR underneath them, that require them — and since validation runs before mapping, they are
required in practice and not only on paper.

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

**Copy these strings exactly, and send nothing else.** There is one accepted `system` per table,
and there is no lenient form: in particular the bare code-system token — `PRK`, `HPK`, `SSK`,
`SNK`, `OGGrp`, `CICode`, `ICPC` — is what the JSON API carries in a field of its own, and it is
not a FHIR `system`. Sending one, or any other URI for the same table, is a 400 on the element it
was on:

```
None of the codings provided are in the value set 'ICPC-1 NL'
(http://spec.digitalis.nl/fhir/ValueSet/icpc-1-nl|0.3.0), and a coding from this value set is
required) (codes = ICPC#A01)
```

A rejection lists the `system` URIs the element accepts, so the error tells you what to send:

```
AllergyIntolerance.code has no coding in a system this interface routes; expected one of
[urn:oid:2.16.840.1.113883.2.4.4.1.725, urn:oid:2.16.840.1.113883.2.4.4.1.750,
urn:oid:2.16.840.1.113883.2.4.4.1.902.122]
```

The G-Standaard tables are licensed and are not distributed with the profiles, so what is checked
is the `system`, not the code: a coding from one of the systems above passes with a warning that its
code could not be verified, and a coding from any other system is an error. The one code shape that
*is* checked is ICPC-1 NL, by an invariant rather than by expansion.

## Extensions

Two, both Digitalis-defined because no national artifact covers them. Both canonical URLs below
dereference: a browser gets the definition page, and `Accept: application/fhir+json` gets the
`StructureDefinition` itself.

**`ext-Dosage.CodedDirections`** —
`http://spec.digitalis.nl/fhir/StructureDefinition/ext-Dosage.CodedDirections`, `valueString`. The NHG
Tabel 25 coded instruction, e.g. `"3-4D1S; gedurende max. 1 maand"`.

This is the dosing instruction **in both directions**. Store it and hand it back unchanged unless
you mean to change the dose; `Dosage.text` beside it is the human-readable form, for display only.
If you do not parse Tabel 25, treat the string as opaque and pass it through — a pharmacy system
downstream reads it natively.

No `timing` or `doseAndRate` is produced, and any you send is **ignored**. To change a dose, edit
this extension; editing `timing` has no effect.

**`ext-MedicationRequest.OpiumActClassification`** —
`http://spec.digitalis.nl/fhir/StructureDefinition/ext-MedicationRequest.OpiumActClassification`,
`valueCodeableConcept`. A G-Standaard bijzonder kenmerk, present only when the product falls under
the Opiumwet.

Only code `2` ("Product valt onder Opiumwet in volle omvang") is emitted today. Read the code rather
than treating the extension as a boolean flag: further codes (`65`, `107`) carry different handling
rules for a pharmacist and may appear later without a breaking change, so switch on the code and
have a default branch.

## Errors

Every error is an `OperationOutcome`, the 401s included, so one error path in your client handles
all of them. No response in this API is un-parseable by a FHIR library.

| Condition | Status |
| --- | --- |
| Missing or malformed Basic credentials | 401 |
| Organization id or key rejected by Prescriptor (`Invalid organization ID or key`) | 401 |
| Unknown or already-consumed session id (`No data found for the session ID`) | 401 |
| Invalid request | 400 |
| Prescriptor unreachable (`Could not reach Prescriptor`) or unparseable | 500 |
| Medication surveillance unreachable, refused, or answering without a report | 500 |

A 400 comes from one of three places. Knowing which saves time when you read `diagnostics`, and
each is quoted as returned so you can recognise it while building.

**The profile**, which is where most of them come from. The body is validated before anything else
happens, and the `OperationOutcome` carries one issue per error with the element it failed on — so
you get every problem in one response rather than one per round trip. The wording is the FHIR
validator's:

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
    "diagnostics": "Slice 'Parameters.parameter:endSessionUrl': a matching slice is required, but not found (from http://spec.digitalis.nl/fhir/StructureDefinition/fhirhub-FormularySessionInput|0.2.0). Note that other slices are allowed in addition to this required slice",
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

If you are replacing a JSON API integration, the payloads change shape but the operations, the
semantics and the status codes do not. Three differences go beyond syntax.

- **Credentials move to the HTTP layer.** The organization id and key leave the request body and
  become an HTTP Basic header. The values are unchanged.
- **Drug codes in `medicationStatement` are resolved before the session opens.** Each PRK or HPK is
  looked up in the G-Standaard and forwarded as a PRK + GPK (+ HPK) triple. A code that cannot be
  resolved fails the request with a 400 — see *Medication surveillance fails closed* above. The JSON
  API forwards current medication at the level you supply it without that lookup, so a code that is
  accepted there can be rejected here. Check your codes against a current G-Standaard before
  switching over.
- **The code system stops being a separate field and becomes the `system` on the coding.** Where the
  JSON API took a code alongside a `PRK`, `SSK` or `ICPC` token, here the token is replaced by the
  URI from [Code systems](#code-systems). The tokens themselves are not accepted as a `system`.

Everything else is answered by the endpoint sections above, and they are the place to answer it
from. This interface is specified in its own terms rather than as a translation of the JSON API, so
read the operation you are calling and the profile it names, and build against those — porting your
existing payloads field by field will carry across assumptions the JSON API allowed and this one
does not.

## Current limitations

Things you may expect to be able to do, and cannot yet. Each is a gap in the published artifacts,
not in the running service.

- **No `meta.profile` is asserted** on any resource, so do not filter or route on it. Validate
  against the profile URLs above explicitly instead. The IG's example instances do carry one,
  because the publishing tool adds it; a live payload does not.
- **nl-core is not derived from**, so do not expect these resources to satisfy nl-core. Three of
  the five are blocked on Nictiz rather than on effort: `nl-core-MedicationContraIndication`
  profiles `Flag` rather than `Condition`, `nl-core-LaboratoryTestResult` requires an
  `Observation.category` this interface neither sends nor reads, and `nl-core-MedicationUse2` is
  not published in the nl-core package. Ask Digitalis before building anything that depends on
  nl-core conformance.
- **The artifacts are `draft`, at version `0.3.0`.** The change policy is published, and while the
  status is `draft` it allows a breaking change at a minor version — see *Versioning and change
  policy* in the published guide. Agree with Digitalis how you want to be told about a change
  before you go live.
- **The G-Standaard code systems are not distributed**, so no validator can check a G-Standaard
  *code* — only the `system` it came from. A wrong code inside a system this interface routes
  reaches Prescriptor, and comes back as a 400 from the medication lookup rather than as a
  validation error.

- **The medication-surveillance response is `experimental` and has no profile.** The request
  contract has been enforced since 0.2.0 and is stable; the answer is newer, and the severity
  mapping and the way a rule's text arrives may still move. Three classes of rule cannot fire yet —
  the PDD-against-DDD dose comparison, anything needing a weight, and nothing tells a partial
  answer from a complete one. See
  [`POST /fhir/surveillance/$check-medication`](#post-fhirsurveillancecheck-medication).

Questions, or a case this document does not cover: contact Digitalis.
