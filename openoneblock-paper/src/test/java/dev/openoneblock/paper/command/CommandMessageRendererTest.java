package dev.openoneblock.paper.command;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;

class CommandMessageRendererTest {
  @Test
  void rendersFallbackAndLiteralPlaceholdersWithoutReadyRuntime() {
    CommandMessageRenderer renderer = new CommandMessageRenderer(Optional::empty);

    Component result = renderer.render("command.create.success", Map.of("island_id", "island-123"));

    assertEquals(Component.text("Island island-123 is ready."), result);
  }
}
