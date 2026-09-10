// The examples from the Implementation Guide, as validatable instances.
//
// The point is that the documentation cannot drift from the profiles: if an example here
// stops satisfying its profile, the build says so. These mirror the payloads in
// IMPLEMENTATION_GUIDE.md — keep the two in step.

Instance: ExamplePatient
InstanceOf: FhirHubPatient
Usage: #inline
* gender = #female
* birthDate = "1980-01-01"

Instance: ExampleAllergy
InstanceOf: FhirHubAllergyIntolerance
Usage: #inline
// Mandatory in base R4 and not read by fhir-hub; there is no Patient resource to point at,
// because the patient travels as a sibling parameter rather than a contained resource.
* patient.extension[0].url = "http://hl7.org/fhir/StructureDefinition/data-absent-reason"
* patient.extension[0].valueCode = #unknown
// Required by base R4 invariant ait-1, and likewise not read by fhir-hub.
* clinicalStatus = http://terminology.hl7.org/CodeSystem/allergyintolerance-clinical#active
* code.coding[0].system = "urn:oid:2.16.840.1.113883.2.4.4.1.750"
* code.coding[0].code = #10499

Instance: ExampleContraIndication
InstanceOf: FhirHubCondition
Usage: #inline
* subject.extension[0].url = "http://hl7.org/fhir/StructureDefinition/data-absent-reason"
* subject.extension[0].valueCode = #unknown
* code.coding[0].system = "urn:oid:2.16.840.1.113883.2.4.4.1.902.40"
* code.coding[0].code = #228

Instance: ExampleCurrentMedication
InstanceOf: FhirHubMedicationStatement
Usage: #inline
* status = #active
* subject.extension[0].url = "http://hl7.org/fhir/StructureDefinition/data-absent-reason"
* subject.extension[0].valueCode = #unknown
* medicationCodeableConcept.coding[0].system = "urn:oid:2.16.840.1.113883.2.4.4.10"
* medicationCodeableConcept.coding[0].code = #18996

// The determination medication surveillance turns on: 666 of the current MFB rules test the
// nierfunctie. Note the unit — an eGFR is normalised per 1.73 m2 and is sent as such.
Instance: ExampleLabResult
InstanceOf: FhirHubLabObservation
Usage: #inline
* status = #final
* code.coding[0].system = "http://loinc.org"
* code.coding[0].code = #62238-1
// No display, and nothing is lost by that: SessionParametersMapper falls back to the caption in
// fhir/LabDeterminations.java, which for this code is "eGFR volgens CKD-EPI" — the very string
// that used to sit here. It had to go because LOINC's own term for 62238-1 is "Glomerulaire
// filtratiesnelheid/1,73 m2.voorspeld [filtratiesnelheid/oppervlakte] in serum of plasma of
// bloed d.m.v. formule gebaseerd op creatinine (CKD-EPI)", and a validator with LOINC loaded
// rejects anything else as a wrong display name — a friendly local label included.
* effectiveDateTime = "2024-07-04"
* valueQuantity.value = 65
* valueQuantity.unit = "mL/min/1.73m2"
* valueQuantity.system = "http://unitsofmeasure.org"
* valueQuantity.code = #"mL/min/{1.73_m2}"

// The one example that has to satisfy a profile it does not claim: R4 requires an Observation
// coded 8302-2 to conform to the core Body height profile, which wants the vital-signs category,
// a subject, and centimetres or inches. So it carries both elements even though this interface
// reads neither, and a host can copy it and be conformant to core as well as to this guide. That
// binding is also why centimetres are the only unit accepted for a height — metres were taken and
// converted exactly until it turned out no other reader would take them.
Instance: ExampleLengthInCentimetres
InstanceOf: FhirHubLabObservation
Usage: #example
Title: "Lengte in centimeters"
Description: "A body height of 178 cm, LOINC 8302-2, in the unit the G-Standaard dose model works in. The vital-signs category and the subject are there for R4's own Body height profile, which any height coded 8302-2 is validated against; this interface reads neither."
* id = "obs-lengte-cm"
* status = #final
* category[0].coding[0].system = "http://terminology.hl7.org/CodeSystem/observation-category"
* category[0].coding[0].code = #vital-signs
* subject.extension[0].url = "http://hl7.org/fhir/StructureDefinition/data-absent-reason"
* subject.extension[0].valueCode = #unknown
* code.coding[0].system = "http://loinc.org"
* code.coding[0].code = #8302-2
* effectiveDateTime = "2026-09-08"
* valueQuantity.value = 178
* valueQuantity.unit = "cm"
* valueQuantity.system = "http://unitsofmeasure.org"
* valueQuantity.code = #cm

