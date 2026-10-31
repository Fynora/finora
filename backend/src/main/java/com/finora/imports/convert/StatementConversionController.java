package com.finora.imports.convert;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finora.config.ClientIpResolver;
import com.finora.dto.ApiResponse;
import com.finora.exception.ApiException;
import com.finora.exception.ErrorCode;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;

/**
 * The free statement converter's two public endpoints ({@code SecurityConfig} permits both):
 * whether it is on, and one conversion. See {@link StatementConversionService}.
 *
 * <p><b>The body is read here, by hand, with a hard cap,</b> rather than bound with
 * {@code @RequestBody}. Two reasons. A JSON body has no size limit of its own (the multipart limits do
 * not apply to it), so an unbounded read would let one request fill the heap. And a parse error from
 * the JSON library can quote the input it choked on, which here is a statement; this method turns
 * any unreadable body into one fixed message.
 *
 * <p>No session, no cookie, no CSRF token needed: the API keeps no session and reads no cookie, the
 * same reasoning as {@code PublicSendController}.
 */
@RestController
@RequestMapping("/api/v1/convert")
public class StatementConversionController {

    /** 5 MB of PDF as base64 is about 7 MB; the rest is the JSON around it and a password. */
    static final int MAX_BODY_BYTES = 7 * 1024 * 1024 + 64 * 1024;

    private final StatementConversionService service;
    private final ClientIpResolver clientIpResolver;
    private final ObjectMapper objectMapper;

    public StatementConversionController(StatementConversionService service, ClientIpResolver clientIpResolver,
                                         ObjectMapper objectMapper) {
        this.service = service;
        this.clientIpResolver = clientIpResolver;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/availability")
    public ResponseEntity<ApiResponse<ConvertDto.Availability>> availability() {
        return ResponseEntity.ok(ApiResponse.ok(service.availability()));
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(
            mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ConvertDto.Request.class)))
    public ResponseEntity<ApiResponse<ConvertDto.Result>> convert(HttpServletRequest request) {
        ConvertDto.Request body = readBody(request);
        return ResponseEntity.ok(ApiResponse.ok(service.convert(body, clientIpResolver.resolve(request))));
    }

    private ConvertDto.Request readBody(HttpServletRequest request) {
        long declared = request.getContentLengthLong();
        if (declared > MAX_BODY_BYTES) throw new ApiException(ErrorCode.CONVERT_TOO_LARGE);
        byte[] bytes;
        try (InputStream in = request.getInputStream()) {
            bytes = in.readNBytes(MAX_BODY_BYTES + 1);
        } catch (IOException e) {
            throw new ApiException(ErrorCode.CONVERT_BAD_REQUEST);
        }
        if (bytes.length > MAX_BODY_BYTES) throw new ApiException(ErrorCode.CONVERT_TOO_LARGE);
        try {
            return objectMapper.readValue(bytes, ConvertDto.Request.class);
        } catch (IOException e) {
            // Never the parser's message: it can quote the body.
            throw new ApiException(ErrorCode.CONVERT_BAD_REQUEST);
        }
    }
}
