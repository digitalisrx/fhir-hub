// Code systems and value sets for the fhir-hub payloads.
//
// The OID-based systems below (ICPC-1 NL, PRK, HPK, GPK and the four G-Standaard subsystems) are national
// identifiers and are NOT defined here — they belong to HL7 NL and the NHG, and every OID was
// taken from the HL7 NL OID register rather than from memory. Only the systems Digitalis
// actually mints are defined as CodeSystems in this IG.

Alias: $icpc      = urn:oid:2.16.840.1.113883.2.4.4.31.1
Alias: $prk       = urn:oid:2.16.840.1.113883.2.4.4.10
Alias: $hpk       = urn:oid:2.16.840.1.113883.2.4.4.7
Alias: $gpk       = urn:oid:2.16.840.1.113883.2.4.4.1
Alias: $loinc     = http://loinc.org
Alias: $basiseenheid = urn:oid:2.16.840.1.113883.2.4.4.1.900.2
Alias: $atc       = http://www.whocc.no/atc
Alias: $ssk       = urn:oid:2.16.840.1.113883.2.4.4.1.725
Alias: $snk       = urn:oid:2.16.840.1.113883.2.4.4.1.750
Alias: $oggrp     = urn:oid:2.16.840.1.113883.2.4.4.1.902.122
Alias: $cicode    = urn:oid:2.16.840.1.113883.2.4.4.1.902.40
Alias: $ucum      = http://unitsofmeasure.org

// ---------------------------------------------------------------------------
// The one code system Digitalis mints. The G-Standaard subsystems are NOT defined here, and
// must not be: a CodeSystem this IG defines is checked more strictly than one it does not
// know. The G-Standaard tables are licensed, so any definition would have to be
// content: not-present, which makes a value set including it unexpandable and turns every
// coding in it into an error. An entirely unknown system falls to
// UnknownCodeSystemWarningValidationSupport and becomes a warning instead, which is the only
// way a required binding onto a licensed table can be satisfied. TerminologyEnforcementTest
// pins that.
//
// BST401T rubriek 72 is different: it defines exactly these three Opiumwet codes, so it can be
// content: complete. Prescriptor reports only a yes/no today, so fhir-hub emits only code 2.
// ---------------------------------------------------------------------------

CodeSystem: GStandaardBijzonderKenmerk
Id: gstandaard-bijzonder-kenmerk
Title: "G-Standaard bijzondere kenmerken (Opiumwet)"
Description: "The Opiumwet subset of G-Standaard bestand BST401T (thesaurus BST922T). No OID is registered for this table, so a Digitalis-local URI is used."
* ^status = #draft
* ^experimental = false
* ^content = #complete
* ^caseSensitive = true
* #2   "Product valt onder Opiumwet in volle omvang"
* #65  "Product valt onder Opiumwet; afhandeling als UR"
* #107 "Grondstof valt onder Opiumwet in volle omvang"

// ---------------------------------------------------------------------------
// Value sets.
//
// Every one of these binds the SYSTEM rather than the code: the G-Standaard tables are
// licensed and enormous, they are not distributed with these profiles, and so a validator can
// check only that the coding comes from a system this interface routes. That is exactly the
// check SessionParametersMapper performs.
//
// There is exactly ONE accepted system per subsystem, and it is a URI. The upstream tokens
// ("SSK", "PRK", "ICPC", ...) are XML attribute names in the DigitalisRx document, not
// identifiers a caller may put in Coding.system, so neither these bindings nor
// CodeSystemRegistry accept one — with or without runtime validation. Blessing the token here
// would put a non-FHIR identifier on the wire permanently, and it would be the form a host
// discovers first.
// ---------------------------------------------------------------------------

ValueSet: AllergyCausativeAgentVS
Id: allergy-causative-agent
Title: "Allergy causative agent (SSK, SNK, OGGrp)"
Description: "Causative agents fhir-hub routes to Prescriptor. Which member carries which subsystem is set by prescriptor-api's OpenSessionRequestBuilder.getAllergies."
* ^status = #draft
* ^experimental = false
* include codes from system $ssk
* include codes from system $snk
* include codes from system $oggrp

ValueSet: ContraIndicationVS
Id: contra-indication
Title: "Contra-indication (CICode or ICPC-1 NL)"
Description: "Contra-indications fhir-hub routes to Prescriptor, as either a G-Standaard CICode or an ICPC-1 NL code."
* ^status = #draft
* ^experimental = false
* include codes from system $cicode
* include codes from system $icpc

