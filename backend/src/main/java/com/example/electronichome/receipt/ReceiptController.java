package com.example.electronichome.receipt;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.InputStreamResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api/accounts/{number}/receipts")
class ReceiptController {

    private static final Logger log = LoggerFactory.getLogger(ReceiptController.class);

    private final ReceiptService service;

    ReceiptController(ReceiptService service) {
        this.service = service;
    }

    // The PDF is the whole body of the request (no multipart); the service reads it as a stream with a size limit.
    @PutMapping("/{month}")
    ResponseEntity<ReceiptResponses.Published> publish(
            @PathVariable String number,
            @PathVariable String month,
            @RequestHeader(value = "X-Admin-Token", required = false) String token,
            @RequestParam(value = "capital_repair", required = false) String capitalRepair,
            @RequestParam(value = "utilities", required = false) String utilities,
            HttpServletRequest request) throws IOException {
        ReceiptService.Result result =
                service.publish(number, month, token, capitalRepair, utilities, request.getInputStream());
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.body());
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER') and @accountAccess.canAccess(authentication, #number)")
    ReceiptResponses.Items list(@PathVariable String number) {
        return service.list(number);
    }

    @GetMapping("/{month}")
    @PreAuthorize("hasAnyRole('ADMIN', 'OWNER') and @accountAccess.canAccess(authentication, #number)")
    ResponseEntity<InputStreamResource> download(@PathVariable String number, @PathVariable String month) throws IOException {
        ReceiptService.Download file = service.download(number, month);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .contentLength(file.size())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("receipt-" + number + "-" + month + ".pdf").build().toString())
                .body(new InputStreamResource(file.content()));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<Void> conflict(DataIntegrityViolationException e) {
        log.warn("Publishing a receipt conflicted with database constraints", e);
        return ResponseEntity.status(HttpStatus.CONFLICT).build();
    }
}