Instance: ExampleFormularySessionInput
InstanceOf: FhirHubFormularySessionInput
Usage: #example
Title: "$formulary-session request"
Description: "A formulary session with one allergy, one contra-indication, one current drug and one lab result."
* parameter[patient].name = "patient"
* parameter[patient].resource = ExamplePatient
* parameter[reason].name = "reason"
* parameter[reason].valueCodeableConcept.coding[0].system = "urn:oid:2.16.840.1.113883.2.4.4.31.1"
* parameter[reason].valueCodeableConcept.coding[0].code = #A01
* parameter[endSessionUrl].name = "endSessionUrl"
* parameter[endSessionUrl].valueUrl = "https://host.example/done"
* parameter[xisId].name = "xisId"
* parameter[xisId].valueString = "xis-001"
* parameter[xisVersion].name = "xisVersion"
* parameter[xisVersion].valueString = "1.0"
* parameter[allergyIntolerance][0].name = "allergyIntolerance"
* parameter[allergyIntolerance][0].resource = ExampleAllergy
* parameter[condition][0].name = "condition"
* parameter[condition][0].resource = ExampleContraIndication
* parameter[medicationStatement][0].name = "medicationStatement"
* parameter[medicationStatement][0].resource = ExampleCurrentMedication
* parameter[observation][0].name = "observation"
* parameter[observation][0].resource = ExampleLabResult

Instance: ExampleSessionOutput
InstanceOf: FhirHubSessionOutput
Usage: #example
Title: "Session response"
Description: "What both session operations return."
* parameter[sessionId].name = "sessionId"
* parameter[sessionId].valueString = "sess-abc-123"
* parameter[url].name = "url"
* parameter[url].valueUrl = "https://evs.prescriptor.nl/web_current/index.php?sk=sess-abc-123"

// One G-Standaard coding, at the level the prescription was written at, plus the ATC. Exactly
// one, which is easy to get wrong in this direction: it is the INPUT side that expands a code
// into a PRK + GPK set, for surveillance, and none of that comes back out.
Instance: ExamplePrescription
InstanceOf: FhirHubPrescription
Usage: #inline
* status = #active
* intent = #order
* subject.extension[0].url = "http://hl7.org/fhir/StructureDefinition/data-absent-reason"
* subject.extension[0].valueCode = #unknown
* extension[opiumAct].url = "http://spec.digitalis.nl/fhir/StructureDefinition/ext-MedicationRequest.OpiumActClassification"
* extension[opiumAct].valueCodeableConcept = GStandaardBijzonderKenmerk#2 "Product valt onder Opiumwet in volle omvang"
* medicationCodeableConcept.coding[0].system = "urn:oid:2.16.840.1.113883.2.4.4.10"
* medicationCodeableConcept.coding[0].code = #18996
* medicationCodeableConcept.coding[0].display = "PARACETAMOL ZETPIL 1000MG"
* medicationCodeableConcept.coding[1].system = "http://www.whocc.no/atc"
* medicationCodeableConcept.coding[1].code = #N02BE01
* medicationCodeableConcept.text = "PARACETAMOL ZETPIL 1000MG"
* dosageInstruction[0].extension[0].url = "http://spec.digitalis.nl/fhir/StructureDefinition/ext-Dosage.CodedDirections"
* dosageInstruction[0].extension[0].valueString = "3-4D1S; gedurende max. 1 maand"
* dosageInstruction[0].text = "3 tot 4 maal per dag 1 stuk"
* dispenseRequest.quantity.value = 15
* dispenseRequest.quantity.system = "urn:oid:2.16.840.1.113883.2.4.4.1.900.2"
* dispenseRequest.quantity.code = #ST
* dispenseRequest.quantity.unit = "ST"
* dispenseRequest.expectedSupplyDuration.value = 30
* dispenseRequest.expectedSupplyDuration.unit = "dag"
* dispenseRequest.expectedSupplyDuration.system = "http://unitsofmeasure.org"
* dispenseRequest.expectedSupplyDuration.code = #d

