package com.finora.imports.send;

import com.finora.dto.ApiResponse;
import com.finora.imports.StatementUpload;
import com.finora.imports.analysis.UploadRefusalLog;
import com.finora.imports.jobs.ImportJobService;
import com.finora.imports.jobs.QueuedUploadChecks;
import com.finora.uploads.UploadScanGate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Gate 2b spec §7, the computer's side: {@code app.fynora.net/send} posts a code and one file here.
 *
 * <p><b>Public, on purpose</b> ({@code SecurityConfig} permits {@code POST /api/v1/send}): the code
 * replaces authentication, and it grants one thing only, adding one file to its owner's review queue.
 * Nothing the response carries comes from the account: not the name, not the email, not the job id.
 *
 * <p>The file passes the same checks as a signed-in queued upload ({@link QueuedUploadChecks}, then
 * the malware scan, which stays in this method because UploadScanGuardTest (FG-034) requires every
 * controller method that takes a file to call it). A password-protected PDF is refused with
 * IMPORT_008: its password is never typed on a public page, and the queue cannot wait for one later.
 * A refused file gives the code back, so a person who picked the wrong file can try again.
 *
 * <p>CSRF: none needed. The API keeps no session and reads no cookie, so a cross-site form can only
 * do what anyone holding the code already can.
 */
@RestController
@RequestMapping("/api/v1/send")
public class PublicSendController {

    private final StatementSendService service;
    private final QueuedUploadChecks queuedUploadChecks;
    private final UploadScanGate uploadScanGate;
    private final UploadRefusalLog uploadRefusalLog;

    public PublicSendController(StatementSendService service, QueuedUploadChecks queuedUploadChecks,
                                UploadScanGate uploadScanGate, UploadRefusalLog uploadRefusalLog) {
        this.service = service;
        this.queuedUploadChecks = queuedUploadChecks;
        this.uploadScanGate = uploadScanGate;
        this.uploadRefusalLog = uploadRefusalLog;
    }

    @PostMapping(consumes = "multipart/form-data")
    public ResponseEntity<ApiResponse<SendDto.Received>> send(@RequestParam("code") String code,
                                                              @RequestParam("file") MultipartFile file) throws Exception {
        StatementSendService.Claim claim = service.claim(code);
        StatementUpload.Format format = ImportJobService.formatOf(file.getOriginalFilename());
        try {
            queuedUploadChecks.requireReadable(file, format);
            uploadScanGate.requireClean(file, claim.userId(), "statement-send");
            queuedUploadChecks.passwordToSave(file, format, false, null);
        } catch (java.io.IOException | RuntimeException refused) {
            uploadRefusalLog.refused(claim.userId(), format.name(), refused);
            service.release(claim);
            throw refused;
        }
        service.complete(claim, file, format);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.ok(new SendDto.Received(true)));
    }
}
