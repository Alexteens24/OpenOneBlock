package dev.openoneblock.protection;

import dev.openoneblock.api.id.NamespacedId;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Event-independent protection query with optional source and destination locations.
 *
 * @param actor initiating actor
 * @param action canonical attempted action
 * @param source optional source block
 * @param destination optional destination block
 * @param target optional namespaced target identity
 * @param cause namespaced platform cause
 * @param context immutable scalar context snapshot
 */
public record ProtectionQuery(
    ProtectionActor actor,
    ProtectionAction action,
    Optional<ProtectionPosition> source,
    Optional<ProtectionPosition> destination,
    Optional<NamespacedId> target,
    NamespacedId cause,
    Map<String, String> context) {
  /** Validates and defensively copies query input. */
  public ProtectionQuery {
    Objects.requireNonNull(actor, "actor");
    Objects.requireNonNull(action, "action");
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(destination, "destination");
    Objects.requireNonNull(target, "target");
    Objects.requireNonNull(cause, "cause");
    context = Map.copyOf(context);
    if (source.isEmpty() && destination.isEmpty()) {
      throw new IllegalArgumentException("a protection query requires a source or destination");
    }
  }

  /**
   * Creates a query without a separately named target identity.
   *
   * @param actor initiating actor
   * @param action canonical attempted action
   * @param source optional source block
   * @param destination optional destination block
   * @param cause namespaced platform cause
   * @param context immutable scalar context snapshot
   */
  public ProtectionQuery(
      ProtectionActor actor,
      ProtectionAction action,
      Optional<ProtectionPosition> source,
      Optional<ProtectionPosition> destination,
      NamespacedId cause,
      Map<String, String> context) {
    this(actor, action, source, destination, Optional.empty(), cause, context);
  }

  /**
   * Creates the common single-location query form.
   *
   * @param actor initiating actor
   * @param action canonical attempted action
   * @param position target block
   * @param cause namespaced platform cause
   * @return single-location immutable query
   */
  public static ProtectionQuery at(
      ProtectionActor actor,
      ProtectionAction action,
      ProtectionPosition position,
      NamespacedId cause) {
    return new ProtectionQuery(
        actor, action, Optional.of(position), Optional.empty(), Optional.empty(), cause, Map.of());
  }
}
