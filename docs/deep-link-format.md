# Shareable request deep-link format

This is the authoritative wire-format contract for the explorer's **Copy link** feature (shareable
request deep-links). It is documented so other tooling can produce or consume compatible links. The
format is client-only: the request travels in the URL **fragment** and never reaches the server.

The reference implementation is `spyglass-core/.../apidocs/js/shareLink.js`.

## URL shape

A shared link is the explorer's own URL with the request encoded into the fragment, after the existing
operation anchor:

```
<explorer-url>?<config-query>#<METHOD>-<path>&s=<encoded>
```

- The **query string** (`spec`, `ns`, `ext`, …) is carried unchanged so the recipient loads the same
  configuration. It is not part of this format.
- `#<METHOD>-<path>` is the existing selection anchor (e.g. `#GET-/pets/{id}`). `<path>` is raw (not
  percent-encoded), matching how the explorer writes the anchor.
- `&s=` (`STATE_SEP`) separates the anchor from the encoded request. `&` cannot occur in an OpenAPI path
  template, so the split is unambiguous.
- `<encoded>` is the encoded request state, described below. It is absent for a plain (no-state) anchor.

## `<encoded>`

```
<encoded> = <codec> <version> <base64url>
```

| Field | Length | Values | Meaning |
| --- | --- | --- | --- |
| `<codec>` | 1 char | `D` \| `R` | `D` = the bytes are `deflate-raw` compressed; `R` = raw (uncompressed). |
| `<version>` | 1 char | `1` | Payload format version. A decoder MUST ignore a link whose version it does not recognize. |
| `<base64url>` | rest | `[A-Za-z0-9-_]`, no padding | base64url of the codec's bytes. |

- `<base64url>` decodes to a byte string. If `<codec>` is `D`, inflate it with raw DEFLATE
  (`CompressionStream('deflate-raw')` on the web) to recover the payload bytes; if `R`, the bytes are the
  payload directly.
- The payload bytes are a **UTF-8 JSON** document (below).
- A producer SHOULD emit `D` only when compression actually shrinks the payload, and `R` otherwise — on a
  small request DEFLATE expands, and base64 already adds ~⅓, so raw is shorter. A consumer must accept
  either regardless.

## Payload JSON

A single object. **Every field is optional and omitted when empty/default** — a producer must not emit
empty values, and a consumer must treat any absent field as its default.

| Key | Type | Default | Meaning |
| --- | --- | --- | --- |
| `p` | object | `{}` | Request parameters, as `{ "<in>:<name>": <string> }`. `<in>` is abbreviated: `q`=query, `p`=path, `h`=header, `c`=cookie. Example: `{ "q:limit": "20", "p:id": "7" }`. |
| `m` | string | `""` | Request body media type (e.g. `application/json`). |
| `u` | `1` | `false` (form mode) | Present as `1` when the body is the raw editor's verbatim text rather than the structured form. |
| `r` | string | `""` | Raw body text. Carried only in raw mode (`u`) or for non-form body types. |
| `b` | any | *(absent)* | Request body value (parsed JSON for a JSON body; an object for form/multipart). Absent for operations with no body. |
| `h` | array | `[]` | Non-secret header rows, as `[ [<name>, <value>], … ]`. Example: `[ ["X-Sandbox", "sb1"] ]`. |

Example — `GET /widgets/{id}?limit=20` with one custom header:

```json
{ "p": { "p:id": "7", "q:limit": "20" }, "h": [ ["X-Sandbox", "sb1"] ] }
```

## Security: what a producer MUST exclude

Auth material must never appear in a shared link. Before encoding, a producer MUST drop:

- The `Authorization` header row (and any header parameter named `Authorization`, case-insensitively).
- Any request field the spec maps to an `apiKey` security scheme — matched by location + name from
  `components.securitySchemes` (header names case-insensitively; query/cookie names exactly). Every
  declared `apiKey` scheme is treated as auth, whether or not an operation references it.

`bearer` / `basic` / `oauth2` / `openIdConnect` schemes all ride the `Authorization` header and are
excluded with it. The exclusion happens at payload-build time, so a secret never reaches the encoder.

## Consuming a link (rehydration)

- Selection comes from the anchor; the payload rehydrates the form best-effort against the **current**
  schema — fields that no longer exist are dropped, newly-required ones surface as non-blocking warnings.
- The link's header set **replaces** the recipient's header rows, except the recipient's own
  `Authorization` row is preserved (the link never carried it).
- A malformed, truncated, or unknown-version payload decodes to nothing and is ignored; the operation
  still opens from its anchor.
- Producers should keep the whole link within the explorer's configured size cap (`shareLink.maxUrl`,
  default 4000 characters — see [configuration.md](configuration.md#shareable-request-links)); over it the
  explorer refuses to emit a link.
