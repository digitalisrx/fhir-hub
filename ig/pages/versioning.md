How this guide will change once it is published, and what you can build on.

**None of it is in force yet.** Nothing is published at the canonical, no integrator is in
production, and until the first release **every part of this specification may change without
notice** — including what *What is not covered* lists as the only things that can move in a patch.

### Where a version shows up

One number versions the whole guide, stamped on every artifact — so the version in a validator
message is the version of the guide that produced the profile:

```
None of the codings provided are in the value set 'ICPC-1 NL'
(http://spec.digitalis.nl/fhir/ValueSet/icpc-1-nl|0.4.0), ...
```

The running service carries no version in a request or a response: none in the path, none in the
payload, none in a header. A deployment implements one release, and you ask which with
`GET /fhir/evs/metadata` — `software.version`, unauthenticated, so you can check it before you hold
credentials.

**One number covers both FHIR bases**, so a surveillance-only release still moves the number an EVS
integrator reads, and the other way round. Which contract a change belongs to is named in the
[Changelog](changelog.html); the version number cannot carry that news.

### Two addresses per artifact

Canonical URLs are **unversioned** and always resolve to the current release:

```
http://spec.digitalis.nl/fhir/StructureDefinition/fhirhub-FormularySessionInput
```

That is the URL a payload carries, and the one to leave in your code. Every release is *also* kept
at a versioned path, permanently:

```
http://spec.digitalis.nl/fhir/0.4.0/StructureDefinition/fhirhub-FormularySessionInput
```

Point a validator at the versioned path for a build that cannot change under you, at the
unversioned one to find out early that it has. A release is never edited in place, so a versioned
URL is safe to cache forever and the current one is not.

Neither address changes what the running service accepts. Validating against an older release than
the service implements passes on the old rules — a green run that verifies nothing.

### What a version bump means

`major.minor.patch`, and the promise is about **payloads you already send**:

| | Meaning | Examples |
| --- | --- | --- |
| **Patch** | Nothing changes about what is accepted or emitted: a payload that validated before validates now, and no response carries an element your parser has not seen | A clearer sentence, a corrected `description` or `comment` on a profile, a better example, a fixed link |
| **Minor** | Additive. Every request that was accepted before is still accepted, and every response you could already parse still parses | A new optional parameter; a new optional element; a widened `max`; a new code in a Digitalis-minted `CodeSystem`; a new accepted lab determination |
| **Major** | A payload that used to be accepted may now be rejected, or a response may carry something your parser has to be taught | A new required element; a narrowed binding; a removed or renamed parameter; a tightened cardinality; a changed canonical |

A patch can still change the bytes of a `StructureDefinition`, because `description` and `comment`
live inside the profile. The version promises behaviour, not checksums — pin the versioned path if
you need bytes that cannot move.

**The base URL is outside this table.** Moving the endpoints breaks every caller without altering a
payload, and no profile records the base URL, so a move is announced in the
[Changelog](changelog.html) under its own **Breaking** heading and agreed with you first. The
canonicals in your payloads are identifiers, not addresses, and do not move when the service does.

Two consequences worth stating outright:

**A minor release can still break you if you validate strictly outbound.** New optional elements in
a response are a minor change, and a client that rejects unknown elements fails on one. Ignore what
you do not recognise.

**A new parameter name is additive for the service and not for you.** Inbound parameter slicing is
closed, so a name the deployment does not know is a 400 rather than an ignored element (see
[Conventions](conventions.html)). Do not send a parameter from a later release until the deployment
you call reports it in `metadata`.

### While the status is `draft`

`draft` is not a formality: **a breaking change can arrive at a minor version** until the first
`active` release, and without notice at all before the first published one. The three most likely:

- a decision about `meta.profile`, which nothing asserts today — see
  [Current limitations](limitations.html)
- a second eGFR determination, if Dutch laboratories start reporting MDRD or cystatin C — see
  [Lab determinations](lab-determinations.html)
- nl-core parentage, if the Nictiz artifacts this interface would need stop being pre-release

The version becomes `1.0.0` and the status `active` when the first integrator goes to production.
From then the table above is a promise: breaking changes only at a major.

### Deprecation

Nothing is removed without warning. An element or code on its way out is marked deprecated for at
least one minor release, with what to use instead, and removed at the next major at the earliest.
It keeps working while it is present.

### What is *not* covered by any of this

These can change in a patch release, so do not build on them:

- **Error wording.** `OperationOutcome.diagnostics` is written for a person. Branch on the HTTP
  status and on `expression`/`location`; never match on the text. See [Errors](errors.html)
- **The `url` from an open-session call.** Prescriptor's shape, not part of this contract. Redirect
  to it unchanged and read the session id from `sessionId`
- **`meta.profile`.** Not asserted on anything the service emits today. Do not route on it
- **HTML rendering.** `?_format=html` is a convenience for a browser, not an interface

### How you find out

Every release is listed on the [Changelog](changelog.html) with its version, date and changes, and
the versioned snapshot of the release it replaces stays online. Ask Digitalis to be told directly:
a changelog nobody is pointed at is not an announcement.
