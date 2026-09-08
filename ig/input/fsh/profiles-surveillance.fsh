// The medication-surveillance contract, served on its own FHIR base at /fhir/surveillance.
//
// Two operations in, one shape out. $check-medication-request weighs proposed prescriptions
// against a patient's dossier and $check-medication-statement weighs a dossier against itself;
// both map the request to a DigitalisRx document, send it to the Digitalis Hub, and return the
// clinical-rules report as a Bundle of DetectedIssue.
//
// The RESPONSE profile is at the bottom of this file. It describes what SurveillanceBundleMapper
// actually emits, element for element, rather than a shape someone would like it to have — so
// every rule in it is checked against a real report by
// OutboundPayloadConformanceTest.theSurveillanceBundleSatisfiesItsProfile. Read that mapper
// before changing a cardinality here: the two are the same fact written twice, and the profile is
// the half that integrators bind to.
//
// Every artifact here is `status = draft`, like everything else in this guide, and that is the
// only status signal it carries. No profile in this IG sets `experimental`: nothing is published
// at the canonical, no integrator is in production, and marking one contract provisional implied
// the rest was not. (The value sets and the code system in terminology.fsh do carry
// `experimental = false`, and that is packaging rather than a maturity claim — the publisher's
// ShareableValueSet and ShareableCodeSystem checks want the element present, and removing it
// costs eleven warnings.)
//
// Note what this file does NOT contain: profiles for Patient, MedicationStatement,
// AllergyIntolerance, Condition, Observation or the prescription. Those are the same resources
// the session operations take, so the same profiles bind here — which is the whole argument for
// the two contracts sharing a service rather than a copy of each other.

Profile: FhirHubSurveillanceInput
Parent: Parameters
Id: fhirhub-SurveillanceInput
Title: "$check-medication-request input"
Description: "The body of POST /fhir/surveillance/$check-medication-request: the patient's context plus the prescriptions to check against it. A conformant request is weighed by the G-Standaard's medisch-farmaceutische beslisregels and by the classic allergy, age, duplicate-medication and dose checks, and the signals come back as a Bundle of DetectedIssue."
* ^status = #draft
// Closed, for the same reason the session requests are: this interface would ignore a parameter
// it does not recognise, and a host that sends "medicationstatements" would be told there were
// no signals for a list it never managed to send. See profiles-parameters.fsh.
* parameter ^slicing.discriminator[0].type = #value
* parameter ^slicing.discriminator[0].path = "name"
* parameter ^slicing.rules = #closed
* parameter contains
    patient 1..1 and
    xisId 1..1 and
    xisVersion 1..1 and
    prescription 1..* and
    medicationStatement 0..* and
    allergyIntolerance 0..* and
    condition 0..* and
    observation 0..*
* parameter[patient].name = "patient" (exactly)
* parameter[patient].resource 1..1
* parameter[patient].resource only FhirHubPatient
* parameter[patient].value[x] 0..0
* parameter[patient].part 0..0
* parameter[xisId].name = "xisId" (exactly)
* parameter[xisId].value[x] 1..1
* parameter[xisId].value[x] only string
* parameter[xisId].value[x] obeys fhirhub-non-blank
* parameter[xisId].value[x] ^short = "Your system id; identifies your product, not the practice"
* parameter[xisId].value[x] ^comment = "The same value you send to the session operations, and required here for the same reason: a log line has to be attributable to a supplier and a release. Never forwarded upstream."
* parameter[xisId].resource 0..0
* parameter[xisId].part 0..0
* parameter[xisVersion].name = "xisVersion" (exactly)
* parameter[xisVersion].value[x] 1..1
* parameter[xisVersion].value[x] only string
* parameter[xisVersion].value[x] obeys fhirhub-non-blank
* parameter[xisVersion].resource 0..0
* parameter[xisVersion].part 0..0
* parameter[prescription].name = "prescription" (exactly)
* parameter[prescription].resource 1..1
* parameter[prescription].resource only FhirHubPrescriptionInput
* parameter[prescription].value[x] 0..0
* parameter[prescription].part 0..0
* parameter[prescription] ^short = "The prescriptions to check; at least one, repeated for more than one"
// Mandatory, and that is what this operation is for: something has to be under test.
// Until 0.4.0 a dossier-only request was accepted and checked for interactions among the
// medication a patient already takes, which the invariant fhirhub-something-to-check
// permitted; that invariant is gone rather than kept and unfireable. The reasoning it
// carried now lives here: surveillance over nothing reports no signals, and a host that
// built its parameter names wrong would read that as an all-clear. Same argument as the
// closed slicing above and the 400 on an unresolvable drug code.
* parameter[prescription] ^comment = "Required: at least one prescription is what this operation weighs. The same profile $createrx-session takes, so a prescription can be checked and then handed to a session without being reshaped. Repeatable here where the session accepts one, because a host may want a whole proposed regimen weighed at once — and because two proposed drugs can interact with each other and not with anything the patient already takes."
* parameter[medicationStatement].name = "medicationStatement" (exactly)
* parameter[medicationStatement].resource 1..1
* parameter[medicationStatement].resource only FhirHubMedicationStatement
* parameter[medicationStatement].value[x] 0..0
* parameter[medicationStatement].part 0..0
* parameter[medicationStatement] ^short = "The patient's current medication"
* parameter[medicationStatement] ^comment = "Optional only in the sense that the profile cannot know what the patient takes. Sending an incomplete list is the one error nothing downstream can detect: the answer will be about the list you sent, not about the patient."
* parameter[allergyIntolerance].name = "allergyIntolerance" (exactly)
* parameter[allergyIntolerance].resource 1..1
* parameter[allergyIntolerance].resource only FhirHubAllergyIntolerance
* parameter[allergyIntolerance].value[x] 0..0
* parameter[allergyIntolerance].part 0..0
* parameter[condition].name = "condition" (exactly)
* parameter[condition].resource 1..1
* parameter[condition].resource only FhirHubCondition
* parameter[condition].value[x] 0..0
* parameter[condition].part 0..0
* parameter[observation].name = "observation" (exactly)
* parameter[observation].resource 1..1
* parameter[observation].resource only FhirHubLabObservation
* parameter[observation].value[x] 0..0
* parameter[observation].part 0..0


