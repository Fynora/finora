package com.finora.architecture;

import com.finora.architecture.registry.GuardianRule;
import com.finora.uploads.UploadScanGate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The structural half of the upload malware scan (audit F-18). {@link UploadScanGate} is only a
 * control if every upload endpoint calls it; a utility three controllers remembered to use and a
 * fourth did not is the exact shape {@code docs/security/security-control-audit.md} warns about
 * for log masking. This rule makes forgetting a build failure.
 *
 * <p>Falsified by hand on 2026-09-25: with the {@code requireClean} call removed from
 * {@code ImportJobController.submit}, this test named exactly that method.
 */
class UploadScanGuardTest {

    @GuardianRule(
            id = "FG-034",
            category = GuardianRule.Category.SECURITY,
            intent = "Every @RestController method that accepts a MultipartFile calls UploadScanGate.requireClean.",
            source = "Audit F-18 (2026-09-24): uploads reached PDFBox, OpenCSV and Tesseract unscanned",
            introduced = "2026-09-25",
            owner = "architecture",
            verification = GuardianRule.Verification.MANUAL_FALSIFICATION)
    @Test
    void everyUploadEndpointRunsTheFileThroughTheScanGate() {
        JavaClasses classes = ProductionClasses.INSTANCE;

        List<String> offenders = new ArrayList<>();
        int endpointsChecked = 0;

        for (JavaClass javaClass : classes) {
            if (!javaClass.isAnnotatedWith(RestController.class)) continue;

            for (JavaMethod method : javaClass.getMethods()) {
                boolean takesAnUpload = method.getRawParameterTypes().stream()
                        .anyMatch(type -> type.isAssignableTo(MultipartFile.class));
                if (!takesAnUpload) continue;
                endpointsChecked++;

                boolean scans = method.getMethodCallsFromSelf().stream().anyMatch(call ->
                        call.getTarget().getOwner().isEquivalentTo(UploadScanGate.class)
                                && call.getTarget().getName().equals("requireClean"));
                if (!scans) {
                    offenders.add(method.getFullName());
                }
            }
        }

        assertThat(endpointsChecked)
                .as("the rule must actually be looking at something; zero upload endpoints means "
                        + "the detection broke, not that the codebase stopped taking uploads")
                .isGreaterThanOrEqualTo(5);
        assertThat(offenders)
                .as("""
                        These controller methods accept a MultipartFile without calling \
                        UploadScanGate.requireClean(file, userId, context). Every upload is scanned \
                        before a parser sees it (audit F-18); call the gate after the cheap \
                        structural checks and before any expensive work -- see ImportController.stage \
                        for the placement.""")
                .isEmpty();
    }
}
