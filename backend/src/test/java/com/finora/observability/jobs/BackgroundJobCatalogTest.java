package com.finora.observability.jobs;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.type.MethodMetadata;
import org.springframework.core.type.classreading.CachingMetadataReaderFactory;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.scheduling.annotation.Scheduled;

import java.io.IOException;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The background jobs page lists jobs by their catalogue entry, so a scheduled method with no entry
 * would run unnamed, and an entry for a deleted method would name nothing. Both directions fail here.
 *
 * <p>Reads the compiled production classes' metadata (no class loading), so test-only
 * {@code @Scheduled} beans under {@code src/test} are never counted.
 */
class BackgroundJobCatalogTest {

    @Test
    void everyScheduledMethodIsCatalogued_andNothingElse() throws IOException {
        Set<String> scheduled = scheduledMethodKeys();

        Set<String> missing = new TreeSet<>(scheduled);
        missing.removeAll(BackgroundJobCatalog.keys());
        Set<String> stale = new TreeSet<>(BackgroundJobCatalog.keys());
        stale.removeAll(scheduled);

        assertThat(scheduled).as("the scan found the scheduled methods at all").hasSizeGreaterThan(30);
        assertThat(missing).as("@Scheduled methods with no BackgroundJobCatalog entry").isEmpty();
        assertThat(stale).as("BackgroundJobCatalog entries naming no @Scheduled method").isEmpty();
    }

    @Test
    void everyEntryHasAPlainNameAndPurpose() {
        for (String key : BackgroundJobCatalog.keys()) {
            BackgroundJobCatalog.JobInfo info = BackgroundJobCatalog.lookup(key).orElseThrow();
            assertThat(info.name()).as(key + " name").isNotBlank();
            assertThat(info.purpose()).as(key + " purpose").isNotBlank().hasSizeLessThanOrEqualTo(120);
        }
    }

    @Test
    void anUnknownKeyHasNoEntry() {
        assertThat(BackgroundJobCatalog.lookup("NoSuchClass.noSuchMethod")).isEmpty();
    }

    static Set<String> scheduledMethodKeys() throws IOException {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        CachingMetadataReaderFactory readers = new CachingMetadataReaderFactory(resolver);
        Set<String> keys = new TreeSet<>();
        for (Resource resource : resolver.getResources("classpath*:com/finora/**/*.class")) {
            if (resource.getURL().toString().contains("/test-classes/")) continue;
            MetadataReader reader = readers.getMetadataReader(resource);
            String className = reader.getClassMetadata().getClassName();
            String simpleName = className.substring(className.lastIndexOf('.') + 1);
            for (MethodMetadata method : reader.getAnnotationMetadata().getAnnotatedMethods(Scheduled.class.getName())) {
                keys.add(simpleName + "." + method.getMethodName());
            }
        }
        return keys;
    }
}
