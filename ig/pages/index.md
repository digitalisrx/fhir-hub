<!-- GUIDE-PREAMBLE -->

### Prescriptor

Prescribing in Prescriptor's own interface: your system opens a session, hands the browser over,
and collects the prescriptions and patient advice. Surveillance runs inside the session, and the
care provider sees the signals in Prescriptor.

```
POST   /fhir/evs/$formulary-session        Parameters  ->  Parameters (sessionId, url)
POST   /fhir/evs/$createrx-session         Parameters  ->  Parameters (sessionId, url)
GET    /fhir/evs/$session-result?session=  ->  Bundle (MedicationRequest, Communication)
GET    /fhir/evs/metadata                  ->  CapabilityStatement (unauthenticated)
```

### Surveillance

Medication surveillance on its own: patient context and medication in, the signals that fire out.
No interface, no session, no browser round trip.

```
POST   /fhir/surveillance/$check-medication-request     Parameters  ->  Bundle (DetectedIssue)
POST   /fhir/surveillance/$check-medication-statement   Parameters  ->  Bundle (DetectedIssue)
GET    /fhir/surveillance/metadata                      ->  CapabilityStatement (unauthenticated)
```

One call answers both halves of Dutch medication surveillance: the G-Standaard's
medisch-farmaceutische beslisregels, and the classic allergy, age, duplicate-medication and dose
checks.

**Request and response both have a profile**, so the response shape is described by
`fhirhub-SurveillanceBundle` rather than by prose. **An empty `Bundle` means the check ran and
nothing fired** — every way for it not to run is a 500, never a 200 with no findings. Two classes
of rule cannot fire yet.

Neither base has a resource REST API or search.

### Where to start

| If you are | Start at |
| --- | --- |
| Integrating with Prescriptor for the first time | [Prescriptor](prescriptor.html), then [The Prescriptor flow](flow.html) and [Authentication](authentication.html) |
| Looking at medication surveillance | [Surveillance](surveillance.html) — and read what the answer does not cover yet |
| Replacing a JSON API integration | [Moving from the JSON API](migration.html) |
| Building a request | the operation pages, then [Code systems](code-systems.html) |
| Wiring up a validator | [Downloads and validation](downloads.html) |
| Deciding what you can rely on | [Behaviour to design around](design-notes.html) and [Current limitations](limitations.html) |

### The machine-readable half

Every payload has a `StructureDefinition`, and a request body is validated against its profile
*before* anything else happens — so what is written here is what the service enforces. The
[Artifacts](artifacts.html) page indexes all of them; these are the ones you validate against:

| | Payload | Profile |
| --- | --- | --- |
| Prescriptor | `$formulary-session` request | [fhirhub-FormularySessionInput](StructureDefinition-fhirhub-FormularySessionInput.html) |
| Prescriptor | `$createrx-session` request | [fhirhub-CreateRxSessionInput](StructureDefinition-fhirhub-CreateRxSessionInput.html) |
| Prescriptor | session response | [fhirhub-SessionOutput](StructureDefinition-fhirhub-SessionOutput.html) |
| Prescriptor | `$session-result` response | [fhirhub-ResultBundle](StructureDefinition-fhirhub-ResultBundle.html) |
| Surveillance | `$check-medication-request` input | [fhirhub-SurveillanceInput](StructureDefinition-fhirhub-SurveillanceInput.html) |
| Surveillance | `$check-medication-statement` input | [fhirhub-SurveillanceStatementInput](StructureDefinition-fhirhub-SurveillanceStatementInput.html) — the dossier check. Defines no `prescription` parameter, and the slicing is closed |
| Surveillance | response, both operations | [fhirhub-SurveillanceBundle](StructureDefinition-fhirhub-SurveillanceBundle.html), whose entries are [fhirhub-SurveillanceFinding](StructureDefinition-fhirhub-SurveillanceFinding.html) |

The resource profiles inside those payloads — patient, current medication, allergies,
contra-indications, lab results — are shared by both applications and live on the
[Profiles](profiles.html) page. The examples are the payloads from the prose, validated against
their profile on every build, so an example cannot contradict the profile or the service.

### Status

**Work in progress.** Nothing is published at the canonical yet, no integrator is in production,
and **every part of this specification may change without notice** — payloads, profiles, operation
names, base paths. Every artifact is `draft`.

[Versioning and change policy](versioning.html) takes effect with the first published release.
[Current limitations](limitations.html) lists what you may expect to be able to do and cannot yet.

Questions, or a case this guide does not cover: contact Digitalis.
