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

Start at [Prescriptor](prescriptor.html), then [The Prescriptor flow](flow.html) for how the three
calls fit together.

### Surveillance — *the response is a preview*

Medication surveillance on its own: patient context and one or more proposed prescriptions in, the
signals that fire out. No user interface, no session, no browser round trip.

```
POST   /fhir/surveillance/$check-medication-request     Parameters  ->  Bundle (DetectedIssue)
POST   /fhir/surveillance/$check-medication-statement   Parameters  ->  Bundle (DetectedIssue)
GET    /fhir/surveillance/metadata                      ->  CapabilityStatement (unauthenticated)
```

Both halves of Dutch medication surveillance answer this one call: the G-Standaard's
medisch-farmaceutische beslisregels, and the classic allergy, age, duplicate-medication and dose
checks.

**The request contract is stable; the response is `experimental`.** What you send has been
published and enforced since 0.2.0 and did not change when the check went live at 0.3.0. The answer
is newer, no response profile is published for it, and there are three classes of rule it cannot
fire yet. **An empty `Bundle` means the check ran and nothing fired** — every way for it not to run
is a 500, never a 200 with no findings.

Start at [Surveillance](surveillance.html).

### What the two share

Two contracts, two FHIR bases, one interface. The credentials, the content types, the error shape,
the payload profiles for patient, current medication, allergies, contra-indications and lab
results, the code systems and the release number are the same for both — so a system that already
opens Prescriptor sessions has no new payload to learn, only a new address to post to. There is no
resource REST API and no search on either base.

See [Conventions](conventions.html) for content types, formats and how a malformed request is
reported, and [Authentication](authentication.html) for the credentials.

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

Every payload has a `StructureDefinition`, and they are not decoration: a request body is
validated against its profile *before* anything else happens, so what is written here is what
the service enforces. The [Artifacts](artifacts.html) page indexes all of them; the ones you will
validate against directly are:

| | Payload | Profile |
| --- | --- | --- |
| Prescriptor | `$formulary-session` request | [fhirhub-FormularySessionInput](StructureDefinition-fhirhub-FormularySessionInput.html) |
| Prescriptor | `$createrx-session` request | [fhirhub-CreateRxSessionInput](StructureDefinition-fhirhub-CreateRxSessionInput.html) |
| Prescriptor | session response | [fhirhub-SessionOutput](StructureDefinition-fhirhub-SessionOutput.html) |
| Prescriptor | `$session-result` response | [fhirhub-ResultBundle](StructureDefinition-fhirhub-ResultBundle.html) |
| Surveillance | `$check-medication-request` input | [fhirhub-SurveillanceInput](StructureDefinition-fhirhub-SurveillanceInput.html) — `experimental`, because the response shape is not final. There is no profile for the response |
| Surveillance | `$check-medication-statement` input | [fhirhub-SurveillanceStatementInput](StructureDefinition-fhirhub-SurveillanceStatementInput.html) — the dossier check. Defines no `prescription` parameter, and the slicing is closed |

The resource profiles inside those payloads — patient, current medication, allergies,
contra-indications, lab results — are shared by both applications, and are on the
[Profiles](profiles.html) page.

The example instances on those pages are the same payloads that appear in the prose, and each
one is validated against the profile it claims on every build of this guide — so an example
here cannot contradict either the profile or the running service.

### Status

This is release **0.3.0**, `draft`. Read [Versioning and change policy](versioning.html) before
you go live: while the guide is `draft`, a breaking change can still arrive at a minor version,
and the policy says how you will be told. [Current limitations](limitations.html) lists what you
may expect to be able to do and cannot yet.

### Getting in touch

Questions, or a case this guide does not cover: contact Digitalis.
