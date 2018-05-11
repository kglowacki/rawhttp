package com.athaydes.rawhttp.core;

import com.athaydes.rawhttp.core.BodyReader.BodyType;
import com.athaydes.rawhttp.core.errors.InvalidHttpRequest;
import com.athaydes.rawhttp.core.errors.InvalidHttpResponse;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.BiFunction;
import java.util.regex.Pattern;
import javax.annotation.Nullable;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * The main class of the raw-http library.
 * <p>
 * Instances of this class can parse HTTP requests and responses, as well as their subsets such as
 * the start-line and headers.
 * <p>
 * To use a default instance, which is not 100% raw (it fixes up new-lines and request's Host headers, for example)
 * use the default constructor, otherwise, call {@link #RawHttp(RawHttpOptions)} with the appropriate options.
 *
 * @see RawHttpOptions
 * @see RawHttpRequest
 * @see RawHttpResponse
 * @see RawHttpHeaders
 */
public class RawHttp {

    private static final Pattern statusCodePattern = Pattern.compile("\\d{3}");

    private final RawHttpOptions options;

    /**
     * Create a new instance of {@link RawHttp} using the default {@link RawHttpOptions} instance.
     */
    public RawHttp() {
        this(RawHttpOptions.defaultInstance());
    }

    /**
     * Create a configured instance of {@link RawHttp}.
     *
     * @param options configuration options
     */
    public RawHttp(RawHttpOptions options) {
        this.options = options;
    }

    /**
     * Parses the given HTTP request.
     *
     * @param request in text form
     * @return a parsed HTTP request object
     * @throws InvalidHttpRequest if the request is invalid
     */
    public final RawHttpRequest parseRequest(String request) {
        try {
            return parseRequest(new ByteArrayInputStream(request.getBytes(UTF_8)));
        } catch (IOException e) {
            // IOException should be impossible
            throw new RuntimeException(e);
        }
    }

    /**
     * Parses the HTTP request contained in the given file.
     *
     * @param file containing a HTTP request
     * @return a parsed HTTP request object
     * @throws InvalidHttpRequest if the request is invalid
     * @throws IOException        if a problem occurs reading the file
     */
    public final RawHttpRequest parseRequest(File file) throws IOException {
        try (FileInputStream stream = new FileInputStream(file)) {
            return parseRequest(stream).eagerly();
        }
    }

    /**
     * Parses the HTTP request produced by the given stream.
     *
     * @param inputStream producing a HTTP request
     * @return a parsed HTTP request object
     * @throws InvalidHttpRequest if the request is invalid
     * @throws IOException        if a problem occurs accessing the stream
     */
    public RawHttpRequest parseRequest(InputStream inputStream) throws IOException {
        return parseRequest(inputStream, null);
    }

    /**
     * Parses the HTTP request produced by the given stream.
     *
     * @param inputStream   producing a HTTP request
     * @param senderAddress the address of the request sender, if known
     * @return a parsed HTTP request object
     * @throws InvalidHttpRequest if the request is invalid
     * @throws IOException        if a problem occurs accessing the stream
     */
    public RawHttpRequest parseRequest(InputStream inputStream,
                                       @Nullable InetAddress senderAddress) throws IOException {
        List<String> metadataLines = parseMetadataLines(inputStream,
                InvalidHttpRequest::new,
                options.allowNewLineWithoutReturn(),
                options.ignoreLeadingEmptyLine());

        if (metadataLines.isEmpty()) {
            throw new InvalidHttpRequest("No content", 0);
        }

        RequestLine requestLine = parseRequestLine(
                metadataLines.remove(0),
                options.insertHttpVersionIfMissing());

        RawHttpHeaders.Builder headersBuilder = parseHeaders(metadataLines, InvalidHttpRequest::new);

        // do a little cleanup to make sure the request is actually valid
        requestLine = verifyHost(requestLine, headersBuilder);

        RawHttpHeaders headers = headersBuilder.build();

        boolean hasBody = requestHasBody(headers);
        @Nullable BodyReader bodyReader = createBodyReader(inputStream, headers, hasBody);

        return new RawHttpRequest(requestLine, headers, bodyReader, senderAddress);
    }

    /**
     * Parses the given HTTP response.
     *
     * @param response in text form
     * @return a parsed HTTP response object
     * @throws InvalidHttpResponse if the response is invalid
     */
    public final RawHttpResponse<Void> parseResponse(String response) {
        try {
            return parseResponse(
                    new ByteArrayInputStream(response.getBytes(UTF_8)),
                    null);
        } catch (IOException e) {
            // IOException should be impossible
            throw new RuntimeException(e);
        }
    }

    /**
     * Parses the HTTP response contained in the given file.
     *
     * @param file containing a HTTP response
     * @return a parsed HTTP response object
     * @throws InvalidHttpResponse if the response is invalid
     * @throws IOException         if a problem occurs reading the file
     */
    public final RawHttpResponse<Void> parseResponse(File file) throws IOException {
        try (FileInputStream stream = new FileInputStream(file)) {
            return parseResponse(stream, null).eagerly();
        }
    }

    /**
     * Parses the HTTP response produced by the given stream.
     *
     * @param inputStream producing a HTTP response
     * @return a parsed HTTP response object
     * @throws InvalidHttpResponse if the response is invalid
     * @throws IOException         if a problem occurs accessing the stream
     */
    public final RawHttpResponse<Void> parseResponse(InputStream inputStream) throws IOException {
        return parseResponse(inputStream, null);
    }

    /**
     * Parses the HTTP response produced by the given stream.
     *
     * @param inputStream producing a HTTP response
     * @param requestLine optional {@link RequestLine} of the request which results in this response.
     *                    If provided, it is taken into consideration when deciding whether the response contains
     *                    a body. See <a href="https://tools.ietf.org/html/rfc7230#section-3.3">Section 3.3</a>
     *                    of RFC-7230 for details.
     * @return a parsed HTTP response object
     * @throws InvalidHttpResponse if the response is invalid
     * @throws IOException         if a problem occurs accessing the stream
     */
    public RawHttpResponse<Void> parseResponse(InputStream inputStream,
                                               @Nullable RequestLine requestLine) throws IOException {
        List<String> metadataLines = parseMetadataLines(inputStream,
                InvalidHttpResponse::new,
                options.allowNewLineWithoutReturn(),
                options.ignoreLeadingEmptyLine());

        if (metadataLines.isEmpty()) {
            throw new InvalidHttpResponse("No content", 0);
        }

        StatusLine statusLine = parseStatusLine(
                metadataLines.remove(0),
                options.insertHttpVersionIfMissing());

        RawHttpHeaders headers = parseHeaders(metadataLines, InvalidHttpResponse::new).build();

        boolean hasBody = responseHasBody(statusLine, requestLine);
        @Nullable BodyReader bodyReader = createBodyReader(inputStream, headers, hasBody);

        return new RawHttpResponse<>(null, null, statusLine, headers, bodyReader);
    }

    @Nullable
    private BodyReader createBodyReader(InputStream inputStream, RawHttpHeaders headers, boolean hasBody) {
        @Nullable BodyReader bodyReader;

        if (hasBody) {
            @Nullable Long bodyLength = null;
            OptionalLong headerLength = parseContentLength(headers);
            if (headerLength.isPresent()) {
                bodyLength = headerLength.getAsLong();
            }
            BodyType bodyType = getBodyType(headers, bodyLength);
            bodyReader = new LazyBodyReader(bodyType, inputStream, bodyLength, options.allowNewLineWithoutReturn());
        } else {
            bodyReader = null;
        }
        return bodyReader;
    }

    static List<String> parseMetadataLines(InputStream inputStream,
                                           BiFunction<String, Integer, RuntimeException> createError,
                                           boolean allowNewLineWithoutReturn,
                                           boolean ignoreLeadingNewLine) throws IOException {
        List<String> metadataLines = new ArrayList<>();
        StringBuilder metadataBuilder = new StringBuilder();
        boolean wasNewLine = true;
        boolean skipNewLine = ignoreLeadingNewLine; // start by skipping new line if we can ignore it
        int lineNumber = 1;
        int b;
        while ((b = inputStream.read()) >= 0) {
            if (b == '\r') {
                // expect new-line
                int next = inputStream.read();
                if (next < 0 || next == '\n') {
                    if (skipNewLine) continue;
                    lineNumber++;
                    if (wasNewLine) break;
                    metadataLines.add(metadataBuilder.toString());
                    if (next < 0) break;
                    metadataBuilder = new StringBuilder();
                    wasNewLine = true;
                } else {
                    inputStream.close();
                    throw createError.apply("Illegal character after return", lineNumber);
                }
            } else if (b == '\n') {
                if (skipNewLine) continue;
                if (!allowNewLineWithoutReturn) {
                    throw createError.apply("Illegal new-line character without preceding return", lineNumber);
                }

                // unexpected, but let's accept new-line without returns
                lineNumber++;
                if (wasNewLine) break;
                metadataLines.add(metadataBuilder.toString());
                metadataBuilder = new StringBuilder();
                wasNewLine = true;
            } else {
                metadataBuilder.append((char) b);
                wasNewLine = false;
            }
            skipNewLine = false;
        }

        if (metadataBuilder.length() > 0) {
            metadataLines.add(metadataBuilder.toString());
        }

        return metadataLines;
    }

    /**
     * Get the body type of a HTTP message with the given headers.
     * <p>
     * If the value of the Content-Length header is known, it should be passed as the {@code bodyLength}
     * argument, as it is not extracted otherwise.
     *
     * @param headers    HTTP message's headers
     * @param bodyLength body length if known
     * @return the body type of the HTTP message
     */
    public static BodyType getBodyType(RawHttpHeaders headers,
                                       @Nullable Long bodyLength) {
        return bodyLength == null ?
                parseContentEncoding(headers).orElse(BodyType.CLOSE_TERMINATED) :
                BodyType.CONTENT_LENGTH;
    }

    /**
     * Determines whether a request with the given headers should have a body.
     *
     * @param headers HTTP request's headers
     * @return true if the headers indicate the request should have a body, false otherwise
     */
    public static boolean requestHasBody(RawHttpHeaders headers) {
        // The presence of a message body in a request is signaled by a
        // Content-Length or Transfer-Encoding header field.  Request message
        // framing is independent of method semantics, even if the method does
        // not define any use for a message body.
        return headers.contains("Content-Length") || headers.contains("Transfer-Encoding");
    }

    /**
     * Determines whether a response with the given status-line should have a body.
     * <p>
     * This method ignores the request-line of the request which produced such response. If the request
     * is known, use the {@link #responseHasBody(StatusLine, RequestLine)} method instead.
     *
     * @param statusLine status-line of response
     * @return true if such response has a body, false otherwise
     */
    public static boolean responseHasBody(StatusLine statusLine) {
        return responseHasBody(statusLine, null);
    }

    /**
     * Determines whether a response with the given status-line should have a body.
     * <p>
     * If provided, the request-line of the request which produced such response is taken into
     * consideration. See <a href="https://tools.ietf.org/html/rfc7230#section-3.3">Section 3.3</a>
     * of RFC-7230 for details.
     *
     * @param statusLine  status-line of response
     * @param requestLine request-line of request, if any
     * @return true if such response has a body, false otherwise
     */
    public static boolean responseHasBody(StatusLine statusLine,
                                          @Nullable RequestLine requestLine) {
        if (requestLine != null) {
            if (requestLine.getMethod().equalsIgnoreCase("HEAD")) {
                return false; // HEAD response must never have a body
            }
            if (requestLine.getMethod().equalsIgnoreCase("CONNECT") &&
                    startsWith(2, statusLine.getStatusCode())) {
                return false; // CONNECT successful means start tunelling
            }
        }

        int statusCode = statusLine.getStatusCode();

        // All 1xx (Informational), 204 (No Content), and 304 (Not Modified)
        // responses do not include a message body.
        boolean hasNoBody = startsWith(1, statusCode) || statusCode == 204 || statusCode == 304;

        return !hasNoBody;
    }

    private static boolean startsWith(int firstDigit, int statusCode) {
        assert 0 < firstDigit && firstDigit < 10;
        int minCode = firstDigit * 100;
        int maxCode = minCode + 99;
        return minCode <= statusCode && statusCode <= maxCode;
    }

    private static Optional<BodyType> parseContentEncoding(RawHttpHeaders headers) {
        Optional<String> encoding = last(headers.get("Transfer-Encoding"));
        if (encoding.isPresent()) {
            if (encoding.get().equalsIgnoreCase("chunked")) {
                return Optional.of(BodyType.CHUNKED);
            } else {
                throw new IllegalArgumentException("Transfer-Encoding is not supported: " + encoding);
            }
        } else {
            return Optional.empty();
        }
    }

    private static Optional<String> last(Collection<String> items) {
        String result = null;
        for (String item : items) {
            result = item;
        }
        return Optional.ofNullable(result);
    }

    /**
     * Parses a HTTP response's status-line.
     *
     * @param line                       status-line
     * @param insertHttpVersionIfMissing whether to insert a HTTP version if it's missing
     * @return the status-line
     * @throws InvalidHttpResponse if the status code line is invalid
     */
    public static StatusLine parseStatusLine(String line,
                                             boolean insertHttpVersionIfMissing) {
        if (line.trim().isEmpty()) {
            throw new InvalidHttpResponse("Empty status line", 1);
        }
        String[] parts = line.split("\\s", 3);

        String httpVersion = null;
        String statusCode;
        String reason = "";

        if (parts.length == 1) {
            statusCode = parts[0];
        } else {
            if (parts[0].startsWith("HTTP")) {
                httpVersion = parts[0];
                statusCode = parts[1];
                if (parts.length == 3) {
                    reason = parts[2];
                }
            } else {
                statusCode = parts[0];

                // parts 1 and 2, if it's there, must be "reason"
                reason = parts[1];
                if (parts.length == 3) {
                    reason += " " + parts[2];
                }
            }
        }

        HttpVersion version;
        if (httpVersion == null) {
            if (insertHttpVersionIfMissing) {
                version = HttpVersion.HTTP_1_1;
            } else {
                throw new InvalidHttpResponse("Missing HTTP version", 1);
            }
        } else {
            try {
                version = HttpVersion.parse(httpVersion);
            } catch (IllegalArgumentException e) {
                throw new InvalidHttpResponse("Invalid HTTP version", 1);
            }
        }

        if (!statusCodePattern.matcher(statusCode).matches()) {
            throw new InvalidHttpResponse("Invalid status code", 1);
        }

        try {
            return new StatusLine(version, Integer.parseInt(statusCode), reason);
        } catch (NumberFormatException e) {
            throw new InvalidHttpResponse("Invalid status code", 1);
        }

    }

    private RequestLine verifyHost(RequestLine requestLine, RawHttpHeaders.Builder headers) {
        List<String> host = headers.build().get("Host");
        if (host.isEmpty()) {
            if (!options.insertHostHeaderIfMissing()) {
                throw new InvalidHttpRequest("Host header is missing", 1);
            } else if (requestLine.getUri().getHost() == null) {
                throw new InvalidHttpRequest("Host not given either in method line or Host header", 1);
            } else {
                // add the Host header to make sure the request is legal
                headers.with("Host", requestLine.getUri().getHost());
            }
            return requestLine;
        } else if (host.size() == 1) {
            if (requestLine.getUri().getHost() != null) {
                throw new InvalidHttpRequest("Host specified both in Host header and in method line", 1);
            }
            try {
                RequestLine newRequestLine = requestLine.withHost(host.iterator().next());
                // cleanup the host header
                headers.overwrite("Host", newRequestLine.getUri().getHost());
                return newRequestLine;
            } catch (IllegalArgumentException e) {
                int lineNumber = headers.getLineNumbers("Host").get(0);
                throw new InvalidHttpRequest("Invalid host header: " + e.getMessage(), lineNumber);
            }
        } else {
            int lineNumber = headers.getLineNumbers("Host").get(1);
            throw new InvalidHttpRequest("More than one Host header specified", lineNumber);
        }
    }

    /**
     * Parses a HTTP request's request-line.
     *
     * @param requestLine               request line
     * @param inserHttpVersionIfMissing whether to insert a HTTP version if it's missing
     * @return the request line
     * @throws InvalidHttpRequest if the request line is invalid
     */
    public static RequestLine parseRequestLine(String requestLine,
                                               boolean inserHttpVersionIfMissing) {
        if (requestLine.isEmpty()) {
            throw new InvalidHttpRequest("Empty request line", 1);
        } else {
            String[] parts = requestLine.split("\\s");
            if (parts.length == 2 || parts.length == 3) {
                String method = parts[0];
                if (FieldValues.indexOfNotAllowedInTokens(method).isPresent()) {
                    throw new InvalidHttpRequest("Invalid method name", 1);
                }
                URI uri = createUri(parts[1]);
                HttpVersion httpVersion = inserHttpVersionIfMissing
                        ? HttpVersion.HTTP_1_1 : null;
                if (parts.length == 3) try {
                    httpVersion = HttpVersion.parse(parts[2]);
                } catch (IllegalArgumentException e) {
                    throw new InvalidHttpRequest("Invalid HTTP version", 1);
                }
                if (httpVersion == null) {
                    throw new InvalidHttpRequest("Missing HTTP version", 1);
                }
                return new RequestLine(method, uri, httpVersion);
            } else {
                throw new InvalidHttpRequest("Invalid request line", 1);
            }
        }
    }

    /**
     * Extracts the Content-Length header's value from the given headers, if available.
     * <p>
     * If more than one value is available, returns the first one.
     *
     * @param headers HTTP message's headers
     * @return the value of the Content-Length header, if any, or empty otherwise.
     */
    public static OptionalLong parseContentLength(RawHttpHeaders headers) {
        Optional<String> contentLength = headers.getFirst("Content-Length");
        return contentLength.map(s -> OptionalLong.of(Long.parseLong(s))).orElseGet(OptionalLong::empty);
    }

    private static URI createUri(String part) {
        if (!part.startsWith("http")) {
            part = "http://" + part;
        }
        URI uri;
        try {
            uri = new URI(part);
        } catch (URISyntaxException e) {
            throw new InvalidHttpRequest("Invalid URI: " + e.getMessage(), 1);
        }
        return uri;
    }

    /**
     * Parses the HTTP messages' headers from the given lines.
     *
     * @param lines       header lines
     * @param createError error factory - used in case an error is encountered
     * @return modifiable {@link RawHttpHeaders.Builder}
     */
    public static RawHttpHeaders.Builder parseHeaders(
            List<String> lines,
            BiFunction<String, Integer, RuntimeException> createError) {
        RawHttpHeaders.Builder builder = RawHttpHeaders.Builder.newBuilder();
        int lineNumber = 2;
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) {
                break;
            }
            String[] parts = line.split(":\\s?", 2);
            if (parts.length != 2) {
                throw createError.apply("Invalid header", lineNumber);
            }
            builder.with(parts[0], parts[1], lineNumber);
            lineNumber++;
        }

        return builder;
    }

}
