-- Stands in for the G-Standaard `medcode` view. Only the columns fhir-hub reads are present.
CREATE TABLE IF NOT EXISTS medcode (
	hpk BIGINT NOT NULL,
	prk BIGINT NOT NULL,
	gpk BIGINT NOT NULL,
	atc VARCHAR(7)
);

DELETE FROM medcode;

-- paracetamol zetpil 1000mg
INSERT INTO medcode (hpk, prk, gpk, atc) VALUES (0, 18996, 111111, 'N02BE01');
-- oxycodon hcl tablet 5mg, known at HPK level too
INSERT INTO medcode (hpk, prk, gpk, atc) VALUES (2106, 43800, 222222, 'N02AA05');
-- a PRK with no GPK: must not resolve
INSERT INTO medcode (hpk, prk, gpk, atc) VALUES (0, 99999, 0, 'N02BE01');
-- a product the G-Standaard carries no ATC for, e.g. a bandage: resolves, and goes out as ZZZZZZ
INSERT INTO medcode (hpk, prk, gpk, atc) VALUES (0, 12345, 333333, '');

-- The three products of the reference case, read out of the live G-Standaard rather than invented,
-- so SurveillanceIntegrationTest can send the published example and get the documented codes.
-- Each of them really appears on several rows, one per packaging, all carrying the same PRK + GPK:
-- metformine on 5, omeprazol on 8. That is what MedicationCodeResolver takes the first row for.
INSERT INTO medcode (hpk, prk, gpk, atc) VALUES (693332, 1090, 3816, 'A10BA02');
INSERT INTO medcode (hpk, prk, gpk, atc) VALUES (873160, 1090, 3816, 'A10BA02');
INSERT INTO medcode (hpk, prk, gpk, atc) VALUES (2310058, 27278, 51004, 'M01AE01');
INSERT INTO medcode (hpk, prk, gpk, atc) VALUES (3018040, 60062, 114529, 'A02BC01');
