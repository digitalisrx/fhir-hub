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

// Surveillance over nothing is the one answer a prescriber cannot use, and an empty request is
// the easiest way to get it: a host that built its parameter names wrong would otherwise be told
// "no signals" about a patient whose medication was never sent. Same reasoning as the closed
// slicing above it, and as the 400 on an unresolvable drug code.
Invariant: fhirhub-something-to-check
Description: "Send at least one prescription to check, or at least one current-medication entry to check for interactions among. A request carrying neither has nothing to evaluate."
Severity: #error
Expression: "parameter.where(name = 'prescription' or name = 'medicationStatement').exists()"

Profile: FhirHubSurveillanceInput
Parent: Parameters
Id: fhirhub-SurveillanceInput
Title: "$check-medication input"
Description: "The body of POST /fhir/surveillance/$check-medication: the patient's context plus the prescriptions to check against it. A conformant request is weighed by the G-Standaard's medisch-farmaceutische beslisregels and by the classic allergy, age, duplicate-medication and dose checks, and the signals come back as a Bundle of DetectedIssue."
* ^status = #draft
// Not decoration: `experimental` is the FHIR-native way to say "test against this, do not rely
// on it". It is kept because the response shape may still move, not because the request one is
// in doubt — this profile has been enforced since 0.2.0 and did not change when the operation
// behind it was implemented.
* ^experimental = true
* obeys fhirhub-something-to-check
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
    prescription 0..* and
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
* parameter[prescription] ^short = "The prescriptions to check; repeat for more than one"
* parameter[prescription] ^comment = "The same profile $createrx-session takes, so a prescription can be checked and then handed to a session without being reshaped. Repeatable here where the session accepts one, because a host may want a whole proposed regimen weighed at once — and because two proposed drugs can interact with each other and not with anything the patient already takes."
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
