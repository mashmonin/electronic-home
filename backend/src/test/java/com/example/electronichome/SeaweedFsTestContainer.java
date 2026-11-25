package com.example.electronichome;

import java.time.Duration;
import java.util.UUID;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

/**
 * One SeaweedFS (S3 API) for all the integration tests of a run: it starts the first time a test needs it and stays
 * until the end (the container is removed by Testcontainers). Every test class gets buckets of its own, so the tests do
 * not see the files of each other.
 */
public final class SeaweedFsTestContainer {

    public static final String ACCESS_KEY = "test-access-key";
    public static final String SECRET_KEY = "test-secret-key-123456";

    // the script is one argument of `sh -c`: withCommand(String) would cut it at the spaces
    private static final String SCRIPT = """
            printf '{"identities":[{"name":"app","credentials":[{"accessKey":"%s","secretKey":"%s"}],"actions":["Admin","Read","Write","List","Tagging"]}]}' '__ACCESS__' '__SECRET__' > /tmp/s3.json
            exec /entrypoint.sh server -dir=/data -s3 -s3.config=/tmp/s3.json -master.volumeSizeLimitMB=64
            """.replace("__ACCESS__", ACCESS_KEY).replace("__SECRET__", SECRET_KEY);

    /** A new container that is not started; the tests that stop the storage use their own, the others the shared one. */
    public static GenericContainer<?> create() {
        return new GenericContainer<>("chrislusf/seaweedfs:4.48")
                .withExposedPorts(8333)
                .withCreateContainerCmdModifier(command -> command.withEntrypoint("sh", "-c").withCmd(SCRIPT))
                // an unsigned request is refused with 403 as soon as the S3 API listens
                .waitingFor(Wait.forHttp("/").forStatusCode(403).withStartupTimeout(Duration.ofSeconds(90)));
    }

    private static final GenericContainer<?> CONTAINER = create();

    static {
        CONTAINER.start();
    }

    private SeaweedFsTestContainer() {
    }

    public static String endpoint() {
        return "http://" + CONTAINER.getHost() + ":" + CONTAINER.getMappedPort(8333);
    }

    /** The settings of the application for this container, with buckets that no other test class uses. */
    public static String[] register(DynamicPropertyRegistry registry) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String receipts = "it-receipts-" + suffix;
        String attachments = "it-attachments-" + suffix;
        String acts = "it-meter-acts-" + suffix;
        String statements = "it-statements-" + suffix;
        registry.add("storage.s3.endpoint", SeaweedFsTestContainer::endpoint);
        registry.add("storage.s3.access-key", () -> ACCESS_KEY);
        registry.add("storage.s3.secret-key", () -> SECRET_KEY);
        registry.add("storage.s3.receipts-bucket", () -> receipts);
        registry.add("storage.s3.attachments-bucket", () -> attachments);
        registry.add("storage.s3.meter-acts-bucket", () -> acts);
        registry.add("storage.s3.statements-bucket", () -> statements);
        return new String[] {receipts, attachments, acts, statements};
    }
}