// The second surveillance operation, added at 0.4.0. Same base, same upstream, same signals — what
// differs is what is under test: $check-medication-request weighs proposed prescriptions against a
// dossier, and this one weighs a dossier against itself.
//
// It exists because a mandatory `prescription` on the operation above withdrew a shape hosts had:
// "check what this patient is already taking". That question is worth answering, but not by making
// the prescription optional again — an operation whose subject depends on which parameters happen
// to be present cannot say in its profile what it checks, and the answer to "nothing under test"
// is an empty Bundle that reads as an all-clear. Two operations, each with a required subject.
//
// Note what this profile does NOT contain: a `prescription` slice. The slicing is closed, so a
// host that posts a MedicationRequest here is told so rather than having it silently ignored —
// which matters, because ignoring it would mean checking a drug's context without checking the
// drug.
Profile: FhirHubSurveillanceStatementInput
Parent: Parameters
Id: fhirhub-SurveillanceStatementInput
Title: "$check-medication-statement input"
Description: "The body of POST /fhir/surveillance/$check-medication-statement: a patient's context and the medication they are already taking, with every entry under test rather than serving as the background to a proposal. The signals come back as a Bundle of DetectedIssue, exactly as for $check-medication-request."
* ^status = #draft
* parameter ^slicing.discriminator[0].type = #value
* parameter ^slicing.discriminator[0].path = "name"
* parameter ^slicing.rules = #closed
* parameter contains
    patient 1..1 and
    xisId 1..1 and
    xisVersion 1..1 and
    medicationStatement 1..* and
    allergyIntolerance 0..* and
    condition 0..* and
    observation 0..*
* parameter[patient].name = "patient" (exactly)
* parameter[patient].resource 1..1
* parameter[patient].resource only FhirHubPatient
* parameter[patient].value[x] 0..0
* parameter[patient].part 0..0
* parameter[xisId].name = "xisId" (exactly)
* parameter[xisId].value[x] 1..1
* parameter[xisId].value[x] only string
* parameter[xisId].value[x] obeys fhirhub-non-blank
* parameter[xisId].value[x] ^short = "Your system id; identifies your product, not the practice"
* parameter[xisId].resource 0..0
* parameter[xisId].part 0..0
* parameter[xisVersion].name = "xisVersion" (exactly)
* parameter[xisVersion].value[x] 1..1
* parameter[xisVersion].value[x] only string
* parameter[xisVersion].value[x] obeys fhirhub-non-blank
* parameter[xisVersion].resource 0..0
* parameter[xisVersion].part 0..0
// Mandatory, and every entry is a subject of the check rather than context for one: the whole list
// is marked as the medication under test on the way upstream, so the classic G-Standaard checks
// examine each entry and pair the entries against each other. See MedicationSurveillanceRequestBuilder.
* parameter[medicationStatement].name = "medicationStatement" (exactly)
* parameter[medicationStatement].resource 1..1
* parameter[medicationStatement].resource only FhirHubMedicationStatement
* parameter[medicationStatement].value[x] 0..0
* parameter[medicationStatement].part 0..0
* parameter[medicationStatement] ^short = "The medication to check; at least one, and all of it is under test"
* parameter[medicationStatement] ^comment = "Required, and unlike on $check-medication-request every entry is itself checked: for allergy, for age, for dose, and against every other entry for duplicate medication. Send the patient's whole current list — an incomplete one is the one error nothing downstream can detect, because the answer will be about the list you sent."
* parameter[allergyIntolerance].name = "allergyIntolerance" (exactly)
* parameter[allergyIntolerance].resource 1..1
* parameter[allergyIntolerance].resource only FhirHubAllergyIntolerance
* parameter[allergyIntolerance].value[x] 0..0
* parameter[allergyIntolerance].part 0..0
* parameter[condition].name = "condition" (exactly)
* parameter[condition].resource 1..1
* parameter[condition].resource only FhirHubCondition
* parameter[condition].value[x] 0..0
* parameter[condition].part 0..0
* parameter[observation].name = "observation" (exactly)
* parameter[observation].resource 1..1
* parameter[observation].resource only FhirHubLabObservation
* parameter[observation].value[x] 0..0
* parameter[observation].part 0..0


