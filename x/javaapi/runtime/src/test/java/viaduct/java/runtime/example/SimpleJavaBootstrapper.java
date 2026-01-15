package viaduct.java.runtime.example;

import java.util.Collections;
import kotlin.Pair;
import viaduct.engine.api.FieldResolverExecutor;
import viaduct.engine.api.NodeResolverExecutor;
import viaduct.engine.api.TenantModuleBootstrapper;
import viaduct.engine.api.ViaductSchema;
import viaduct.java.runtime.bridge.JavaFieldResolverExecutor;

/**
 * Simple manual bootstrapper for testing the Java API with a greeting resolver.
 *
 * <p>This manually registers the GreetingResolver for the Query.greeting field. In a real
 * implementation, this would use reflection to discover resolvers.
 */
public class SimpleJavaBootstrapper implements TenantModuleBootstrapper {

  // Coordinate is a Kotlin typealias for Pair<String, String>, which isn't visible from Java
  // So we use Pair<String, String> directly
  @Override
  public Iterable<Pair<Pair<String, String>, FieldResolverExecutor>> fieldResolverExecutors(
      ViaductSchema schema) {
    // Create the greeting resolver
    GreetingResolver resolver = new GreetingResolver();

    // Wrap it in the bridge executor
    JavaFieldResolverExecutor executor =
        new JavaFieldResolverExecutor(resolver::resolve, "Query.greeting", "GreetingResolver");

    // Return the mapping: Query.greeting -> executor
    Pair<String, String> coordinate = new Pair<>("Query", "greeting");
    Pair<Pair<String, String>, FieldResolverExecutor> entry = new Pair<>(coordinate, executor);

    return Collections.singletonList(entry);
  }

  @Override
  public Iterable<Pair<String, NodeResolverExecutor>> nodeResolverExecutors(ViaductSchema schema) {
    // No node resolvers for this simple example
    return Collections.emptyList();
  }
}
