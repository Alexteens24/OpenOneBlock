package dev.openoneblock.core.slot;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class SlotIdTest {
  @Test
  void generatedIdsAreVersionFourAndImportedIdsRoundTrip() {
    SlotId generated = SlotId.generate();
    UUID imported = UUID.fromString("2f1c6f20-4434-11ef-9b1f-325096b39f47");

    assertEquals(4, generated.value().version());
    assertEquals(imported, SlotId.parse(imported.toString()).value());
  }
}
