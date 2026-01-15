package viaduct.java.runtime.example;

import java.util.concurrent.CompletableFuture;
import viaduct.java.api.annotations.Resolver;
import viaduct.java.api.context.FieldExecutionContext;

/**
 * A simple "Hello World" resolver that returns a greeting message.
 *
 * <p>This resolver demonstrates the simplest possible Java resolver:
 *
 * <ul>
 *   <li>Resolves Query.greeting field
 *   <li>Returns a String (scalar type)
 *   <li>No required selection sets
 *   <li>No arguments
 * </ul>
 */
@Resolver
public class GreetingResolver {

  /**
   * Resolves the greeting field.
   *
   * @param ctx the execution context (not used in this simple example)
   * @return a CompletableFuture containing the greeting message
   */
  public CompletableFuture<String> resolve(FieldExecutionContext<?, ?, ?, ?> ctx) {
    return CompletableFuture.completedFuture("Hello, World!");
  }
}