Instance: ExampleAdvice
InstanceOf: FhirHubAdvice
Usage: #inline
* status = #completed
* category[0] = http://terminology.hl7.org/CodeSystem/communication-category#instruction
* payload[0].contentString = "Neem in bij voorkeur met wat water."

// The other payload form: advice that is a pointer rather than prose. Prescriptor reports both
// as text, and the text/plain versus text/uri-list distinction becomes the payload type.
Instance: ExampleAdviceLink
InstanceOf: FhirHubAdvice
Usage: #inline
* status = #completed
* category[0] = http://terminology.hl7.org/CodeSystem/communication-category#instruction
* payload[0].contentAttachment.contentType = #text/uri-list
* payload[0].contentAttachment.url = "https://www.thuisarts.nl/paracetamol"

Instance: ExampleResultBundle
InstanceOf: FhirHubResultBundle
Usage: #example
Title: "$session-result response"
Description: "One prescription, one prose advice and one pointer to patient information."
* type = #collection
// A urn:uuid identity: these resources exist only inside this Bundle. fhir-hub stores nothing
// and there is no endpoint to fetch a prescription back from.
* entry[prescription][0].fullUrl = "urn:uuid:9f1b2d34-5a67-4c89-b012-3456789abcde"
* entry[prescription][0].resource = ExamplePrescription
* entry[advice][0].fullUrl = "urn:uuid:1c2d3e4f-5678-4a9b-8c0d-1e2f3a4b5c6d"
* entry[advice][0].resource = ExampleAdvice
* entry[advice][1].fullUrl = "urn:uuid:7b8c9d01-2e3f-4a5b-9c6d-7e8f9a0b1c2d"
* entry[advice][1].resource = ExampleAdviceLink

// ---------------------------------------------------------------------------
// $createrx-session: the same context, plus a prescription to open for editing.
//
// This is the payload the Implementation Guide documents under `prescription`, and the reason
// it is here is that it was the one example the build never checked. Note what a conformant
// prescription needs beyond the product code: status, intent and subject are mandatory in base
// R4 and unread here, so subject carries a data-absent-reason like everything else the host
// has nothing to point at.
//
// Note also the level: PRK or HPK, never GPK. $session-result can return a prescription coded
// at GPK level, and that one cannot be handed back without resolving it first.
// ---------------------------------------------------------------------------

Instance: ExamplePrescriptionInput
InstanceOf: FhirHubPrescriptionInput
Usage: #inline
* status = #active
* intent = #order
* subject.extension[0].url = "http://hl7.org/fhir/StructureDefinition/data-absent-reason"
* subject.extension[0].valueCode = #unknown
* medicationCodeableConcept.coding[0].system = "urn:oid:2.16.840.1.113883.2.4.4.10"
* medicationCodeableConcept.coding[0].code = #18996
* medicationCodeableConcept.coding[0].display = "PARACETAMOL ZETPIL 1000MG"
* medicationCodeableConcept.coding[1].system = "http://www.whocc.no/atc"
* medicationCodeableConcept.coding[1].code = #N02BE01
* dosageInstruction[0].extension[0].url = "http://spec.digitalis.nl/fhir/StructureDefinition/ext-Dosage.CodedDirections"
* dosageInstruction[0].extension[0].valueString = "3-4D1S; gedurende max. 1 maand"
* dispenseRequest.quantity.value = 15
* dispenseRequest.quantity.system = "urn:oid:2.16.840.1.113883.2.4.4.1.900.2"
* dispenseRequest.quantity.code = #ST

Instance: ExampleCreateRxSessionInput
InstanceOf: FhirHubCreateRxSessionInput
Usage: #example
Title: "$createrx-session request"
Description: "A CreateRx session opened on a prescription the host already holds. No reason for encounter: CreateRx prescribes without a formulary lookup."
* parameter[patient].name = "patient"
* parameter[patient].resource = ExamplePatient
* parameter[endSessionUrl].name = "endSessionUrl"
* parameter[endSessionUrl].valueUrl = "https://host.example/done"
* parameter[xisId].name = "xisId"
* parameter[xisId].valueString = "xis-001"
* parameter[xisVersion].name = "xisVersion"
* parameter[xisVersion].valueString = "1.0"
* parameter[medicationStatement][0].name = "medicationStatement"
* parameter[medicationStatement][0].resource = ExampleCurrentMedication
* parameter[prescription].name = "prescription"
* parameter[prescription].resource = ExamplePrescriptionInput

