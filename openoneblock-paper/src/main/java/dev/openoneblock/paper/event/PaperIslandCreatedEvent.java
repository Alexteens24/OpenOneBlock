package dev.openoneblock.paper.event;

import dev.openoneblock.api.event.IslandCreatedEvent;
import java.util.Objects;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Bukkit-facing wrapper around the immutable public OpenOneBlock creation event. */
public final class PaperIslandCreatedEvent extends Event {
  private static final HandlerList HANDLERS = new HandlerList();

  /** Immutable platform-neutral event payload. */
  private final IslandCreatedEvent event;

  /**
   * Creates a synchronous global-scheduler event.
   *
   * @param event immutable creation payload
   */
  public PaperIslandCreatedEvent(IslandCreatedEvent event) {
    this.event = Objects.requireNonNull(event, "event");
  }

  /**
   * Returns the immutable public event payload.
   *
   * @return creation event
   */
  public IslandCreatedEvent event() {
    return event;
  }

  /** {@inheritDoc} */
  @Override
  public HandlerList getHandlers() {
    return HANDLERS;
  }

  /**
   * Returns Bukkit's handler registry for this event type.
   *
   * @return shared handler list
   */
  public static HandlerList getHandlerList() {
    return HANDLERS;
  }
}
