package org.plukh.spyglass.demo;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.swagger.v3.oas.annotations.ExternalDocumentation;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.DiscriminatorMapping;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Builder;
import lombok.Value;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Opt-in demo/showcase for the explorer, contributed via {@link DemoEndpointsConfiguration} (gated on
 * {@code apidocs.demo.enabled=true}) for consumers that don't component-scan this package.
 *
 * <p>It exercises explorer features the host service's real spec may not contain: deprecated-operation
 * and dropped-metadata surfacing (deprecated banner, parameter descriptions + markers, externalDocs
 * links, response-header docs, deprecated schema fields), oneOf/anyOf/discriminator variant forms,
 * non-JSON request bodies (multipart file upload, URL-encoded forms), binary/image responses, and
 * spec-provided named {@code examples} (request body, parameters and responses, including an
 * external-value example). The upload endpoints echo back the received file names and byte counts so a
 * real upload can be verified end-to-end. It travels with the explorer as an opt-in component.
 */
@RestController
@RequestMapping(value = "/apidocs-demo", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "API explorer demo", description = "Opt-in demo endpoints showcasing the API explorer (apidocs.demo.enabled).")
public class DemoController {

    @Operation(
            summary = "Deprecated demo endpoint",
            description = "Demo — demonstrates the deprecated banner, externalDocs link and response-header docs.",
            deprecated = true,
            externalDocs = @ExternalDocumentation(description = "Developer docs", url = "https://example.invalid/docs"))
    @ApiResponse(
            responseCode = "200",
            description = "A demo payload.",
            headers = @Header(name = "X-Demo-Trace-Id", description = "Correlation id for this response.",
                    schema = @Schema(type = "string")))
    @GetMapping
    public DemoResponse demo(
            @Parameter(description = "A user UID. **Deprecated** — prefer the auth context.", deprecated = true)
            @RequestParam(value = "uid", required = false) String uid) {
        return DemoResponse.builder().value("demo").legacyValue("old").build();
    }

    @Operation(
            summary = "Create a shape (oneOf + discriminator)",
            description = "Demo — exercises the variant selector with a discriminator: pick circle/square and the "
                    + "discriminator field prefills (but stays editable).")
    @PostMapping("/shapes")
    public Shape createShape(@RequestBody Shape shape) {
        return shape;
    }

    @Operation(
            summary = "Replace a shape (PUT + path parameter)",
            description = "Demo — a full replace by id: a path parameter alongside the discriminated `oneOf` "
                    + "body, echoing the updated shape back.")
    @PutMapping("/shapes/{id}")
    public Shape replaceShape(
            @Parameter(description = "The id of the shape to replace.") @PathVariable("id") String id,
            @RequestBody Shape shape) {
        return shape;
    }

    @Operation(
            summary = "Delete a shape (204 No Content)",
            description = "Demo — deletes by id and returns an empty `204 No Content`, so Try It Out exercises "
                    + "no-body response rendering.")
    @ApiResponse(responseCode = "204", description = "The shape was deleted; no body is returned.")
    @DeleteMapping("/shapes/{id}")
    public ResponseEntity<Void> deleteShape(
            @Parameter(description = "The id of the shape to delete.") @PathVariable("id") String id) {
        return ResponseEntity.noContent().build();
    }

    @Operation(
            operationId = "com.example.api.billing.invoices.resource.InternalInvoiceLineItemResource.deleteInvoiceLineItemAttachments",
            summary = "Long operation ID — tail-match snippet windowing demo",
            description = "Demo — carries a long, fully-qualified operationId whose matched method fragment sits at "
                    + "the tail. Filter the sidebar by `attachments` (a token unique to the operationId — the short "
                    + "path deliberately doesn't contain it) and drag the divider in: the operationId snippet windows "
                    + "around the match so the found marker stays visible instead of being clipped off the right edge.")
    @ApiResponse(responseCode = "204", description = "The resource was deleted; no body is returned.")
    @DeleteMapping("/long-operation-id/{id}")
    public ResponseEntity<Void> deleteInvoiceLineItemAttachments(
            @Parameter(description = "The id of the resource to delete.") @PathVariable("id") String id) {
        return ResponseEntity.noContent().build();
    }

