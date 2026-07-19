package dev.openoneblock.persistence.sqlite.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.openoneblock.api.id.DimensionId;
import dev.openoneblock.api.id.OperationId;
import dev.openoneblock.api.id.ShardGroupId;
import dev.openoneblock.api.id.WorldId;
import dev.openoneblock.core.locator.PersistedWorldProjection;
import dev.openoneblock.core.locator.WorldEnvironment;
import dev.openoneblock.core.locator.WorldProjectionAdoptionRequest;
import dev.openoneblock.core.locator.WorldProjectionDefinition;
import dev.openoneblock.core.locator.WorldProjectionDriftKind;
import dev.openoneblock.core.locator.WorldProjectionVerification;
import dev.openoneblock.persistence.sqlite.SqliteConnectionFactory;
import dev.openoneblock.persistence.sqlite.migration.SqliteSchemaMigrator;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteWorldProjectionCatalogTest {
  private static final ShardGroupId SHARD = ShardGroupId.parse("openoneblock:primary");
  private static final DimensionId OVERWORLD = DimensionId.parse("openoneblock:overworld");
  private static final DimensionId END = DimensionId.parse("openoneblock:end");
  private static final Instant REGISTERED_AT = Instant.parse("2026-07-19T02:00:00Z");
  private static final String GEOMETRY = "a".repeat(64);

  @TempDir Path temporaryDirectory;

  @Test
  void firstStartupRegistersAllProjectionsAndRestartBuildsRegistry() throws Exception {
    TestContext context = context("register.db");
    List<WorldProjectionDefinition> observed = List.of(overworld(), end());

    WorldProjectionVerification.Verified first =
        assertInstanceOf(
            WorldProjectionVerification.Verified.class,
            join(context.catalog().verifyOrRegister(observed)));
    WorldProjectionVerification.Verified restart =
        assertInstanceOf(
            WorldProjectionVerification.Verified.class,
            join(context().catalog().verifyOrRegister(observed)));

    assertEquals(2, first.projections().size());
    assertEquals(2, restart.toRuntimeRegistry().size());
    assertTrue(
        restart
            .toRuntimeRegistry()
            .resolve(overworld().worldId())
            .filter(projection -> projection.dimensionId().equals(OVERWORLD))
            .isPresent());
    assertEquals(2, count(context.factory(), "world_projections"));
  }

  @Test
  void replacementWorldUuidIsDetectedWithoutMutatingAuthority() throws Exception {
    TestContext context = context("replacement.db");
    WorldProjectionDefinition original = overworld();
    join(context.catalog().verifyOrRegister(List.of(original)));
    WorldProjectionDefinition replacement =
        definition(
            OVERWORLD,
            original.worldName(),
            "00000000-0000-0000-0000-000000000099",
            WorldEnvironment.NORMAL,
            GEOMETRY);

    WorldProjectionVerification.Drifted drifted =
        assertInstanceOf(
            WorldProjectionVerification.Drifted.class,
            join(context.catalog().verifyOrRegister(List.of(replacement))));

    assertEquals(1, drifted.drifts().size());
    assertEquals(WorldProjectionDriftKind.WORLD_ID_CHANGED, drifted.drifts().getFirst().kind());
    assertInstanceOf(
        WorldProjectionVerification.Verified.class,
        join(context().catalog().verifyOrRegister(List.of(original))));
  }

  @Test
  void environmentGeometryMissingAndUnconfiguredRowsAllFailClosed() throws Exception {
    TestContext context = context("drift.db");
    join(context.catalog().verifyOrRegister(List.of(overworld(), end())));
    WorldProjectionDefinition changed =
        definition(
            OVERWORLD,
            "renamed_world",
            overworld().worldId().toString(),
            WorldEnvironment.NETHER,
            "b".repeat(64));
    WorldProjectionDefinition added =
        definition(
            DimensionId.parse("openoneblock:nether"),
            "openoneblock_nether",
            "00000000-0000-0000-0000-000000000003",
            WorldEnvironment.NETHER,
            "b".repeat(64));

    WorldProjectionVerification.Drifted drifted =
        assertInstanceOf(
            WorldProjectionVerification.Drifted.class,
            join(context.catalog().verifyOrRegister(List.of(changed, added))));

    assertTrue(
        drifted.drifts().stream()
            .map(drift -> drift.kind())
            .toList()
            .containsAll(
                List.of(
                    WorldProjectionDriftKind.WORLD_NAME_CHANGED,
                    WorldProjectionDriftKind.ENVIRONMENT_CHANGED,
                    WorldProjectionDriftKind.GEOMETRY_FINGERPRINT_CHANGED,
                    WorldProjectionDriftKind.MISSING_PERSISTED_PROJECTION,
                    WorldProjectionDriftKind.UNCONFIGURED_PERSISTED_PROJECTION)));
  }

  @Test
  void duplicateObservedWorldUuidIsRejectedBeforeDatabaseMutation() throws Exception {
    TestContext context = context("duplicate.db");
    WorldProjectionDefinition duplicate =
        definition(
            END,
            "openoneblock_end",
            overworld().worldId().toString(),
            WorldEnvironment.THE_END,
            GEOMETRY);

    CompletionException exception =
        assertThrows(
            CompletionException.class,
            () -> join(context.catalog().verifyOrRegister(List.of(overworld(), duplicate))));

    assertInstanceOf(IllegalArgumentException.class, exception.getCause());
    assertEquals(0, count(context.factory(), "world_projections"));
  }

  @Test
  void dimensionsInOneShardCannotUseDifferentGridFingerprint() throws Exception {
    TestContext context = context("geometry-conflict.db");
    WorldProjectionDefinition incompatibleEnd =
        definition(
            END,
            "openoneblock_end",
            "00000000-0000-0000-0000-000000000002",
            WorldEnvironment.THE_END,
            "b".repeat(64));

    CompletionException exception =
        assertThrows(
            CompletionException.class,
            () -> join(context.catalog().verifyOrRegister(List.of(overworld(), incompatibleEnd))));

    assertInstanceOf(IllegalArgumentException.class, exception.getCause());
    assertEquals(0, count(context.factory(), "world_projections"));
  }

  @Test
  void explicitAdoptionIsOptimisticAuditedAndIdempotent() throws Exception {
    TestContext context = context("adopt.db");
    PersistedWorldProjection original =
        assertInstanceOf(
                WorldProjectionVerification.Verified.class,
                join(context.catalog().verifyOrRegister(List.of(overworld()))))
            .projections()
            .getFirst();
    WorldProjectionDefinition replacement =
        definition(
            OVERWORLD,
            "openoneblock_overworld",
            "00000000-0000-0000-0000-000000000099",
            WorldEnvironment.NORMAL,
            GEOMETRY);
    WorldProjectionAdoptionRequest request =
        new WorldProjectionAdoptionRequest(
            OperationId.parse("00000000-0000-0000-0000-000000000010"),
            replacement,
            original.version(),
            REGISTERED_AT.plusSeconds(60));

    PersistedWorldProjection adopted = join(context.catalog().adopt(request));
    PersistedWorldProjection replay = join(context.catalog().adopt(request));

    assertEquals(adopted, replay);
    assertEquals(1, adopted.version());
    assertEquals(replacement.worldId(), adopted.definition().worldId());
    assertEquals(1, count(context.factory(), "world_projection_repairs"));
    assertInstanceOf(
        WorldProjectionVerification.Verified.class,
        join(context.catalog().verifyOrRegister(List.of(replacement))));
  }

  @Test
  void adoptionRejectsStaleVersionAndOperationReuseForDifferentOutcome() throws Exception {
    TestContext context = context("adopt-conflict.db");
    join(context.catalog().verifyOrRegister(List.of(overworld())));
    WorldProjectionDefinition replacement =
        definition(
            OVERWORLD,
            "openoneblock_overworld",
            "00000000-0000-0000-0000-000000000099",
            WorldEnvironment.NORMAL,
            GEOMETRY);
    OperationId operationId = OperationId.parse("00000000-0000-0000-0000-000000000011");

    CompletionException stale =
        assertThrows(
            CompletionException.class,
            () ->
                join(
                    context
                        .catalog()
                        .adopt(
                            new WorldProjectionAdoptionRequest(
                                operationId, replacement, 8, REGISTERED_AT.plusSeconds(60)))));
    assertInstanceOf(WorldProjectionAdoptionConflictException.class, stale.getCause());

    WorldProjectionAdoptionRequest accepted =
        new WorldProjectionAdoptionRequest(
            operationId, replacement, 0, REGISTERED_AT.plusSeconds(60));
    join(context.catalog().adopt(accepted));
    WorldProjectionAdoptionRequest conflictingReplay =
        new WorldProjectionAdoptionRequest(
            operationId,
            definition(
                OVERWORLD,
                "openoneblock_overworld",
                "00000000-0000-0000-0000-000000000098",
                WorldEnvironment.NORMAL,
                GEOMETRY),
            0,
            REGISTERED_AT.plusSeconds(60));

    CompletionException conflict =
        assertThrows(
            CompletionException.class, () -> join(context.catalog().adopt(conflictingReplay)));
    assertInstanceOf(WorldProjectionAdoptionConflictException.class, conflict.getCause());
  }

  private TestContext context(String databaseName) throws Exception {
    SqliteConnectionFactory factory =
        new SqliteConnectionFactory(temporaryDirectory.resolve(databaseName), 100);
    new SqliteSchemaMigrator(factory).migrate();
    return new TestContext(
        factory,
        new SqliteWorldProjectionCatalog(
            factory, Runnable::run, Clock.fixed(REGISTERED_AT, ZoneOffset.UTC)));
  }

  private TestContext context() throws Exception {
    return context("register.db");
  }

  private static WorldProjectionDefinition overworld() {
    return definition(
        OVERWORLD,
        "openoneblock_overworld",
        "00000000-0000-0000-0000-000000000001",
        WorldEnvironment.NORMAL,
        GEOMETRY);
  }

  private static WorldProjectionDefinition end() {
    return definition(
        END,
        "openoneblock_end",
        "00000000-0000-0000-0000-000000000002",
        WorldEnvironment.THE_END,
        GEOMETRY);
  }

  private static WorldProjectionDefinition definition(
      DimensionId dimension,
      String worldName,
      String worldId,
      WorldEnvironment environment,
      String geometryFingerprint) {
    return new WorldProjectionDefinition(
        SHARD,
        dimension,
        worldName,
        WorldId.of(UUID.fromString(worldId)),
        environment,
        geometryFingerprint);
  }

  private static <T> T join(java.util.concurrent.CompletionStage<T> stage) {
    return stage.toCompletableFuture().join();
  }

  private static int count(SqliteConnectionFactory factory, String table) throws Exception {
    try (Connection connection = factory.open();
        Statement statement = connection.createStatement();
        ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
      assertTrue(result.next());
      return result.getInt(1);
    }
  }

  private record TestContext(
      SqliteConnectionFactory factory, SqliteWorldProjectionCatalog catalog) {}
}
