// The medication-surveillance contract, served on its own FHIR base at /fhir/surveillance.
//
// THE OPERATION IS IMPLEMENTED as of 0.3.0: the request is mapped to a DigitalisRx document, sent
// to the Digitalis Hub, and the clinical-rules report that comes back is returned as a Bundle of
// DetectedIssue. Before that it answered 501, and the request contract published here is the one
// it answered 501 against — unchanged, which is what publishing it early was for.
//
// It is still `experimental = true`, and that is about the RESPONSE rather than this profile. What
// a host sends has been enforced for a release; what it gets back has existed for days. The
// severity mapping, how a rule's own text arrives, and what a partial answer would look like are
// the parts that may still move.
//
// There is deliberately still NO RESPONSE PROFILE. A profile is a promise, and the shape of the
// response is described in the Implementation Guide instead until it has been reviewed against
// real reports. Publishing one now would invite a host to bind to a shape that is a week old.
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
// Not decoration: `experimental` is the FHIR-native way to say "test against this, do not rely
// on it". It is kept because the response shape may still move, not because the request one is
// in doubt — this profile has been enforced since 0.2.0 and did not change when the operation
// behind it was implemented.
* ^experimental = true
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
// Experimental for the same reason as the profile above: the response shape it shares may move.
* ^experimental = true
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
