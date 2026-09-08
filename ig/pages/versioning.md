How this guide will change once it is published, and what you will be able to build on. Agree the
parts that affect your release process with Digitalis before you go live.

**None of it is in force yet.** Nothing is published at the canonical, no integrator is in
production, and until the first release goes out **every part of this specification may change
without notice** — including the things listed under *What is not covered* below as the only ones
that can move in a patch. The grades and promises on this page take effect with that first
release.

### Where a version shows up

One number versions the whole guide. It is stamped on every artifact, so the version in a validator
message is the version of the guide that produced the profile:

```
None of the codings provided are in the value set 'ICPC-1 NL'
(http://spec.digitalis.nl/fhir/ValueSet/icpc-1-nl|0.4.0), ...
```

The running service carries no version in a request or a response: none in the path, none in the
payload, none in a header. A deployment implements one release of this guide, and the way to ask
which is `GET /fhir/evs/metadata` — the `CapabilityStatement` reports it as `software.version`, and
`implementation.description` names it alongside the canonical. That call is unauthenticated, so you
can check it before you hold credentials.

**One number covers both FHIR bases.** `/fhir/evs` and `/fhir/surveillance` are separate contracts
with separate CapabilityStatements, and they report the same release. So a release that only moves
the surveillance contract still moves the number an EVS integrator reads, and the other way round.
Which contract a change belongs to is named in the [Changelog](changelog.html); the version number
cannot carry that news and does not try to.

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

Point a validator at the versioned path when you want a build that cannot change under you, and at
the unversioned one when you want to find out early that it has. Both serve the same bytes for the
same release, and a release is never edited in place, so a versioned URL is safe to cache forever
and the current one is not.

Neither address changes what the running service accepts. Validating against an older release than
the service implements will not fail — it will pass on the old rules, which is exactly the kind of
green run that verifies nothing. Track the version in `metadata`.

### What a version bump means

`major.minor.patch`, and the promise is about **payloads you already send**:

| | Meaning | Examples |
| --- | --- | --- |
| **Patch** | Nothing changes about what is accepted or emitted: a payload that validated before validates now, and no response carries an element your parser has not seen | A clearer sentence, a corrected `description` or `comment` on a profile, a better example, a fixed link |
| **Minor** | Additive. Every request that was accepted before is still accepted, and every response you could already parse still parses | A new optional parameter; a new optional element; a widened `max`; a new code in a Digitalis-minted `CodeSystem`; a new accepted lab determination |
| **Major** | A payload that used to be accepted may now be rejected, or a response may carry something your parser has to be taught | A new required element; a narrowed binding; a removed or renamed parameter; a tightened cardinality; a changed canonical |

A patch can still change the bytes of a `StructureDefinition`: an element's `description` and
`comment` live inside the profile, so correcting wording alters the artifact without altering a
rule. The version promises behaviour, not checksums — if you need bytes that cannot move, pin the
versioned path.

**The base URL is outside this table.** The grades above are about payloads, and moving the
endpoints breaks every caller without altering one. Nothing in a profile records the base URL, so a
validator cannot warn you and the version number cannot carry the news. A move like that is
announced in the [Changelog](changelog.html) under its own **Breaking** heading and agreed with you
before it goes out. Note that a base URL and a canonical are different things: the canonicals in
your payloads are identifiers rather than addresses, and they do not move when the service does.

Two consequences worth stating outright:

**A minor release can still break you if you validate strictly outbound.** New optional elements in
a response are a minor change, and a client that rejects unknown elements will fail on one. Ignore
what you do not recognise — that is the FHIR rule and it is the rule here.

**A new parameter name is additive for the service and not for you.** Inbound parameter slicing is
closed, so a name the deployment you are talking to does not know is a 400 rather than an ignored
element (see [Conventions](conventions.html)). Do not start sending a parameter introduced in a
minor release until the deployment you call has moved to it, and read the version out of `metadata`
rather than assuming.

### While the status is `draft`

`draft` is not a formality: **a breaking change can arrive at a minor version** until the first
`active` release, and before the first *published* release it can arrive without notice at all.
The three that are most likely once things settle:

- a decision about `meta.profile`, which nothing asserts today — see
  [Current limitations](limitations.html)
- a second eGFR determination, if Dutch laboratories start reporting MDRD or cystatin C — see
  [Lab determinations](lab-determinations.html)
- nl-core parentage, if the Nictiz artifacts this interface would need stop being pre-release

The version becomes `1.0.0` and the status `active` when the first integrator goes to production.
From that point the table above is a promise: breaking changes only at a major.

### Deprecation

Nothing is removed without warning. An element or code on its way out is marked deprecated for at
least one minor release, with what to use instead, and is removed at the next major at the
earliest. A deprecated element keeps working for as long as it is present.

### What is *not* covered by any of this

These can change in a patch release, so do not build on them:

- **Error wording.** `OperationOutcome.diagnostics` is written for a person. Branch on the HTTP
  status and on `expression`/`location`; never match on the text. See [Errors](errors.html)
- **The `url` from an open-session call.** Prescriptor's shape, not part of this contract. Redirect
  to it unchanged and read the session id from `sessionId`
- **`meta.profile`.** Not asserted on anything the service emits today. Do not route on it
- **HTML rendering.** `?_format=html` is a convenience for a browser, not an interface

### How you find out

Every release is listed on the [Changelog](changelog.html) with the version, the date and what
moved, and the versioned snapshot of the release it replaces stays online. Ask Digitalis to be told
directly — with a dozen HIS suppliers and several XIS systems, a changelog nobody is pointed at is
not an announcement.