// ---------------------------------------------------------------------------------------------
// The response, shared by both operations.
// ---------------------------------------------------------------------------------------------
//
// An EMPTY BUNDLE MEANS THE CHECK RAN AND NOTHING FIRED, and that is the invariant the whole
// contract rests on. It cannot be expressed here — a profile cannot distinguish "no findings"
// from "no answer" — so it is enforced in MedicationSurveillanceResponseParser, which refuses a
// response carrying no <report> rather than passing an empty one on, and pinned by
// SurveillanceIntegrationTest.refusesToTurnAFailedCheckIntoAnAllClear. Every other outcome is a
// 500. Do not read `entry 0..*` below as permission to return an empty Bundle for a check that
// did not happen.
//
// Both operations answer this profile. The one difference between them is the resource type in
// implicated.type, which is decided by the operation rather than by the upstream — see
// SurveillanceBundleMapper.DrugsUnderTest — so it is constrained to the two possible values
// rather than to one per operation.
//
// Note that nothing asserts this profile in meta.profile. The house rule is that a resource may
// claim a profile only where it validates clean against it, which this Bundle now does; whether
// to claim it is a separate decision, because the Implementation Guide currently tells
// integrators not to route on meta.profile and the two have to move together.

Invariant: fhirhub-logical-reference
Description: "implicated names the host's own record by the id it sent: a type and an identifier, never a resolvable URL. There is nothing to resolve — the resources came in on the request and are not stored here."
Severity: #error
Expression: "reference.empty() and type.exists() and identifier.value.exists()"

Invariant: fhirhub-implicated-medication
Description: "implicated.type is MedicationRequest for a drug this check had under test on $check-medication-request, and MedicationStatement for one under test on $check-medication-statement or carried as dossier context."
Severity: #error
Expression: "type = 'MedicationRequest' or type = 'MedicationStatement'"

Profile: FhirHubSurveillanceBundle
Parent: Bundle
Id: fhirhub-SurveillanceBundle
Title: "Medication surveillance output"
Description: "What POST /fhir/surveillance/$check-medication-request and POST /fhir/surveillance/$check-medication-statement return: one DetectedIssue per signal, in the order the clinical-rules report listed them. An empty Bundle means the check ran and no rule fired — every way for the check not to happen is a 500 instead, never a 200 with no findings."
* ^status = #draft
* type = #collection (exactly)
* type ^comment = "A collection, not a searchset: this is not the result of a search and has no paging."
* identifier 0..1
* identifier.system 1..1
* identifier.system = "http://spec.digitalis.nl/fhir/sid/crs-report" (exactly)
* identifier.value 1..1
* identifier ^short = "The report id the clinical-rules service assigned (CRID)"
* identifier ^comment = "Log it. It is what Digitalis support asks for when a prescriber queries a signal, and the only handle that ties this answer to the run that produced it. Optional only because the upstream is the one that assigns it."
* timestamp 0..1
* timestamp ^short = "When the report was produced upstream"
* timestamp ^comment = "The upstream's clock, not this service's, and repeated on every finding as DetectedIssue.identifiedDateTime."
* entry ^short = "One entry per signal; none if nothing fired"
* entry ^comment = "An empty Bundle is a valid and meaningful answer: the check ran and no rule fired. It is NOT what you get when the check could not run — that is a 500 with an OperationOutcome. A prescriber cannot tell an empty list of findings apart from a genuine all-clear, so this interface never returns one for a check that did not happen."
* entry.fullUrl 1..1
* entry.fullUrl ^short = "A urn:uuid, unique within this Bundle and nowhere else"
* entry.fullUrl ^comment = "This service stores nothing and there is no endpoint to fetch a finding back from, so there is no server identity to give these. Do not persist a fullUrl as a key or expect it to be stable across calls: the stable handle for a finding is DetectedIssue.identifier, which is the rule the signal came from."
* entry.resource 1..1
* entry.resource only FhirHubSurveillanceFinding
* entry.search 0..0
* entry.request 0..0
* entry.response 0..0

