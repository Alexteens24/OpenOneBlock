package dev.openoneblock.paper.protection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.bukkit.event.EventHandler;
import org.junit.jupiter.api.Test;

class PaperProtectionListenerArchitectureTest {
  @Test
  void listenerDependsOnlyOnEngineSupplierAndQueryFactory() {
    Set<String> fieldTypes =
        Arrays.stream(PaperProtectionListener.class.getDeclaredFields())
            .filter(field -> !Modifier.isStatic(field.getModifiers()))
            .map(Field::getType)
            .map(Class::getName)
            .collect(Collectors.toSet());

    assertEquals(
        Set.of("java.util.function.Supplier", BukkitProtectionQueryFactory.class.getName()),
        fieldTypes);
    assertFalse(fieldTypes.stream().anyMatch(type -> type.contains("persistence")));
    assertFalse(fieldTypes.stream().anyMatch(type -> type.contains("runtime")));
  }

  @Test
  void everyPublicEventAdapterIsDeclaredAsAnEventHandler() {
    long adapterCount =
        Arrays.stream(PaperProtectionListener.class.getDeclaredMethods())
            .filter(method -> method.getName().startsWith("on"))
            .peek(
                method ->
                    assertTrue(method.isAnnotationPresent(EventHandler.class), method.getName()))
            .count();

    assertEquals(14, adapterCount);
  }
}
