package io.tapstate.app;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The HTTP port the server binds is declared in configuration rather than left to the framework
 * default, and it stays overridable by the {@code SERVER_PORT} environment variable so a second
 * instance can run on a host where the usual port is already taken.
 *
 * <p>8080 is load-bearing, not arbitrary: the container image exposes it, its health check probes
 * it, the compose file maps it and the quickstart addresses it. A change to the declared default
 * has to move all of those with it, so the first case pins the value.
 *
 * <p>The second case states the override contract. It is worth knowing what it does and does not
 * discriminate: the framework maps {@code server.port} onto {@code SERVER_PORT} through its
 * system-environment source on its own, so this case passes whether the declaration carries the
 * placeholder or a bare number. It guards the contract itself - that nothing downstream pins the
 * port at a precedence the environment cannot outrank - not the shape of the declaration.
 */
class ServerPortConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer());

    @Test
    void bindsTheDeclaredDefaultPortWhenTheEnvironmentNamesNoOther() {
        runner.run(context -> assertThat(context.getEnvironment().getProperty("server.port"))
                .as("declared default port")
                .isEqualTo("8080"));
    }

    @Test
    void bindsThePortNamedByTheServerPortEnvironmentVariable() {
        // Arrives the way a real environment variable does, through a system-environment source,
        // so the relaxed SERVER_PORT to server.port mapping is exercised rather than simulated.
        runner.withInitializer(systemEnvironmentWith("SERVER_PORT", "8081"))
                .run(context -> assertThat(context.getEnvironment().getProperty("server.port"))
                        .as("port named by the environment")
                        .isEqualTo("8081"));
    }

    private static ApplicationContextInitializer<ConfigurableApplicationContext> systemEnvironmentWith(
            String name, String value) {
        return context -> context.getEnvironment()
                .getPropertySources()
                .addFirst(new SystemEnvironmentPropertySource(
                        "testSystemEnvironment", Map.<String, Object>of(name, value)));
    }
}
