Every release of this guide, newest first, each naming its version, publication date and changes.
What a version number promises is in [Versioning and change policy](versioning.html); a release with
no **Breaking** heading broke nothing.

### 0.4.0 — current

`draft`, 2026-09-07. In development: nothing has been published at the canonical yet, so nothing
here is reported as changed — and every part of it may still change without notice.

**Prescriptor**, on `/fhir/evs`:

- `$formulary-session`, `$createrx-session` and `$session-result`, with the input and output
  specification for each
- profiles for the two session requests, the session response, the `$session-result` Bundle and the
  resources a host sends in or receives back
- 2 Digitalis extensions, `ext-Dosage.CodedDirections` and
  `ext-MedicationRequest.OpiumActClassification`
- 1 Digitalis code system, the Opiumwet subset of G-Standaard `BST401T`, and the value sets binding
  the national systems this interface routes

**Surveillance**, on `/fhir/surveillance`:

- `$check-medication-request` — proposed prescriptions weighed against a patient's context.
  `prescription` is `1..*`, because a request with nothing under test has no answer to give
- `$check-medication-statement` — a patient's current medication checked against itself and their
  context, with **every `medicationStatement` under test**. It accepts no `prescription`, and one
  sent there is a 400 rather than an element quietly dropped
- both answer a `Bundle` of `DetectedIssue`, one per signal, with `severity`, `code.text`, the
  rule's full `detail`, the `evidence` a rule read, `implicated` pointing back at the records you
  sent by the `id` you gave them, and the rule's own `identifier`. `Bundle.identifier` carries the
  report id, which is what Digitalis support asks for
- the response has a profile — `fhirhub-SurveillanceBundle`, whose entries are
  `fhirhub-SurveillanceFinding` — so you can validate what you receive. The severity mapping and
  the way a rule's text arrives are the parts most likely to move; `code.coding` is `0..0` and the
  rule text is plain, both deliberately, and widening either is additive
- **an empty `Bundle` means the check ran and nothing fired.** Every way for the check not to
  happen is a 500, never a 200 with no findings

`implicated` names a `MedicationRequest` from the first operation and a `MedicationStatement` from
the second, because the upstream cannot tell the two apart. Set an `id` on the resources you send,
or you get a positional identifier that is stable only within the request.

**Shared by both bases**: the credentials, content types, error shape, the resource profiles for
patient, current medication, allergies, contra-indications and lab results, the G-Standaard code
systems, the LOINC lab determinations with their units, the 400 on a drug code the G-Standaard
cannot resolve, and one release number.

Known gaps are under [Current limitations](limitations.html). The one worth repeating: the
artifacts are `draft`, so a breaking change can still arrive at a minor version.

