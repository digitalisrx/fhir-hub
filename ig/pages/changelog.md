Every release of this guide, newest first. What a version number promises is in
[Versioning and change policy](versioning.html).

Each entry names the version, the date it was published, and every change grouped by whether it
can affect a payload you already send. A release with no **Breaking** heading broke nothing.

### 0.4.0 — the surveillance operation is renamed, split in two, and takes a required subject

`draft`, 2026-09-07. **Two breaking changes and one addition on the surveillance contract**, all of
them about the request rather than the answer. Nothing on the EVS contract changed.

**Breaking: `$check-medication` is now `$check-medication-request`.** The operation on the
surveillance base has been renamed. Taken on its own this is a change of address rather than of
payload — the parameter names are the same and the response is the same `Bundle` of
`DetectedIssue` — so for the rename, change the URL you post to and nothing else:

```
POST /fhir/surveillance/$check-medication-request
```

**The old name is not served.** A post to `/fhir/surveillance/$check-medication` answers **400**
with an `OperationOutcome` naming the operation the deployment does not know — not a 404, because
the base itself is still there, and not a redirect. There is no transition period in which both
names answer. Nothing in a profile records an operation name, so no validator can warn you and the
version number cannot carry the news; this heading and a direct word from Digitalis are the
announcement. See [Versioning and change policy](versioning.html) under *The base URL is outside
this table*, which is the clause this falls under.

**Breaking: `prescription` is now required.** It was `0..*` and is `1..*`, so at least one
`MedicationRequest` has to be in the request:

| | 0.3.0 | 0.4.0 |
| --- | --- | --- |
| `prescription` | `0..*` | **`1..*`** |
| `medicationStatement` | `0..*` | `0..*`, unchanged |

**What this rejects that used to be accepted** is a request carrying only `medicationStatement` —
current medication with nothing proposed, which up to 0.3.0 was checked for interactions among the
patient's standing dossier. That shape is a **400** now, naming `prescription` as the element at
fault. If you rely on it, say so before you move to 0.4.0: the capability is a decision to reverse
in a later release rather than something to work around by sending a placeholder prescription.

The invariant `fhirhub-something-to-check`, which required *either* a prescription or a
current-medication entry, **is removed** rather than kept: a mandatory `prescription` satisfies it
on every request that validates at all, and its own description — "or at least one current
medication entry" — would have been false. If your error handling matches on that invariant's key,
a missing `prescription` now reports as an ordinary cardinality error on
`Parameters.parameter:prescription` instead.

**New: `POST /fhir/surveillance/$check-medication-statement`.** The dossier question, as an
operation of its own: a patient's current medication checked against itself and their context, with
**every `medicationStatement` under test** rather than serving as the background to a proposal. It
is what the shape withdrawn above was reaching for, and it answers it properly.

| | `$check-medication-request` | `$check-medication-statement` |
| --- | --- | --- |
| `prescription` | `1..*`, and it is the subject | **not accepted** — a 400 |
| `medicationStatement` | `0..*`, the context | `1..*`, and every entry is a subject |
| Request profile | `fhirhub-SurveillanceInput` | `fhirhub-SurveillanceStatementInput` |
| `implicated` names | `MedicationRequest` | `MedicationStatement` |
| Response | `Bundle` of `DetectedIssue` | identical |

**What it surfaces that the other operation cannot** is every signal where nothing is being
prescribed: an allergy or contra-indication recorded after the medication was started, a dose that
no longer fits a nierfunctie that has since dropped, a duplicate between two drugs of which neither
is new, an age band the patient has since crossed. A periodic medication review asks exactly this
and cannot phrase it as a proposal without inventing one.

**It takes no `prescription`, and one sent there is refused** rather than ignored — the slicing is
closed, so the `OperationOutcome` says the parameter does not match any known slice. A request whose
proposed drug was silently dropped would be answered for the patient's existing medication alone,
which is a real answer to a question the host did not ask.

**Read `implicated` per operation.** The upstream cannot tell the two apart — this interface marks
the drugs under test the same way whichever operation asked — so the reference type is decided here:
`MedicationStatement` from the dossier check, `MedicationRequest` from the other. Both carry your own
resource `id` as the identifier, so set one.

