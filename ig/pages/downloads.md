### The package

The whole guide as a FHIR NPM package — every profile, extension, value set, code system and
example, and nothing else:

- **[package.tgz](package.tgz)** — `nl.digitalis.fhirhub#0.3.0`

That is the file to hand to a validator, a FHIR server or an IG of your own. The versioned copy
lives at `/fhir/0.3.0/package.tgz` and never changes; the one linked above follows the current
release. See [Versioning and change policy](versioning.html) for which of the two you want.

**Fetch it over `https`.** The canonical URLs in the payloads are `http://spec.digitalis.nl/…` —
that is an identifier, and it stays as it is — but the HL7 validator's SSRF protection refuses a
plain-`http` fetch outright (`Refusing to fetch from non-https URL`), and it is on by default and
should stay on. So use `https://spec.digitalis.nl/…` for anything a tool downloads. Both schemes
serve the same bytes.

Also available:

- **[definitions.json.zip](definitions.json.zip)** — the conformance resources as loose JSON, if
  your tooling does not read packages
- **[full-ig.zip](full-ig.zip)** — this entire site, for reading offline

### Validating a payload

With the HL7 reference validator ([download](https://github.com/hapifhir/org.hl7.fhir.core/releases/latest/download/validator_cli.jar)),
against a profile by canonical URL:

```bash
java -jar validator_cli.jar -version 4.0.1 \
  -ig https://spec.digitalis.nl/fhir/package.tgz \
  -profile http://spec.digitalis.nl/fhir/StructureDefinition/fhirhub-FormularySessionInput \
  my-request.json
```

Note the two schemes, and that the difference is not a typo: `-ig` is a URL to **fetch**, so it is
`https`; `-profile` is the canonical **identifier** of the profile, which is `http` and is matched
against what the package declares rather than being downloaded.

Pin the release instead of following it by naming the versioned package —
`-ig https://spec.digitalis.nl/fhir/0.3.0/package.tgz` — or by downloading the tarball and
pointing `-ig` at the local file, which is also what to do on a machine without outbound network
access.

**Do not rely on network resolution alone.** A validator that cannot fetch a profile reports it
as *not checked* rather than as a failure, so a run that resolved nothing still ends in a green
`0 errors`. Load the package explicitly, as above, and confirm the validator logs
`Load nl.digitalis.fhirhub#0.3.0` before you believe the result.

### Warnings you should expect

A conformant payload does not validate clean, and it cannot. These warnings are normal and are
not something to fix:

| Warning | Why |
| --- | --- |
| `Code System ... could not be found, so the code cannot be validated` | The G-Standaard tables are licensed and are not distributed with these profiles. The `system` is checked; the code is not. See [Code systems](code-systems.html) |
| `Unable to expand ValueSet` for a G-Standaard binding | Same reason — a value set that includes an undistributed system cannot be expanded |
| `Constraint failed: dom-6 ... should have narrative` | This interface builds no narrative. Nothing reads one, and one generated for a machine-to-machine payload is filler |
| `Reference ... has no display` | The `data-absent-reason` idiom on `subject` and `patient`. There is nothing to display: no `Patient` resource is contained or referenced |

Only `error` and `fatal` matter. That is also exactly how the running service decides: warnings do
not reject a request, errors do. See [Errors](errors.html).

### The FSH source

The profiles are written in [FHIR Shorthand](https://hl7.org/fhir/uv/shorthand/) and built with
SUSHI. Ask Digitalis for the source if you want to derive from these profiles rather than
validate against them.
