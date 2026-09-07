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