// ---------------------------------------------------------------------------
// The surveillance contract, on its own base. Two examples: a short one that matches the payload
// in the guide's prose, and the reference case below it, which is the FHIR form of the request
// Digitalis documents as example-1-req.xml. See profiles-surveillance.fsh.
//
// Every resource in it is reused unchanged from the session examples above, which is the point:
// a host that already opens sessions has no new payload to shape, only a new address to post to.
// ---------------------------------------------------------------------------

Instance: ExampleSurveillanceInput
InstanceOf: FhirHubSurveillanceInput
Usage: #example
Title: "$check-medication-request example"
Description: "One proposed prescription weighed against a patient's current medication, allergy, contra-indication and lab result."
* parameter[patient].name = "patient"
* parameter[patient].resource = ExamplePatient
* parameter[xisId].name = "xisId"
* parameter[xisId].valueString = "xis-001"
* parameter[xisVersion].name = "xisVersion"
* parameter[xisVersion].valueString = "1.0"
* parameter[prescription][0].name = "prescription"
* parameter[prescription][0].resource = ExamplePrescriptionInput
* parameter[medicationStatement][0].name = "medicationStatement"
* parameter[medicationStatement][0].resource = ExampleCurrentMedication
* parameter[allergyIntolerance][0].name = "allergyIntolerance"
* parameter[allergyIntolerance][0].resource = ExampleAllergy
* parameter[condition][0].name = "condition"
* parameter[condition][0].resource = ExampleContraIndication
* parameter[observation][0].name = "observation"
* parameter[observation][0].resource = ExampleLabResult

// ---------------------------------------------------------------------------
// The reference case: the FHIR form of DigitalisRx-documentation/example-1-req.xml, the request
// Digitalis documents alongside the response it produces. Metformine proposed for a pregnant
// 19-year-old with an eGFR of 35 who already takes ibuprofen and omeprazol.
//
// It is here rather than only in that folder so the build checks it: SUSHI validates nothing, and
// IgExampleConformanceTest is what stops an example contradicting its profile. What it produces on
// the wire is pinned separately, by SurveillanceIntegrationTest.postsTheReferenceCaseAsDocumented,
// which sends this instance and compares the DigitalisRx that comes out with the documented one.
//
// Every id is the UID from that document, because an id is what comes back on
// DetectedIssue.implicated — 9064 is the metformine the signals are about.

Instance: ReferencePatient
InstanceOf: FhirHubPatient
Usage: #inline
* gender = #female
* birthDate = "2007-09-07"

Instance: ReferenceAllergyPenicillines
InstanceOf: FhirHubAllergyIntolerance
Usage: #inline
* id = "5469"
* patient.extension[0].url = "http://hl7.org/fhir/StructureDefinition/data-absent-reason"
* patient.extension[0].valueCode = #unknown
* clinicalStatus = http://terminology.hl7.org/CodeSystem/allergyintolerance-clinical#active
// Thesaurus 122, an ongewenste groep — the only allergy form a beslisregel can test.
* code.coding[0].system = "urn:oid:2.16.840.1.113883.2.4.4.1.902.122"
* code.coding[0].code = #35
// Your own wording, and not decoration: the surveillance upstream writes it into the title and
// the body of the signal it raises.
* code.coding[0].display = "PENICILLINES"

Instance: ReferenceAllergyTalk
InstanceOf: FhirHubAllergyIntolerance
Usage: #inline
* id = "5470"
* patient.extension[0].url = "http://hl7.org/fhir/StructureDefinition/data-absent-reason"
* patient.extension[0].valueCode = #unknown
* clinicalStatus = http://terminology.hl7.org/CodeSystem/allergyintolerance-clinical#active
* code.coding[0].system = "urn:oid:2.16.840.1.113883.2.4.4.1.750"
* code.coding[0].code = #10499
* code.coding[0].display = "TALK"