Everything else is shared and unchanged: the same base, the same credentials, the same resource
profiles on every parameter, the same G-Standaard code systems and LOINC determinations, the same
400 on an unresolvable drug code, and the same rule that an empty `Bundle` means the check ran and
nothing fired. See
[`POST /fhir/surveillance/$check-medication-statement`](check-medication-statement.html).

**Why breaking changes at a minor.** The status is still `draft`, where the policy allows them, and
both are worth making before the first integrator goes to production rather than after. The name
now says what the operation takes — a request to be checked — and distinguishes it from the
question a future operation might ask about medication already dispensed. And a required
`prescription` makes the operation's subject explicit in the profile rather than in prose, which is
the difference between a validator catching an empty check and a prescriber reading one as an
all-clear.

**Everything else about the contract is unchanged**, including the base (`/fhir/surveillance`), the
credentials, the canonical of the request profile `fhirhub-SurveillanceInput`, every parameter name,
every resource profile the parameters bind, and the response shape. The only element of the request
profile that moved is the one named above. The generated `OperationDefinition` moves with the operation, to
`/fhir/surveillance/OperationDefinition/-s-check-medication-request`, and
`GET /fhir/surveillance/metadata` now lists both `check-medication-request` and
`check-medication-statement`. As always, read the release out
of `metadata` before you switch: a deployment still on 0.3.0 answers only the old name.

### 0.3.0 — medication surveillance answers

`draft`, 2026-09-07. **Nothing breaks.** Every request accepted at 0.2.0 is accepted unchanged and
the request profile did not move a single element. What changed is the answer:
`POST /fhir/surveillance/$check-medication` no longer returns 501.

**The check is real.** A conformant request is now weighed by both halves of Dutch medication
surveillance and the signals come back in the response:

- the G-Standaard's **medisch-farmaceutische beslisregels**, through the clinical-rules engine —
  interactions, contra-indications, nierfunctie, and the rest
- the **classic G-Standaard checks** — allergy, age as a contra-indication, duplicate medication,
  and dose control against the dose bands

