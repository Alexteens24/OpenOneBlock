package dev.openoneblock.api.id;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class NamespacedIdTest {
  @ParameterizedTest
  @MethodSource("validIdentifiers")
  void parsesCanonicalIdentifiers(String input, String namespace, String value) {
    NamespacedId identifier = NamespacedId.parse(input);

    assertEquals(namespace, identifier.namespace());
    assertEquals(value, identifier.value());
    assertEquals(input, identifier.toString());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "openoneblock",
        ":plains",
        "openoneblock:",
        "openoneblock:phase:plains",
        "OpenOneBlock:plains",
        "openoneblock:Plains",
        "open one block:plains",
        "openoneblock:phase plains"
      })
  void rejectsNonCanonicalIdentifiers(String input) {
    assertThrows(IllegalArgumentException.class, () -> NamespacedId.parse(input));
  }

  private static Stream<Arguments> validIdentifiers() {
    return Stream.of(
        Arguments.of("openoneblock:plains", "openoneblock", "plains"),
        Arguments.of("server:phase/underground", "server", "phase/underground"),
        Arguments.of("addon_name:boss.v2-alpha", "addon_name", "boss.v2-alpha"));
  }
}