Instance: ReferencePregnancy
InstanceOf: FhirHubCondition
Usage: #inline
* id = "5468"
* subject.extension[0].url = "http://hl7.org/fhir/StructureDefinition/data-absent-reason"
* subject.extension[0].valueCode = #unknown
* code.coding[0].system = "urn:oid:2.16.840.1.113883.2.4.4.1.902.40"
* code.coding[0].code = #1320
* code.coding[0].display = "ZWANGERSCHAP"

Instance: ReferenceMetformine
InstanceOf: FhirHubPrescriptionInput
Usage: #inline
* id = "9064"
* status = #active
* intent = #order
* subject.extension[0].url = "http://hl7.org/fhir/StructureDefinition/data-absent-reason"
* subject.extension[0].valueCode = #unknown
* medicationCodeableConcept.coding[0].system = "urn:oid:2.16.840.1.113883.2.4.4.10"
* medicationCodeableConcept.coding[0].code = #1090
* medicationCodeableConcept.coding[0].display = "METFORMINE TABLET   500MG"
* medicationCodeableConcept.coding[1].system = "http://www.whocc.no/atc"
* medicationCodeableConcept.coding[1].code = #A10BA02
* dosageInstruction[0].extension[0].url = "http://spec.digitalis.nl/fhir/StructureDefinition/ext-Dosage.CodedDirections"
* dosageInstruction[0].extension[0].valueString = "1D1T"
* dosageInstruction[0].text = "1 X per dag 1 tablet"
* dispenseRequest.quantity.value = 20
* dispenseRequest.quantity.system = "urn:oid:2.16.840.1.113883.2.4.4.1.900.2"
* dispenseRequest.quantity.code = #ST
// When the prescription runs. Both engines filter the dossier on the start date.
* dispenseRequest.validityPeriod.start = "2026-09-06"
* dispenseRequest.validityPeriod.end = "2026-09-26"
// The indication, which is what makes a dose check indication-specific: K86 is hypertensie
// zonder orgaanbeschadiging in ICPC-1 NL, and it is the reason recorded in the reference document.
* reasonCode[0].coding[0].system = "urn:oid:2.16.840.1.113883.2.4.4.31.1"
* reasonCode[0].coding[0].code = #K86

Instance: ReferenceIbuprofen
InstanceOf: FhirHubMedicationStatement
Usage: #inline
* id = "9065"
* status = #active
* subject.extension[0].url = "http://hl7.org/fhir/StructureDefinition/data-absent-reason"
* subject.extension[0].valueCode = #unknown
* medicationCodeableConcept.coding[0].system = "urn:oid:2.16.840.1.113883.2.4.4.10"
* medicationCodeableConcept.coding[0].code = #27278
* medicationCodeableConcept.coding[0].display = "IBUPROFEN TABLET 400MG"
* effectivePeriod.start = "2026-09-06"
* effectivePeriod.end = "2026-09-11"

Instance: ReferenceOmeprazol
InstanceOf: FhirHubMedicationStatement
Usage: #inline
* id = "9066"
* status = #active
* subject.extension[0].url = "http://hl7.org/fhir/StructureDefinition/data-absent-reason"
* subject.extension[0].valueCode = #unknown
* medicationCodeableConcept.coding[0].system = "urn:oid:2.16.840.1.113883.2.4.4.10"
* medicationCodeableConcept.coding[0].code = #60062
* medicationCodeableConcept.coding[0].display = "OMEPRAZOL CAPSULE MSR 20MG"
* effectivePeriod.start = "2026-09-06"
* effectivePeriod.end = "2026-09-13"

Instance: ReferenceEgfr
InstanceOf: FhirHubLabObservation
Usage: #inline
* id = "1667"
* status = #final
* code.coding[0].system = "http://loinc.org"
* code.coding[0].code = #62238-1
// No display: LOINC is distributed, so a display that is not LOINC's own Dutch term is a
// validation error rather than a warning. The interface supplies the caption instead.
// The time of day is stated, because only the most recent result of a determination is forwarded
// and results carrying a date alone are equally recent.
* effectiveDateTime = "2026-09-06T09:05:11+02:00"
* valueQuantity.value = 35
* valueQuantity.system = "http://unitsofmeasure.org"
* valueQuantity.code = #mL/min/{1.73_m2}