    @Operation(
            summary = "Send a payload (anyOf)",
            description = "Demo — exercises the multi-branch anyOf form: both object branches can be checked at "
                    + "once and the body merges them (a scalar anyOf would stay single-select instead).")
    @PostMapping("/payloads")
    public PayloadRequest createPayload(@RequestBody PayloadRequest request) {
        return request;
    }

    @Operation(
            summary = "Save settings (fixed properties + additionalProperties)",
            description = "Demo — a typed object that also accepts arbitrary extra string entries. The form shows the "
                    + "declared fields **and** an additional-properties map editor; both are sent and echoed back.")
    @PostMapping("/settings")
    public Settings saveSettings(@RequestBody Settings settings) {
        return settings;
    }

    @Operation(
            summary = "Register a conveyance (deep allOf + discriminator)",
            description = "Demo — a discriminated `oneOf` whose base **also** extends a common `Vehicle` root via "
                    + "`allOf`. Each branch's form inherits the root's `vin` field, not just the base's own "
                    + "properties — pick car/bike and the inherited fields appear alongside the branch-specific ones.")
    @PostMapping("/conveyances")
    public Conveyance registerConveyance(@RequestBody Conveyance conveyance) {
        return conveyance;
    }

    @Operation(
            summary = "Wide form (~50 fields) — render/keyboard performance probe",
            description = "Demo — a deliberately large request body of mixed field kinds, for checking that "
                    + "form rendering and arrow-key navigation stay smooth on a complex operation.")
    @PostMapping("/wide")
    public WideForm submitWide(@RequestBody WideForm form) {
        return form;
    }

