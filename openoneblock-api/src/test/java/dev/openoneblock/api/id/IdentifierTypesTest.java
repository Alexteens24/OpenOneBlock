package dev.openoneblock.api.id;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class IdentifierTypesTest {
  private static final UUID VERSION_ONE_UUID =
      UUID.fromString("2f1c6f20-4434-11ef-9b1f-325096b39f47");

  @ParameterizedTest
  @MethodSource("uuidIdentifiers")
  void generatedRuntimeIdentifiersUseUuidVersionFour(
      Supplier<UUID> generatedValue, Function<String, UUID> parsedValue) {
    UUID generated = generatedValue.get();

    assertEquals(4, generated.version());
    assertEquals(generated, parsedValue.apply(generated.toString()));
  }

  @Test
  void importedRuntimeIdentifiersMayUseOtherUuidVersions() {
    assertEquals(VERSION_ONE_UUID, IslandId.parse(VERSION_ONE_UUID.toString()).value());
    assertEquals(VERSION_ONE_UUID, OperationId.parse(VERSION_ONE_UUID.toString()).value());
  }

  @Test
  void semanticNamespacedIdentifiersRoundTrip() {
    assertEquals("server:primary", ShardGroupId.parse("server:primary").toString());
    assertEquals("openoneblock:overworld", DimensionId.parse("openoneblock:overworld").toString());
  }

  private static Stream<Arguments> uuidIdentifiers() {
    return Stream.of(
        Arguments.of(
            (Supplier<UUID>) () -> IslandId.generate().value(),
            (Function<String, UUID>) value -> IslandId.parse(value).value()),
        Arguments.of(
            (Supplier<UUID>) () -> OperationId.generate().value(),
            (Function<String, UUID>) value -> OperationId.parse(value).value()));
  }
}