Instance: ExampleSurveillanceReferenceCase
InstanceOf: FhirHubSurveillanceInput
Usage: #example
Title: "$check-medication-request example, the reference case"
Description: "The FHIR form of the request Digitalis documents as example-1-req.xml: metformine proposed for a pregnant patient with an eGFR of 35 who already takes ibuprofen and omeprazol. It is the payload behind the three beslisregel signals in that document's response, and it also raises the classic allergy and dose-control signals."
* parameter[patient].name = "patient"
* parameter[patient].resource = ReferencePatient
* parameter[xisId].name = "xisId"
* parameter[xisId].valueString = "xis-001"
* parameter[xisVersion].name = "xisVersion"
* parameter[xisVersion].valueString = "1.0"
* parameter[prescription][0].name = "prescription"
* parameter[prescription][0].resource = ReferenceMetformine
* parameter[medicationStatement][0].name = "medicationStatement"
* parameter[medicationStatement][0].resource = ReferenceIbuprofen
* parameter[medicationStatement][1].name = "medicationStatement"
* parameter[medicationStatement][1].resource = ReferenceOmeprazol
* parameter[allergyIntolerance][0].name = "allergyIntolerance"
* parameter[allergyIntolerance][0].resource = ReferenceAllergyPenicillines
* parameter[allergyIntolerance][1].name = "allergyIntolerance"
* parameter[allergyIntolerance][1].resource = ReferenceAllergyTalk
* parameter[condition][0].name = "condition"
* parameter[condition][0].resource = ReferencePregnancy
* parameter[observation][0].name = "observation"
* parameter[observation][0].resource = ReferenceEgfr

// ---------------------------------------------------------------------------
// The dossier check, added with $check-medication-statement at 0.4.0. Two drugs a patient is
// already taking, checked against each other and against a nierfunctie that has since dropped —
// which is the class of signal no proposal-shaped request can ask for, because nothing is being
// proposed.
//
// Both entries carry an `id`, and that is the part worth copying: every entry of this list goes
// upstream as medication under test, so every signal comes back on DetectedIssue.implicated naming
// a MedicationStatement by the id you gave it. Without one a host gets a positional identifier and
// has to match on codes.

Instance: StatementCheckMetformine
InstanceOf: FhirHubMedicationStatement
Usage: #inline
* id = "ms-metformine"
* status = #active
* subject.extension[0].url = "http://hl7.org/fhir/StructureDefinition/data-absent-reason"
* subject.extension[0].valueCode = #unknown
* medicationCodeableConcept.coding[0].system = "urn:oid:2.16.840.1.113883.2.4.4.10"
* medicationCodeableConcept.coding[0].code = #1090
* medicationCodeableConcept.coding[0].display = "METFORMINE TABLET   500MG"
* effectivePeriod.start = "2024-11-02"

Instance: StatementCheckIbuprofen
InstanceOf: FhirHubMedicationStatement
Usage: #inline
* id = "ms-ibuprofen"
* status = #active
* subject.extension[0].url = "http://hl7.org/fhir/StructureDefinition/data-absent-reason"
* subject.extension[0].valueCode = #unknown
* medicationCodeableConcept.coding[0].system = "urn:oid:2.16.840.1.113883.2.4.4.10"
* medicationCodeableConcept.coding[0].code = #27278
* medicationCodeableConcept.coding[0].display = "IBUPROFEN TABLET 400MG"
* effectivePeriod.start = "2026-08-19"

Instance: ExampleStatementCheck
InstanceOf: FhirHubSurveillanceStatementInput
Usage: #example
Title: "$check-medication-statement example"
Description: "A patient's current medication checked against itself and their context: two drugs, an allergy and an eGFR of 35, with no prescription proposed. Note that there is no prescription parameter — this profile does not define one."
* parameter[patient].name = "patient"
* parameter[patient].resource = ExamplePatient
* parameter[xisId].name = "xisId"
* parameter[xisId].valueString = "xis-001"
* parameter[xisVersion].name = "xisVersion"
* parameter[xisVersion].valueString = "1.0"
* parameter[medicationStatement][0].name = "medicationStatement"
* parameter[medicationStatement][0].resource = StatementCheckMetformine
* parameter[medicationStatement][1].name = "medicationStatement"
* parameter[medicationStatement][1].resource = StatementCheckIbuprofen
* parameter[allergyIntolerance][0].name = "allergyIntolerance"
* parameter[allergyIntolerance][0].resource = ExampleAllergy
* parameter[observation][0].name = "observation"
* parameter[observation][0].resource = ExampleLabResult