Profile: FhirHubSurveillanceFinding
Parent: DetectedIssue
Id: fhirhub-SurveillanceFinding
Title: "Medication surveillance finding"
Description: "One signal from Dutch medication surveillance: a medisch-farmaceutische beslisregel from the G-Standaard, or one of the classic allergy, age, duplicate-medication and dose checks. Route on identifier, show code.text, and read the full text from detail."
* ^status = #draft
* status = #final (exactly)
* status ^comment = "A report is a finished statement about the medication as it was sent; there is no preliminary form of it."
* severity 0..1
* severity ^short = "high, moderate or low, from the rule's own red, orange and green"
* severity ^comment = "Absent where the rule stated no level, which is not the same as the mildest one. Note what low means: a rule that fired and concluded that no action is needed — 'Dit is GEEN contra-indicatie', 'Bij deze interactie is GEEN actie nodig'. That is a finding with a reassuring text rather than a near-miss, and it answers a question the prescriber's own dossier raised. A host that hides low hides that answer."
* code 1..1
* code.coding 0..0
* code.text 1..1
* code ^short = "The title to show: the message's own where it has one, the rule's otherwise"
* code ^comment = "Text and no coding, because there is nothing honest to code it with: the rule identifier is an identifier, and FHIR's own DetectedIssue categories (drug interaction, duplicate therapy, and so on) are a classification the upstream does not make and this interface would have to guess at from a rule id. Whether Digitalis should mint a code system for the class of check — mfb, allergy, dosage, double-medication, age — is an open question; if it is answered, this cardinality widens, which is an additive change. Until then: route on identifier, show code.text."
* identifier 0..*
* identifier.system 1..1
* identifier.system = "http://spec.digitalis.nl/fhir/sid/crs-rule" (exactly)
* identifier.value 1..1
* identifier ^short = "The rule's own id — stable across reports; route and de-duplicate on this"
* identifier ^comment = "MFB-0000000068-v000006 for a beslisregel, hub-doublemedication-prkA-1090 and its siblings for the classic checks. An identifier rather than a code because the G-Standaard's editors publish, revise and withdraw these on their own schedule, so no code system defined here would stay current."
* detail 0..1
* detail ^short = "The rule's full text, as plain text"
* detail ^comment = "The upstream sends XHTML and this is a line-per-block rendering of it, with the numbered dosing steps still numbered. Plain text on purpose: a FHIR narrative is validated against a fixed list of permitted elements and attributes, and the upstream's markup carries at least one that is not on it — so carrying it through would put the validity of every response at the mercy of the next rule an editor writes."
* identified[x] only dateTime
* identifiedDateTime 0..1
* identifiedDateTime ^short = "The report's timestamp, repeated per finding"
* patient 0..0
* patient ^comment = "No patient identity comes back. This service is stateless and never stored the Patient the host sent, so there is nothing to reference; correlate a finding with your own record through implicated.identifier, which carries the id you put on the resource."
* evidence 0..*
* evidence.code 1..*
* evidence ^short = "What the rule read: the drug's codes, a lab result with its value, a contra-indication's code"
* evidence ^comment = "A drug arrives as its PRK, GPK, HPK and ATC together in one CodeableConcept; a lab result as its LOINC code with CodeableConcept.text carrying the determination and the value it was measured at ('kreatinineklaring: 35'), because the value belongs with the code it was measured for. evidence.code rather than evidence.detail, because the codes are the part a receiving system can act on without resolving anything: it already knows which PRK it sent."
* implicated 0..*
* implicated obeys fhirhub-logical-reference
* implicated obeys fhirhub-implicated-medication
* implicated.reference 0..0
* implicated.type 1..1
* implicated.identifier 1..1
* implicated.identifier.value 1..1
* implicated.display 0..1
* implicated ^short = "The medication the risk is in, as a logical reference to the record you sent"
* implicated ^comment = "identifier.value is the id you put on that resource, and type says whether it was a MedicationRequest you proposed or a MedicationStatement the patient already takes — which is decided by the operation you called, because the upstream echoes the same marking back either way. Set an id on the resources you send, or you get a positional identifier that is stable only within the request. Only medication appears here: FHIR defines implicated as the resources implicated in the detected risk, so a lab result, an allergy and a contra-indication are evidence rather than the subject of the finding. Note that for an allergy signal this element carries the verdict — low with no implicated means the allergy was checked and nothing matched, and high naming a drug means it did, while the rule's text reads the same in both cases."
