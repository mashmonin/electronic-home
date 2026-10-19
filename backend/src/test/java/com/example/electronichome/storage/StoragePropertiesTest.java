package com.example.electronichome.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class StoragePropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(Properties.class);

    @EnableConfigurationProperties(StorageProperties.class)
    static class Properties {
    }

    private static StorageProperties with(String endpoint, String access, String secret, String receipts, String attachments) {
        return new StorageProperties(endpoint, "us-east-1", access, secret, receipts, attachments);
    }

    @Test
    void validSettingsAreAccepted() {
        StorageProperties properties = with("http://s3:8333", "access-key", "secret-key-0123456", "receipts", "attachments");
        assertThat(properties.endpoint()).isEqualTo("http://s3:8333");
    }

    @Test
    void theEndpointIsRequired() {
        assertThatThrownBy(() -> with("", "access-key", "secret-key-0123456", "a", "b")).hasMessageContaining("S3_ENDPOINT");
        assertThatThrownBy(() -> with(null, "access-key", "secret-key-0123456", "a", "b")).hasMessageContaining("S3_ENDPOINT");
    }

    @Test
    void theKeysAreRequired() {
        assertThatThrownBy(() -> with("http://s3", null, "secret-key-0123456", "a", "b")).hasMessageContaining("S3_ACCESS_KEY");
        assertThatThrownBy(() -> with("http://s3", "", "secret-key-0123456", "a", "b")).hasMessageContaining("S3_ACCESS_KEY");
        assertThatThrownBy(() -> with("http://s3", "access-key", null, "a", "b")).hasMessageContaining("S3_SECRET_KEY");
    }

    @Test
    void theSecretMustBeAtLeast16Characters() {
        assertThatThrownBy(() -> with("http://s3", "access-key", "short-secret", "a", "b")).hasMessageContaining("at least 16");
        with("http://s3", "access-key", "exactly-16-chars!".substring(0, 16).replace('!', 'x'), "a", "b");
    }

    @Test
    void theKeysMustNotNeedEscapingInJson() {
        assertThatThrownBy(() -> with("http://s3", "acc\"ess", "secret-key-0123456", "a", "b")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> with("http://s3", "access-key", "secret\\key-0123456", "a", "b")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> with("http://s3", "access-key", "secret key 0123456", "a", "b")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theTwoBucketsMustDiffer() {
        assertThatThrownBy(() -> with("http://s3", "access-key", "secret-key-0123456", "same", "same")).hasMessageContaining("differ");
    }

    @Test
    void prodNeedsTheEndpointAndTheKeys() {
        runner.withPropertyValues("spring.profiles.active=prod")
                .run(context -> assertThat(context.getStartupFailure()).hasStackTraceContaining("S3_"));
    }

    @Test
    void prodRejectsAShortSecret() {
        runner.withPropertyValues("spring.profiles.active=prod", "storage.s3.endpoint=http://s3:8333", "storage.s3.access-key=app", "storage.s3.secret-key=short")
                .run(context -> assertThat(context.getStartupFailure()).hasStackTraceContaining("at least 16"));
    }

    @Test
    void prodAcceptsGoodSettings() {
        runner.withPropertyValues(
                        "spring.profiles.active=prod", "storage.s3.endpoint=http://s3:8333", "storage.s3.access-key=app", "storage.s3.secret-key=" + "x".repeat(20))
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void devHasKeysForDevelopmentAndTheLocalPort() {
        runner.withPropertyValues("spring.profiles.active=dev").run(context -> {
            StorageProperties properties = context.getBean(StorageProperties.class);
            assertThat(properties.endpoint()).isEqualTo("http://localhost:8333");
            assertThat(properties.accessKey()).isNotBlank();
            assertThat(properties.receiptsBucket()).isEqualTo("electronic-home-receipts");
            assertThat(properties.attachmentsBucket()).isEqualTo("electronic-home-attachments");
        });
    }
}
