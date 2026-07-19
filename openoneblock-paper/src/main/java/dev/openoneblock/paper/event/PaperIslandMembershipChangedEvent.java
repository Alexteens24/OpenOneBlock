package dev.openoneblock.paper.event;

import dev.openoneblock.api.event.IslandMembershipChangedEvent;
import java.util.Objects;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Bukkit-facing wrapper around an immutable committed team mutation event. */
public final class PaperIslandMembershipChangedEvent extends Event {
  private static final HandlerList HANDLERS = new HandlerList();
  private final IslandMembershipChangedEvent event;

  /** Creates the synchronous global-scheduler event. */
  public PaperIslandMembershipChangedEvent(IslandMembershipChangedEvent event) {
    this.event = Objects.requireNonNull(event, "event");
  }

  /** Returns the immutable platform-neutral event payload. */
  public IslandMembershipChangedEvent event() {
    return event;
  }

  /** {@inheritDoc} */
  @Override
  public HandlerList getHandlers() {
    return HANDLERS;
  }

  /** Returns Bukkit's shared handler registry. */
  public static HandlerList getHandlerList() {
    return HANDLERS;
  }
}