    @Operation(
            summary = "Return a chosen HTTP outcome",
            description = "Demo — pick an `outcome` and the endpoint replies with that HTTP status and a "
                    + "matching body, so Try It Out exercises 2xx **and** non-2xx response rendering. The "
                    + "Schema tab lists every documented status.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The request succeeded.",
                    content = @Content(schema = @Schema(implementation = OutcomePayload.class),
                            examples = @ExampleObject(value = "{\"outcome\": \"OK\", \"message\": \"Everything worked.\"}"))),
            @ApiResponse(responseCode = "302", description = "Redirects to the OK outcome (the browser follows it automatically).",
                    headers = @Header(name = "Location", description = "The URL to follow.",
                            schema = @Schema(type = "string"))),
            @ApiResponse(responseCode = "400", description = "The request was invalid.",
                    content = @Content(schema = @Schema(implementation = ApiError.class),
                            examples = @ExampleObject(value = "{\"code\": \"BAD_REQUEST\", \"message\": \"The outcome you chose maps to HTTP 400.\"}"))),
            @ApiResponse(responseCode = "404", description = "Nothing matched the request.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "409", description = "The request conflicted with current state.",
                    content = @Content(schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "500", description = "Something went wrong on the server.",
                    content = @Content(schema = @Schema(implementation = ApiError.class)))
    })
    @GetMapping("/outcomes")
    public ResponseEntity<Object> outcomes(
            @Parameter(description = "Which HTTP outcome the endpoint should return.")
            @RequestParam(value = "outcome", defaultValue = "OK") Outcome outcome) {
        if (outcome == Outcome.OK) {
            return ResponseEntity.ok(OutcomePayload.builder()
                    .outcome(outcome.name())
                    .message("Everything worked.")
                    .build());
        }
        if (outcome == Outcome.REDIRECT) {
            return ResponseEntity.status(HttpStatus.FOUND)
                    .location(URI.create("/apidocs-demo/outcomes?outcome=OK"))
                    .build();
        }
        return ResponseEntity.status(outcome.status).body(ApiError.builder()
                .code(outcome.name())
                .message("The outcome you chose maps to HTTP " + outcome.status.value() + ".")
                .build());
    }

    @Operation(
            summary = "Respond after a delay (try the Cancel button)",
            description = "Demo — waits `seconds` (clamped to 0–30, default 5) before responding, so a "
                    + "long-running request can be started and then cancelled from the explorer's "
                    + "**Cancel** button while it is in flight. The wait is server-side; cancelling aborts "
                    + "the browser request — the server still finishes its wait.")
    @ApiResponse(responseCode = "200", description = "The delayed response, reporting the actual wait.")
    @GetMapping("/slow")
    public SlowResponse slow(
            @Parameter(description = "How long to wait before responding, in seconds (clamped to 0–30).")
            @RequestParam(value = "seconds", defaultValue = "5") int seconds) {
        long waitMs = Math.clamp(seconds, 0, 30) * 1000L;
        try {
            Thread.sleep(waitMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return SlowResponse.builder()
                .waitedMs(waitMs)
                .message("Responded after " + waitMs + " ms.")
                .build();
    }

    @Operation(
            summary = "Mirror a status code (response-body transformer probe)",
            description = "Demo — echoes an integer status code straight back. The demo extension registers a "
                    + "response-body transformer that decodes this code into a label (1=ACTIVE, 2=PENDING, "
                    + "3=CLOSED; anything else → UNKNOWN) behind the response panel's **Decoded** toggle, while "
                    + "leaving the sibling fields untouched. Toggle **Decoded** off to see the raw code again.")
    @ApiResponse(responseCode = "200", description = "The echoed status code plus untouched sibling fields.")
    @GetMapping("/mirror")
    public MirrorResponse mirror(
            @Parameter(description = "A status code to echo (try 1, 2, 3, or any other integer).")
            @RequestParam(value = "status", defaultValue = "1") int status) {
        return MirrorResponse.builder()
                .status(status)
                .count(status * 10)
                .note("unchanged")
                .build();
    }

    @Operation(
            summary = "Return a large JSON response (pretty-print size-limit probe)",
            description = "Demo — returns a JSON body of roughly `kb` kilobytes (default 2200, just over the "
                    + "explorer's ~2 MB threshold), so the response view falls back to plain, unformatted text "
                    + "instead of parsing, pretty-printing and syntax-highlighting a multi-megabyte body.")
    @ApiResponse(responseCode = "200", description = "A large JSON array, sized to exceed the explorer's pretty-print limit.")
    @GetMapping(value = "/large", produces = MediaType.APPLICATION_JSON_VALUE)
    public String large(
            @Parameter(description = "Approximate response size in kilobytes (clamped to 1–8192; default 2200).")
            @RequestParam(value = "kb", defaultValue = "2200") int kb) {
        int target = Math.clamp(kb, 1, 8192) * 1024;
        // Build valid, multi-line (pretty) JSON by hand so the body carries real newlines: many medium
        // lines render far more smoothly than one multi-megabyte line once the explorer shows it as text.
        String filler = "x".repeat(100);
        StringBuilder sb = new StringBuilder(target + 128);
        sb.append("{\n  \"note\": \"A large JSON response — it may render unformatted if it exceeds the explorer's pretty-print limit (~2 MB).\",\n  \"items\": [\n");
        int i = 0;
        while (sb.length() < target) {
            sb.append("    \"row-").append(String.format("%07d", i++)).append('-').append(filler).append("\",\n");
        }
        sb.append("    \"end\"\n  ]\n}\n");
        return sb.toString();
    }

    @Operation(
            summary = "Echo a payload (named examples everywhere)",
            description = "Demo — showcases spec-provided named `examples`: request-body examples (named, "
                    + "described and one external-value), parameter examples (a named map and a singular "
                    + "example), and multiple named response examples. The body is echoed back.")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            description = "The payload to echo back.",
            content = @Content(
                    schema = @Schema(implementation = ExamplePayload.class),
                    examples = {
                            @ExampleObject(name = "minimal", summary = "Just the required field",
                                    value = "{\"name\": \"widget\"}"),
                            @ExampleObject(name = "full", summary = "Every field populated",
                                    description = "A fully populated payload — name, count and tags.",
                                    value = "{\"name\": \"deluxe widget\", \"count\": 7, \"tags\": [\"alpha\", \"beta\"]}"),
                            @ExampleObject(name = "external", summary = "Loaded from a URL",
                                    description = "Has no inline value — the explorer shows the link rather than loading it.",
                                    externalValue = "https://example.invalid/sample-payload.json")
                    }))
    @ApiResponse(responseCode = "200", description = "The echoed payload.",
            content = @Content(
                    schema = @Schema(implementation = ExamplePayload.class),
                    examples = {
                            @ExampleObject(name = "created", summary = "A freshly created payload",
                                    value = "{\"name\": \"widget\", \"count\": 1, \"tags\": [\"new\"]}"),
                            @ExampleObject(name = "archived", summary = "An archived payload",
                                    value = "{\"name\": \"old widget\", \"count\": 0, \"tags\": [\"archived\"]}")
                    }))
    @PostMapping("/examples")
    public ExamplePayload echoExample(
            @Parameter(description = "A filter expression.", examples = {
                    @ExampleObject(name = "by-name", summary = "Match by name",
                            description = "Matches records whose name contains the given substring. "
                                    + "The comparison is case-insensitive and ignores surrounding whitespace.",
                            value = "acme"),
                    @ExampleObject(name = "by-id", summary = "Match by id", value = "42")
            })
            @RequestParam(value = "filter", required = false) String filter,
            @Parameter(description = "Tags to filter by (repeatable).", examples = {
                    @ExampleObject(name = "single", summary = "One tag", value = "[\"featured\"]"),
                    @ExampleObject(name = "multiple", summary = "Several tags",
                            description = "Records must carry **all** of the listed tags. "
                                    + "Order is not significant and duplicates are ignored.",
                            value = "[\"featured\", \"in-stock\", \"on-sale\"]")
            })
            @RequestParam(value = "tags", required = false) List<String> tags,
            @Parameter(description = "Maximum results.", example = "20")
            @RequestParam(value = "limit", required = false) Integer limit,
            @RequestBody ExamplePayload payload) {
        return payload;
    }

    @Operation(
            summary = "Exchange credentials for a session (Request Log sanitizer showcase)",
            description = "Demo — every request and response surface carries a secret, so it exercises the "
                    + "Request Log **sanitizer seam**. The bundled sample extension registers a sanitizer that "
                    + "redacts each one before the call is persisted: the `apiKey` query value (in the URL and the "
                    + "replay snapshot), the `X-Demo-Api-Key` request header, the `secret` request-body field, the "
                    + "`X-Demo-Session` response header and the `sessionToken` response-body field. The "
                    + "non-sensitive `note` is echoed back untouched, so the redaction is visibly surgical.")
    @ApiResponse(
            responseCode = "200",
            description = "A session payload, with the sensitive session id also returned as a response header.",
            headers = @Header(name = "X-Demo-Session", description = "An opaque session id for this exchange (sensitive).",
                    schema = @Schema(type = "string")))
    @PostMapping("/secrets")
    public ResponseEntity<SecretResponse> exchangeSecret(
            @Parameter(description = "An API key sent as a **query parameter** — a credential that must never be logged.")
            @RequestParam(value = "apiKey", required = false) String apiKey,
            @Parameter(description = "An API key sent as a **request header** — a credential that must never be logged.",
                    in = ParameterIn.HEADER)
            @RequestHeader(value = "X-Demo-Api-Key", required = false) String apiKeyHeader,
            @RequestBody SecretRequest request) {
        return ResponseEntity.ok()
                .header("X-Demo-Session", "demo-session-id-DO-NOT-LOG")
                .body(SecretResponse.builder()
                        .message("Exchanged credentials for a session.")
                        .sessionToken("demo-session-token-DO-NOT-LOG")
                        .note(request != null ? request.getNote() : null)
                        .build());
    }

    // The multipart body schema is declared explicitly (a doc-only DTO) because springdoc otherwise
    // emits the plain text part as a query parameter rather than a field of the multipart body. The
    // text part is bound via @RequestParam (hidden from the docs so it isn't duplicated as a query).
    @Operation(
            summary = "Upload a single file (multipart/form-data)",
            description = "Demo — exercises a single file upload with a text field; echoes back the file name and "
                    + "byte count so the upload can be verified.")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            content = @Content(mediaType = MediaType.MULTIPART_FORM_DATA_VALUE, schema = @Schema(implementation = SingleUploadForm.class)))
    @PostMapping(value = "/upload/single", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public UploadResult uploadSingle(
            @RequestParam("file") MultipartFile file,
            @Parameter(hidden = true) @RequestParam(value = "note", required = false) String note) {
        return summarize(note, file);
    }

    @Operation(
            summary = "Upload multiple files (multipart/form-data)",
            description = "Demo — exercises a multi-file upload with a text field; echoes back each file name and the "
                    + "total byte count so the upload can be verified.")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            content = @Content(mediaType = MediaType.MULTIPART_FORM_DATA_VALUE, schema = @Schema(implementation = MultiUploadForm.class)))
    @PostMapping(value = "/upload/multiple", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public UploadResult uploadMultiple(
            @RequestParam("files") List<MultipartFile> files,
            @Parameter(hidden = true) @RequestParam(value = "note", required = false) String note) {
        return summarize(note, files.toArray(new MultipartFile[0]));
    }

    @Operation(
            summary = "Submit a form (application/x-www-form-urlencoded)",
            description = "Demo — exercises a URL-encoded form body; echoes back the received fields.")
    @PostMapping(value = "/form", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public FormResult submitForm(
            @RequestParam("name") String name,
            @RequestParam(value = "count", required = false) Integer count) {
        return FormResult.builder()
                .message("Received form: name=" + name + ", count=" + count)
                .name(name)
                .count(count)
                .build();
    }

    @Operation(
            summary = "Download a PNG image",
            description = "Demo — returns a small generated PNG (with Content-Disposition) to exercise the response "
                    + "image preview and the correct-bytes download.")
    @ApiResponse(responseCode = "200", description = "A PNG image.",
            content = @Content(mediaType = MediaType.IMAGE_PNG_VALUE))
    @GetMapping(value = "/download/image", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> downloadImage() {
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"demo.png\"")
                .body(generatePng());
    }

    @Operation(
            summary = "Download a binary blob",
            description = "Demo — returns an octet-stream (with Content-Disposition) to exercise the binary notice "
                    + "and download naming.")
    @ApiResponse(responseCode = "200", description = "A binary payload.",
            content = @Content(mediaType = MediaType.APPLICATION_OCTET_STREAM_VALUE))
    @GetMapping(value = "/download/blob", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<byte[]> downloadBlob() {
        // A small blob mixing printable text with genuinely non-text bytes (NUL, control and high
        // bytes), to exercise the explorer's binary/octet-stream response rendering and download.
        byte[] bytes = {
                'b', 'i', 'n', 'a', 'r', 'y', ' ', 'p', 'a', 'y', 'l', 'o', 'a', 'd', ' ',
                0x00, 0x01, 0x02, (byte) 0xFF, (byte) 0xFE,
                ' ', 'b', 'y', 't', 'e', 's'
        };
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"sample.bin\"")
                .body(bytes);
    }

    /** Generates a small PNG (offscreen, headless-safe) so the explorer has a real image to preview. */
    private byte[] generatePng() {
        BufferedImage image = new BufferedImage(120, 80, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(0x2E, 0x86, 0xC1));
        g.fillRect(0, 0, 120, 80);
        g.setColor(Color.WHITE);
        g.drawString("apidocs", 30, 45);
        g.dispose();
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Echoes back the received multipart files (names, content types, sizes) and the total byte count. */
    private UploadResult summarize(String note, MultipartFile... files) {
        List<FileInfo> infos = new ArrayList<>();
        long total = 0;
        for (MultipartFile file : files) {
            if (file == null || file.isEmpty()) {
                continue;
            }
            long size = file.getSize();
            total += size;
            infos.add(FileInfo.builder()
                    .name(file.getOriginalFilename())
                    .contentType(file.getContentType())
                    .size(size)
                    .build());
        }
        String message = "Received " + infos.size() + " file(s), " + total + " bytes total"
                + (note != null ? "; note=\"" + note + "\"" : "");
        return UploadResult.builder()
                .message(message)
                .note(note)
                .fileCount(infos.size())
                .totalBytes(total)
                .files(infos)
                .build();
    }

    /**
     * Demo response payload.
     */
    @Value
    @Builder
    public static class DemoResponse {

        @Schema(description = "The current value.")
        String value;

        @Schema(description = "A legacy value.", deprecated = true)
        String legacyValue;
    }

    /**
     * Demo — a small payload used by the named-examples showcase, as both the request and response
     * body of {@code POST /examples}.
     */
    @Value
    public static class ExamplePayload {

        @Schema(description = "The payload name.", example = "widget")
        String name;

        @Schema(description = "An optional count.", example = "5")
        Integer count;

        @Schema(description = "Status — has a default, so it prefills the field (unlike an example, which only hints).",
                defaultValue = "active")
        String status;

        @ArraySchema(arraySchema = @Schema(description = "Optional tags.", example = "[\"a\", \"b\"]"), schema = @Schema(type = "string"))
        List<String> tags;
    }

    /**
     * Demo — the request body of {@code POST /secrets}: a sensitive field the sanitizer redacts and a
     * non-sensitive one it leaves untouched.
     */
    @Value
    public static class SecretRequest {

        @Schema(description = "A sensitive value (e.g. a password or token) — redacted from the Request Log.",
                example = "hunter2")
        String secret;

        @Schema(description = "A non-sensitive note — kept verbatim in the Request Log.", example = "exchange for staging")
        String note;
    }

    /**
     * Demo — the response body of {@code POST /secrets}: a sensitive session token the sanitizer redacts
     * and the echoed non-sensitive note.
     */
    @Value
    @Builder
    public static class SecretResponse {

        @Schema(description = "Human-readable confirmation.")
        String message;

        @Schema(description = "An opaque session token — sensitive, redacted from the Request Log.")
        String sessionToken;

        @Schema(description = "The non-sensitive note, echoed back.")
        @Nullable String note;
    }

    /**
     * Demo — a discriminated polymorphic body. The Jackson annotations make the {@code kind} property
     * select the concrete subtype at runtime; the matching springdoc {@code @Schema} attributes emit
     * the {@code oneOf} + {@code discriminator} (with mapping) the explorer renders as a variant
     * selector.
     */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "kind", visible = true)
    @JsonSubTypes({
            @JsonSubTypes.Type(value = Circle.class, name = "circle"),
            @JsonSubTypes.Type(value = Square.class, name = "square")
    })
    @Schema(
            description = "A shape — a discriminated oneOf of circle/square.",
            discriminatorProperty = "kind",
            oneOf = {Circle.class, Square.class},
            discriminatorMapping = {
                    @DiscriminatorMapping(value = "circle", schema = Circle.class),
                    @DiscriminatorMapping(value = "square", schema = Square.class)
            })
    public interface Shape {

        String getKind();
    }

    /**
     * Demo — the "circle" shape branch.
     */
    // @Value (not @Builder) — Jackson binds it through the public all-args constructor (see -parameters
    // in the build); these request-body types are only echoed, never built, so no builder is needed.
    @Value
    public static class Circle implements Shape {

        @Schema(description = "Discriminator value — always \"circle\".")
        String kind;

        @Schema(description = "Radius in pixels.")
        Integer radius;
    }

    /**
     * Demo — the "square" shape branch.
     */
    @Value
    public static class Square implements Shape {

        @Schema(description = "Discriminator value — always \"square\".")
        String kind;

        @Schema(description = "Side length in pixels.")
        Integer side;
    }

    /**
     * Demo — a body with a non-discriminated anyOf field (either text or a number wrapper).
     */
    // A record (not @Value) — a single-field immutable type, where a record binds properties-based on
    // both Jackson majors; a one-arg constructor would instead be treated as delegating.
    public record PayloadRequest(
            @Schema(description = "Either a text or a number payload.", anyOf = {TextPayload.class, NumberPayload.class})
            Object value) {
    }

    /**
     * Demo — the text branch of the anyOf payload.
     */
    @Value
    @Builder
    public static class TextPayload {

        @Schema(description = "Free-form text.")
        String text;
    }

    /**
     * Demo — the number branch of the anyOf payload.
     */
    @Value
    @Builder
    public static class NumberPayload {

        @Schema(description = "A numeric amount.")
        Integer amount;
    }

    /**
     * Demo — a typed object that also permits arbitrary extra string keys (fixed properties +
     * additionalProperties). A plain mutable bean (not {@code @Value}) so Jackson's {@code @JsonAnySetter}
     * can collect the unknown keys and {@code @JsonAnyGetter} echo them back; that pairing is also what
     * makes springdoc emit {@code properties} alongside {@code additionalProperties} for this schema.
     */
    @Schema(description = "A settings envelope — fixed fields plus arbitrary extra string entries.",
            additionalProperties = Schema.AdditionalPropertiesValue.TRUE)
    public static class Settings {

        @Schema(description = "The settings profile id.", requiredMode = Schema.RequiredMode.REQUIRED)
        private @Nullable String id;

        @Schema(description = "An optional display label.")
        private @Nullable String label;

        private final Map<String, String> extra = new LinkedHashMap<>();

        public @Nullable String getId() {
            return id;
        }

        public void setId(@Nullable String id) {
            this.id = id;
        }

        public @Nullable String getLabel() {
            return label;
        }

        public void setLabel(@Nullable String label) {
            this.label = label;
        }

        // Hidden from the schema: Jackson flattens these entries to top-level keys at runtime (so the
        // echo round-trips), but in the document they're represented by the type-level additionalProperties
        // above, not as a nested "extra" property.
        @JsonAnyGetter
        @Schema(hidden = true)
        public Map<String, String> getExtra() {
            return extra;
        }

        @JsonAnySetter
        public void putExtra(String key, String value) {
            extra.put(key, value);
        }
    }

    /**
     * Demo — the common root of the {@link Conveyance} hierarchy. {@code Conveyance} extends it, so the
     * generated base schema carries an {@code allOf} reference to this root; the explorer's form lifts
     * the root's {@code vin} into every concrete branch (see {@link #registerConveyance}).
     */
    @Schema(description = "The common vehicle root.")
    public interface Vehicle {

        @Schema(description = "Vehicle identification number (from the common root).", requiredMode = Schema.RequiredMode.REQUIRED)
        String getVin();
    }

    /**
     * Demo — a discriminated polymorphic body whose base also extends the {@link Vehicle} root. The
     * springdoc {@code @Schema} emits the {@code oneOf} + {@code discriminator} (the variant selector)
     * and the {@code allOf} to {@code Vehicle} (the inherited root field) on the same base schema.
     */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "mode", visible = true)
    @JsonSubTypes({
            @JsonSubTypes.Type(value = CarV.class, name = "car"),
            @JsonSubTypes.Type(value = BikeV.class, name = "bike")
    })
    @Schema(
            description = "A conveyance — a discriminated oneOf of car/bike that also extends the Vehicle root.",
            allOf = {Vehicle.class},
            discriminatorProperty = "mode",
            oneOf = {CarV.class, BikeV.class},
            discriminatorMapping = {
                    @DiscriminatorMapping(value = "car", schema = CarV.class),
                    @DiscriminatorMapping(value = "bike", schema = BikeV.class)
            })
    public interface Conveyance extends Vehicle {

        String getMode();
    }

    /**
     * Demo — the "car" conveyance branch.
     */
    @Value
    public static class CarV implements Conveyance {

        @Schema(description = "Vehicle identification number (from the common root).")
        String vin;

        @Schema(description = "Discriminator value — always \"car\".")
        String mode;

        @Schema(description = "Number of doors.")
        Integer doors;
    }

    /**
     * Demo — the "bike" conveyance branch.
     */
    @Value
    public static class BikeV implements Conveyance {

        @Schema(description = "Vehicle identification number (from the common root).")
        String vin;

        @Schema(description = "Discriminator value — always \"bike\".")
        String mode;

        @Schema(description = "Number of gears.")
        Integer gears;
    }

    /**
     * Demo — a deliberately wide request body (~50 fields of mixed kinds, plus a nested object) used as
     * a performance probe for form rendering and keyboard navigation on a complex operation.
     */
    @Value
    public static class WideForm {

        String name;
        String title;
        String summary;
        String description;
        String category;
        String subcategory;
        String code;
        String reference;
        String externalId;
        String slug;
        String label;
        String group;
        String owner;
        String department;
        String region;
        String country;
        String city;
        String street;
        String postalCode;
        String phone;

        @Schema(format = "email")
        String email;

        @Schema(format = "uri")
        String website;

        @Schema(format = "uuid")
        String correlationId;

        @Schema(format = "date")
        String startDate;

        @Schema(format = "date-time")
        String createdAt;

        Integer count;
        Integer quantity;
        Integer priority;
        Integer weight;
        Integer height;
        Integer width;
        Integer depth;
        Integer score;
        Integer rank;
        Integer version;

        Boolean active;
        Boolean archived;
        Boolean featured;
        Boolean hidden;
        Boolean locked;
        Boolean verified;

        @Schema(allowableValues = {"LOW", "MEDIUM", "HIGH"})
        String severity;

        @Schema(allowableValues = {"NEW", "OPEN", "CLOSED"})
        String state;

        @Schema(allowableValues = {"RED", "GREEN", "BLUE"})
        String color;

        Double amount;
        Double rate;

        @ArraySchema(schema = @Schema(type = "string"))
        List<String> tags;

        @ArraySchema(schema = @Schema(type = "string"))
        List<String> notes;

        @Schema(description = "A nested object, exercising recursive form rendering.")
        WideAddress address;
    }

    /**
     * Demo — the nested object of {@link WideForm}.
     */
    @Value
    public static class WideAddress {

        String line1;
        String line2;
        String city;
        String postalCode;
    }

    /**
     * Demo — the HTTP outcome to return from {@code GET /outcomes}; each maps to a status code so Try
     * It Out can exercise 2xx and non-2xx rendering.
     */
    public enum Outcome {

        OK(HttpStatus.OK),
        REDIRECT(HttpStatus.FOUND),
        BAD_REQUEST(HttpStatus.BAD_REQUEST),
        NOT_FOUND(HttpStatus.NOT_FOUND),
        CONFLICT(HttpStatus.CONFLICT),
        SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR);

        private final HttpStatus status;

        Outcome(HttpStatus status) {
            this.status = status;
        }
    }

    /**
     * Demo — the success body of {@code GET /outcomes}.
     */
    @Value
    @Builder
    public static class OutcomePayload {

        @Schema(description = "The outcome that was returned.")
        String outcome;

        @Schema(description = "A human-readable message.")
        String message;
    }

    /**
     * Demo — the delayed response of {@code GET /slow}.
     */
    @Value
    @Builder
    public static class SlowResponse {

        @Schema(description = "How long the server waited before responding, in milliseconds.")
        long waitedMs;

        @Schema(description = "A human-readable confirmation.")
        String message;
    }

    /**
     * Demo — the echoed response of {@code GET /mirror}, decoded by the demo extension's response-body
     * transformer (the {@code status} code becomes a label; {@code count} and {@code note} are untouched).
     */
    @Value
    @Builder
    public static class MirrorResponse {

        @Schema(description = "A status code the demo extension decodes to a label "
                + "(1=ACTIVE, 2=PENDING, 3=CLOSED; anything else → UNKNOWN).")
        Integer status;

        @Schema(description = "An untouched integer (not decoded) — shows sibling values are unaffected.")
        Integer count;

        @Schema(description = "An untouched string field.")
        String note;
    }

    /**
     * Demo — a generic error body returned for the non-2xx outcomes of {@code GET /outcomes}.
     */
    @Value
    @Builder
    public static class ApiError {

        @Schema(description = "A machine-readable error code.")
        String code;

        @Schema(description = "A human-readable error message.")
        String message;
    }

    /**
     * Demo — documents the single-file multipart body (file part + text part). Doc-only: the endpoint
     * binds the parts via @RequestParam, not this type.
     */
    @Value
    @Builder
    public static class SingleUploadForm {

        @Schema(type = "string", format = "binary", description = "A single file.")
        String file;

        @Schema(description = "An optional note sent alongside the file.")
        String note;
    }

    /**
     * Demo — documents the multi-file multipart body (file array + text part). Doc-only.
     */
    @Value
    @Builder
    public static class MultiUploadForm {

        @ArraySchema(arraySchema = @Schema(description = "One or more files."),
                schema = @Schema(type = "string", format = "binary"))
        List<String> files;

        @Schema(description = "An optional note sent alongside the files.")
        String note;
    }

    /**
     * Demo — what a file upload received: file descriptors, total bytes and an echoed text field.
     */
    @Value
    @Builder
    public static class UploadResult {

        @Schema(description = "Human-readable confirmation of what was received.")
        String message;

        @Schema(description = "The accompanying text field, if any.")
        @Nullable String note;

        @Schema(description = "Number of files received.")
        int fileCount;

        @Schema(description = "Total size of all received files, in bytes.")
        long totalBytes;

        @Schema(description = "One descriptor per received file.")
        List<FileInfo> files;
    }

    /**
     * Demo — a single received file's descriptor.
     */
    @Value
    @Builder
    public static class FileInfo {

        @Schema(description = "Original file name.")
        @Nullable String name;

        @Schema(description = "Reported content type.")
        @Nullable String contentType;

        @Schema(description = "File size in bytes.")
        long size;
    }

    /**
     * Demo — the echoed fields of a URL-encoded form submission.
     */
    @Value
    @Builder
    public static class FormResult {

        @Schema(description = "Human-readable confirmation of what was received.")
        String message;

        @Schema(description = "The received name field.")
        String name;

        @Schema(description = "The received count field, if any.")
        Integer count;
    }
}
