package org.plukh.spyglass.demo;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.swagger.v3.oas.annotations.ExternalDocumentation;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.DiscriminatorMapping;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Builder;
import lombok.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
import java.util.ArrayList;
import java.util.List;

/**
 * Opt-in demo/showcase for the explorer, registered only when {@code apidocs.demo.enabled=true}
 * (see {@link DemoEndpointsConfiguration}) — never bound by default.
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
            summary = "Send a payload (anyOf)",
            description = "Demo — exercises the anyOf variant selector (no discriminator) and the anyOf hint.")
    @PostMapping("/payloads")
    public PayloadRequest createPayload(@RequestBody PayloadRequest request) {
        return request;
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
                    @ExampleObject(name = "by-name", summary = "Match by name", value = "acme"),
                    @ExampleObject(name = "by-id", summary = "Match by id", value = "42")
            })
            @RequestParam(value = "filter", required = false) String filter,
            @Parameter(description = "Maximum results.", example = "20")
            @RequestParam(value = "limit", required = false) Integer limit,
            @RequestBody ExamplePayload payload) {
        return payload;
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
    @Builder
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
    @Value
    @Builder
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
    @Builder
    public static class Square implements Shape {

        @Schema(description = "Discriminator value — always \"square\".")
        String kind;

        @Schema(description = "Side length in pixels.")
        Integer side;
    }

    /**
     * Demo — a body with a non-discriminated anyOf field (either text or a number wrapper).
     */
    @Value
    @Builder
    public static class PayloadRequest {

        @Schema(description = "Either a text or a number payload.", anyOf = {TextPayload.class, NumberPayload.class})
        Object value;
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
        String note;

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
        String name;

        @Schema(description = "Reported content type.")
        String contentType;

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
