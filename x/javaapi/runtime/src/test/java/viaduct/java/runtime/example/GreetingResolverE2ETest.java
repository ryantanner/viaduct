package viaduct.java.runtime.example;

import org.junit.jupiter.api.Test;
import viaduct.java.runtime.test.JavaFeatureTestHelper;

/**
 * End-to-end test demonstrating a Java resolver being called through the Viaduct engine.
 *
 * <p>This test demonstrates the complete flow from a Java developer's perspective:
 *
 * <ul>
 *   <li>Define a GraphQL schema
 *   <li>Create a Java resolver that returns CompletableFuture
 *   <li>Bootstrap the resolver using TenantModuleBootstrapper
 *   <li>Execute a GraphQL query through the engine
 *   <li>Verify the response
 * </ul>
 */
public class GreetingResolverE2ETest {

  private static final String SCHEMA_SDL = "extend type Query { greeting: String }";

  @Test
  public void greetingResolverReturnsHelloWorldThroughEngine() {
    // Create the bootstrapper that registers our Java resolver
    SimpleJavaBootstrapper bootstrapper = new SimpleJavaBootstrapper();

    // Run the feature test
    JavaFeatureTestHelper.run(
        SCHEMA_SDL,
        bootstrapper,
        test -> {
          // Execute a GraphQL query and verify the response
          test.runQueryAndAssert("{ greeting }", "{data: {greeting: \"Hello, World!\"}}");
        });
  }
}
