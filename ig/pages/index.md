<!-- GUIDE-PREAMBLE -->

### Prescriptor

Prescribing, in Prescriptor's own user interface. Your system opens a session, hands the browser
over, and collects the prescriptions and patient advice that came out. Medication surveillance runs
inside the session, and the care provider sees the signals in Prescriptor.

```
POST   /fhir/evs/$formulary-session        Parameters  ->  Parameters (sessionId, url)
POST   /fhir/evs/$createrx-session         Parameters  ->  Parameters (sessionId, url)
GET    /fhir/evs/$session-result?session=  ->  Bundle (MedicationRequest, Communication)
GET    /fhir/evs/metadata                  ->  CapabilityStatement (unauthenticated)
```

### Surveillance

Medication surveillance on its own: patient context and medication in, the signals that fire out.
No user interface, no session, no browser round trip.

```
POST   /fhir/surveillance/$check-medication-request     Parameters  ->  Bundle (DetectedIssue)
POST   /fhir/surveillance/$check-medication-statement   Parameters  ->  Bundle (DetectedIssue)
GET    /fhir/surveillance/metadata                      ->  CapabilityStatement (unauthenticated)
```

Both halves of Dutch medication surveillance answer this one call: the G-Standaard's
medisch-farmaceutische beslisregels, and the classic allergy, age, duplicate-medication and dose
checks.

**Request and response both have a profile**, so the response shape is described by
`fhirhub-SurveillanceBundle` rather than by prose. There are two classes of rule it cannot fire
yet. **An empty `Bundle` means the check ran and nothing fired** — every way for it not to run is a
500, never a 200 with no findings.

There is no resource REST API and no search on either base.

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

Every payload has a `StructureDefinition`, and they are not decoration: a request body is validated
against its profile *before* anything else happens, so what is written here is what the service
enforces. The [Artifacts](artifacts.html) page indexes all of them; these are the ones you validate
against directly:

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
contra-indications, lab results — are shared by both applications and are on the
[Profiles](profiles.html) page. The example instances are the same payloads that appear in the
prose, each validated against the profile it claims on every build, so an example here cannot
contradict either the profile or the running service.

### Status

**Work in progress.** Nothing is published at the canonical yet, no integrator is in production,
and **every part of this specification may change without notice** — the payloads, the profiles,
the operation names and the base paths included. Every artifact is `draft`, and none is singled out
as more or less provisional than the rest.

Read [Versioning and change policy](versioning.html) before you go live: it is the policy that
takes effect with the first published release, and it says how you will be told about a change.
[Current limitations](limitations.html) lists what you may expect to be able to do and cannot yet.

Questions, or a case this guide does not cover: contact Digitalis.