**What you get back** is a `Bundle` (`type: collection`) of `DetectedIssue`, one per signal, in the
order the report listed them. Per finding: `severity` (`high`, `moderate`, `low` for the rules
engine's red, orange and green), `code.text` — the rule's own title — `detail` with the rule's full
text, `evidence` with the codes and lab values the rule read, `implicated` pointing back at the
records you sent by the `id` you gave them, and `identifier` carrying the rule's own id.
`Bundle.identifier` carries the report id, which is what Digitalis support asks for. See
[`POST /fhir/surveillance/$check-medication`](check-medication-request.html) for the whole shape and for the
three things it does not cover yet.

**An empty Bundle means the check ran and nothing fired.** Nothing else can produce one: every way
for the check not to happen — an unreachable upstream, a refusal, a report that did not come back —
is a 500 with an `OperationOutcome`, never a 200 with no findings. That is the same rule that makes
an unresolvable drug code a 400 rather than a dropped drug, and it is why this endpoint spent a
release answering 501 rather than "no issues found".

**Weight and height now reach dose control.** Send them as `observation` parameters (LOINC
`29463-7` in `kg`, `8302-2` in `cm` or `m`) and a weight-dependent dose band is evaluated instead
of refused. Without one, dose control answers *"Geen doseringscontrole: onbekend actueel gewicht"*
— a red signal saying it could not run, which is not the same thing as a dose that passed. Nothing
changes in what you send; the NHG-coded form the dose check reads is added on the way out.

**A complete request example is published**, alongside the short one in the prose: *"$check-medication
request, the reference case"*, which is the FHIR form of the request Digitalis documents as
`example-1-req.xml` and produces the signals in that document's response. Every `id` in it is the
`UID` from that document, so the two can be read side by side.

**Set `id` on the resources you send.** It comes back on `DetectedIssue.implicated` as the
identifier of the record a signal is about, which is what lets you show the signal against the right
row instead of matching on codes. Without one you get a positional identifier that is stable only
within the request.

**Still `experimental`, and now that is about the response.** The request profile has been enforced
since 0.2.0 and is unchanged; the response has existed for days. The severity mapping and how a
rule's text arrives are the parts that may still move, and there is deliberately **still no response
profile** — the shape is described in the guide until it has been reviewed against real reports.

**The version number moved for a contract you may not use.** One release covers both bases — see
[Versioning and change policy](versioning.html) — so an EVS-only integrator reads 0.3.0 in
`GET /fhir/evs/metadata` and has nothing to do about it. Nothing on the EVS contract changed.

### 0.2.0 — medication surveillance, published and not implemented

`draft`, 2026-09-02. **Nothing breaks.** Every request accepted at 0.1.0 is accepted unchanged, and
no response gained an element. Additive, on a FHIR base that did not exist before.

**A second contract, on a second base.** `POST /fhir/surveillance/$check-medication` asks the
medication-surveillance question directly — a patient's context and one or more proposed
prescriptions in, the signals that fire out — without a session or a browser round trip.

**It is not implemented.** A conformant request is answered with **501 Not Implemented** and an
`OperationOutcome` whose `issue.code` is `not-supported`. Nothing about a patient's medication may
be concluded from that response, and no release of your product should depend on this endpoint yet.
It is published because the request contract is worth reviewing and building against before the
check behind it exists: a malformed body is a 400 naming what is wrong with it, exactly as it will
be when the check goes live.

What this release adds:

- 1 profile, `fhirhub-SurveillanceInput`, marked `experimental` — the request body of
  `$check-medication`. It reuses the resource profiles of the session contract unchanged, so a host
  that already opens sessions has no new payload to shape, only a new address to post to
- 1 invariant, `fhirhub-something-to-check`: at least one `prescription` or one
  `medicationStatement`, because a request carrying neither has nothing to evaluate
- 1 example, `$check-medication request`, validated against its profile like every other
- A second FHIR base, `/fhir/surveillance`, with its own unauthenticated
  `GET /fhir/surveillance/metadata` and its own generated `OperationDefinition`. The credentials,
  the content types, the error shape and the version number are the same as on `/fhir/evs`

**The guide is now organised by application.** `fhir-hub` was documented as "the interface to
Prescriptor 3", which stopped being true with this release: it is one interface in front of two
distinct applications. [Prescriptor](prescriptor.html) and [Surveillance](surveillance.html) each
have a page saying what they are, which base they answer on and how they differ, and the menu has
a group for each. Nothing about the Prescriptor payloads moved — the operation pages, the profiles
and the examples are where they were — but the page that used to be *Global flow* is now
[The Prescriptor flow](flow.html), because that is whose flow it is.

**No response profile is published.** `DetectedIssue` is where it is heading; the severity grades,
how a rule's own text and action come across, and whether a partial answer is ever permitted are
all open. A profile with nothing behind it would be a promise this interface cannot keep.

**The version number moved for a change to a contract you may not use.** One release covers both
bases — see [Versioning and change policy](versioning.html) — so an EVS-only integrator reads 0.2.0
in `GET /fhir/evs/metadata` and has nothing to do about it. Nothing on the EVS contract changed in
this release.

### 0.1.0 — first release

`draft`, 2026-08-28. Nothing preceded it, so nothing is reported as changed.

What it contains:

- The three operations — `$formulary-session`, `$createrx-session`, `$session-result` — with the
  input and output specification for each
- 13 profiles: the two session requests, the session response, the `$session-result` Bundle, and
  the resources a host sends in or receives back
- 2 Digitalis extensions: `ext-Dosage.CodedDirections` and
  `ext-MedicationRequest.OpiumActClassification`
- 1 Digitalis code system, the Opiumwet subset of G-Standaard `BST401T`, and 9 value sets binding
  the national systems this interface routes
- 4 example payloads, each validated against the profile it claims

Known gaps at this release are listed under [Current limitations](limitations.html). The one worth
repeating here: the artifacts are `draft`, so a breaking change can still arrive at a minor
version.
