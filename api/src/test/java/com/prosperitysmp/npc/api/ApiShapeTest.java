package com.prosperitysmp.npc.api;

import com.prosperitysmp.npc.api.charge.MeterQuery;
import com.prosperitysmp.npc.api.charge.MeterReading;
import com.prosperitysmp.npc.api.charge.UsageMeter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What an adapter compiled against API 1.2 depends on. A method added without a default, or removed, or a class that
 * stops being final, breaks adapters already built, so each shape is written out here.
 */
class ApiShapeTest {

    private static List<String> names(Class<?> type, boolean defaults) {
        return Arrays.stream(type.getDeclaredMethods())
                .filter(m -> !m.isSynthetic() && !Modifier.isStatic(m.getModifiers()))
                .filter(m -> m.isDefault() == defaults)
                .map(Method::getName)
                .sorted()
                .toList();
    }

    static Stream<Arguments> interfaces() {
        return Stream.of(
                Arguments.of(Piece.class, List.of("apiMajor", "apiMinor", "capabilities", "id"), List.of("label")),
                Arguments.of(MoneySource.class, List.of("pin"), List.of()),
                Arguments.of(MoneySource.Pin.class,
                        List.of("artifact", "balanceDollars", "enabled", "leg", "stillCurrent"), List.of()),
                Arguments.of(PropertyBackend.class,
                        List.of("available", "describe", "displayName", "findRegion", "installGuard", "observe",
                                "ownerReadAt", "ownerReadRegion", "regionAt"),
                        List.of("guardBindings")),
                Arguments.of(OwnershipHooks.class, List.of("beginWrite"),
                        List.of("externalChange", "guarded", "isHeld", "markDirty")),
                Arguments.of(OwnershipToken.class, List.of("ownerId", "regionId", "sameIncarnation", "worldId"), List.of()),
                Arguments.of(RegionRef.class, List.of("regionId", "worldId"), List.of()),
                Arguments.of(RegionView.class, List.of("kind", "market", "matches", "ownerUuid"), List.of()),
                Arguments.of(UsageMeter.class, List.of("read"), List.of()));
    }

    @ParameterizedTest
    @MethodSource("interfaces")
    void eachInterfaceDeclaresExactlyTheDocumentedAbstractAndDefaultMethods(Class<?> type, List<String> abstractNames,
                                                                            List<String> defaultNames) {
        assertTrue(type.isInterface(), type.getName());
        assertTrue(Modifier.isPublic(type.getModifiers()), type.getName());
        assertEquals(abstractNames, names(type, false), type.getSimpleName() + " abstract methods");
        assertEquals(defaultNames, names(type, true), type.getSimpleName() + " default methods");
    }

    static Stream<Class<?>> finalClasses() {
        return Stream.of(Capabilities.class, Capabilities.Builder.class, GuardBinding.class, GuardResult.class,
                LegResponse.class, ProsperityNpcApi.class, MeterQuery.class, MeterReading.class, MeterReading.Builder.class);
    }

    @ParameterizedTest
    @MethodSource("finalClasses")
    void eachValueClassIsFinalAndOnlyBuiltThroughItsFactory(Class<?> type) {
        assertTrue(Modifier.isFinal(type.getModifiers()), type.getName() + " is final");
        assertTrue(Modifier.isPublic(type.getModifiers()), type.getName() + " is public");
        assertEquals(0, type.getConstructors().length, type.getName() + " has no public constructor");
        for (var constructor : type.getDeclaredConstructors())
            assertTrue(Modifier.isPrivate(constructor.getModifiers()), type.getName() + " constructor is private");
    }

    @ParameterizedTest
    @MethodSource("finalClasses")
    void noValueClassExposesAPublicField(Class<?> type) {
        for (Field field : type.getDeclaredFields()) {
            if (type == ProsperityNpcApi.class || type == MeterReading.class) {
                if (Modifier.isStatic(field.getModifiers()) && Modifier.isFinal(field.getModifiers())
                        && Modifier.isPublic(field.getModifiers())) continue;
            }
            assertFalse(Modifier.isPublic(field.getModifiers()), type.getSimpleName() + "." + field.getName());
        }
    }

    @Test void theVersionConstantsAreStaticFinalInts() throws Exception {
        for (String name : List.of("MAJOR", "MINOR")) {
            Field field = ProsperityNpcApi.class.getField(name);
            assertEquals(int.class, field.getType(), name);
            assertEquals(Modifier.PUBLIC | Modifier.STATIC | Modifier.FINAL, field.getModifiers(), name);
        }
    }

    @Test void theEnumsKeepTheirNamesAndOrder() {
        assertEquals(List.of("WRITE_HOOK", "VETO_EVENTS", "NONE"), Arrays.stream(GuardMode.values()).map(Enum::name).toList());
        assertEquals(List.of("PRESENT", "MISSING", "UNREADABLE"), Arrays.stream(Presence.values()).map(Enum::name).toList());
        assertEquals(List.of("DEBIT", "CREDIT"), Arrays.stream(MoneySource.Leg.values()).map(Enum::name).toList());
    }

    @Test void theMeterReadingLimitIsOneBillionUnits() throws Exception {
        Field field = MeterReading.class.getField("MAX_UNITS");
        assertEquals(long.class, field.getType());
        assertEquals(1_000_000_000L, field.getLong(null));
    }

    @Test void aMoneySourceIsAPieceAndAUsageMeterIsAPiece() {
        assertTrue(Piece.class.isAssignableFrom(MoneySource.class));
        assertTrue(Piece.class.isAssignableFrom(PropertyBackend.class));
        assertTrue(Piece.class.isAssignableFrom(UsageMeter.class));
        assertFalse(Piece.class.isAssignableFrom(RegionView.class));
    }

    @Test void aRegionViewIsARegionRefAndAnOwnershipTokenIsNot() {
        assertTrue(RegionRef.class.isAssignableFrom(RegionView.class));
        assertFalse(RegionRef.class.isAssignableFrom(OwnershipToken.class));
    }
}