ValueSet: MedicationCodeVS
Id: medication-code
Title: "Medication code (PRK or HPK)"
Description: "Product code levels accepted for current medication. Each entry carries its own level, so PRK and HPK may be mixed within one list."
* ^status = #draft
* ^experimental = false
* include codes from system $prk
* include codes from system $hpk

ValueSet: DispensedMedicationCodeVS
Id: dispensed-medication-code
Title: "Prescribed medication code (PRK, GPK, HPK or ATC)"
Description: "Codings that may appear on a MedicationRequest returned by $session-result. Exactly one of PRK, GPK and HPK is present — the level the prescription was written at — with an ATC beside it when the upstream supplies one."
* ^status = #draft
* ^experimental = false
* include codes from system $prk
* include codes from system $gpk
* include codes from system $hpk
* include codes from system $atc

ValueSet: IcpcVS
Id: icpc-1-nl
Title: "ICPC-1 NL"
Description: "Reason for encounter. The code shape is additionally constrained by an invariant, because ICPC-1 NL is not expanded here."
* ^status = #draft
* ^experimental = false
* include codes from system $icpc

// Any LOINC code validates on Parameters.parameter:observation — see LoincVS below. This value
// set is the narrower, meaningful list: the determinations a beslisregel or the dose-band model
// actually reads, and nothing else. It is not the binding that decides what an Observation.code
// may carry; it is documentation, kept as a value set so the guide can render it as a table and
// LabDeterminationsTest can pin it against fhir/LabDeterminations.java.
//
// The list is short because the G-Standaard's own list is: BST685T rows with THMFBP = 2000 are
// the complete set of patient measurements an MFB rule can test — twelve of them, four used by
// current rules — plus weight and height, which dose checking reads. A lab value outside this set
// validates and is accepted, and then forwarded nowhere: nothing here reads it, so sending it
// upstream would tell a prescriber their lab data had been weighed when nothing looked at it.
//
// fhir/LabDeterminations.java carries the same list with the MFB parameter each code feeds, the
// caption sent when a host supplies no display, and the units it accepts; LabDeterminationsTest
// pins the two together.
ValueSet: LabDeterminationVS
Id: lab-determination
Title: "Laboratory determinations medication surveillance reads"
Description: "The LOINC codes that are meaningful on Parameters.parameter:observation — not a restriction on what Observation.code may carry (see LoincVS for that), but the codes the G-Standaard itself attaches to its MFB parameters (BST684T rows with MFBEXSRT = 4, 'LOINC / Nederlandse Labcodeset'), so the rules engine can test what a host sends: eGFR, kalium, INR, sirolimus, natrium and lithium, plus weight and height for dose checking. One eGFR code only — Dutch laboratories report CKD-EPI, so the MDRD and cystatin C codes the G-Standaard also lists are not read here."
* ^status = #draft
* ^experimental = false
* $loinc#62238-1 "Glomerular filtration rate [Volume Rate/Area] in Serum, Plasma or Blood by Creatinine-based formula (CKD-EPI)/1.73 sq M"
* $loinc#2823-3 "Potassium [Moles/volume] in Serum or Plasma"
* $loinc#6298-4 "Potassium [Moles/volume] in Blood"
* $loinc#6301-6 "INR in Platelet poor plasma by Coagulation assay"
* $loinc#34714-6 "INR in Blood by Coagulation assay"
* $loinc#29247-4 "Sirolimus [Mass/volume] in Blood"
* $loinc#2951-2 "Sodium [Moles/volume] in Serum or Plasma"
* $loinc#14334-7 "Lithium [Moles/volume] in Serum or Plasma"
* $loinc#29463-7 "Body weight"
* $loinc#8302-2 "Body height"

// The actual binding on Observation.code: any LOINC code, because unlike the closed lists this
// interface enforces for drugs and allergies, an unread lab value costs nothing to accept — it is
// simply not forwarded. See LabDeterminationVS above for which codes are meaningful.
ValueSet: LoincVS
Id: loinc
Title: "LOINC"
Description: "Any LOINC code. Only the determinations in LabDeterminationVS feed a beslisregel or the dose-band model; see the Lab determinations page."
* ^status = #draft
* ^experimental = false
* include codes from system $loinc

ValueSet: OpiumActVS
Id: opium-act-classification
Title: "Opiumwet classification"
Description: "The Opiumwet codes from G-Standaard BST401T rubriek 72."
* ^status = #draft
* ^experimental = false
* include codes from system GStandaardBijzonderKenmerk