// The response, and the first example in this guide that is an OUTPUT of the surveillance
// contract rather than an input. It is the FHIR form of two of the signals the reference-case
// request produces at hub.digitalis.nl: a nierfunctie beslisregel that fires red on the proposed
// metformine, and an allergy check that ran and matched nothing — which is the shape a host is
// most likely to misread, because its text reads "voor het onderstaande middel" while its
// severity is low and its implicated list is empty.
Instance: SurveillanceFindingNierfunctie
InstanceOf: FhirHubSurveillanceFinding
Usage: #inline
* identifier[0].system = "http://spec.digitalis.nl/fhir/sid/crs-rule"
* identifier[0].value = "MFB-0000000068-v000006"
* status = #final
* code.text = "Nierfunctie: metformine"
* severity = #high
* identifiedDateTime = "2026-09-07T11:11:16+02:00"
* implicated[0].type = "MedicationRequest"
* implicated[0].identifier.value = "rx-1"
* implicated[0].display = "METFORMINE TABLET   500MG"
* detail = "Risico op lactaatacidose is verhoogd. Patiënt heeft creatinineklaring 30-60 ml/min.\n1. aanvankelijk 500 mg metformine 2x per dag\n2. vervolgens dosering geleidelijk verhogen tot standaardonderhoudsdosering"
* evidence[0].code[0].coding[0].system = "urn:oid:2.16.840.1.113883.2.4.4.10"
* evidence[0].code[0].coding[0].code = #1090
* evidence[0].code[0].coding[1].system = "urn:oid:2.16.840.1.113883.2.4.4.1"
* evidence[0].code[0].coding[1].code = #3816
* evidence[0].code[0].coding[2].system = "http://www.whocc.no/atc"
* evidence[0].code[0].coding[2].code = #A10BA02
* evidence[0].code[0].text = "METFORMINE TABLET   500MG"
* evidence[1].code[0].coding[0].system = "http://loinc.org"
* evidence[1].code[0].coding[0].code = #62238-1
* evidence[1].code[0].text = "kreatinineklaring: 35"

// Checked, nothing matched. severity low and NO implicated — branch on those two rather than on
// the sentence, which is the upstream's wording and is only accurate when the check did match.
Instance: SurveillanceFindingAllergyClear
InstanceOf: FhirHubSurveillanceFinding
Usage: #inline
* identifier[0].system = "http://spec.digitalis.nl/fhir/sid/crs-rule"
* identifier[0].value = "hub-allergy-snk-10499"
* status = #final
* code.text = "Allergie (SNK)"
* severity = #low
* identifiedDateTime = "2026-09-07T11:11:16+02:00"
* detail = "In het dossier is een allergie (PENICILLINES) geregistreerd voor het onderstaande middel."
* evidence[0].code[0].coding[0].system = "urn:oid:2.16.840.1.113883.2.4.4.1.750"
* evidence[0].code[0].coding[0].code = #10499
* evidence[0].code[0].text = "PENICILLINES"

Instance: ExampleSurveillanceBundle
InstanceOf: FhirHubSurveillanceBundle
Usage: #example
Title: "Medication surveillance response"
Description: "Two signals from one report: a nierfunctie beslisregel firing red on a proposed metformine, and an allergy check that ran and matched nothing. Bundle.identifier is the report id to quote in a support question."
* identifier.system = "http://spec.digitalis.nl/fhir/sid/crs-report"
* identifier.value = "83327A6E-FAED-4448-A20E-EFEA660C7627"
* type = #collection
* timestamp = "2026-09-07T11:11:16+02:00"
* entry[0].fullUrl = "urn:uuid:6f1b2d34-5a67-4c89-b012-3456789abcde"
* entry[0].resource = SurveillanceFindingNierfunctie
* entry[1].fullUrl = "urn:uuid:2a3b4c5d-6e7f-4a8b-9c0d-1e2f3a4b5c6d"
* entry[1].resource = SurveillanceFindingAllergyClear
