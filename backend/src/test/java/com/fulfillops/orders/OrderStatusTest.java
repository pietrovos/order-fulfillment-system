package com.fulfillops.orders;

import static com.fulfillops.orders.OrderStatus.CANCELLED;
import static com.fulfillops.orders.OrderStatus.DRAFT;
import static com.fulfillops.orders.OrderStatus.PACKED;
import static com.fulfillops.orders.OrderStatus.PICKING;
import static com.fulfillops.orders.OrderStatus.RESERVED;
import static com.fulfillops.orders.OrderStatus.SHIPPED;
import static com.fulfillops.orders.OrderStatus.STOCK_EXCEPTION;
import static com.fulfillops.orders.OrderStatus.SUBMITTED;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class OrderStatusTest {

    /** The specification, written independently of the enum's own table. */
    private static final Map<OrderStatus, Set<OrderStatus>> SPEC = Map.of(
            DRAFT, EnumSet.of(SUBMITTED, CANCELLED),
            SUBMITTED, EnumSet.of(RESERVED, STOCK_EXCEPTION, CANCELLED),
            RESERVED, EnumSet.of(PICKING, CANCELLED),
            PICKING, EnumSet.of(PACKED, CANCELLED),
            PACKED, EnumSet.of(SHIPPED),
            SHIPPED, EnumSet.noneOf(OrderStatus.class),
            CANCELLED, EnumSet.noneOf(OrderStatus.class),
            STOCK_EXCEPTION, EnumSet.of(RESERVED, CANCELLED));

    static Stream<Arguments> everyPair() {
        return Stream.of(OrderStatus.values())
                .flatMap(from -> Stream.of(OrderStatus.values()).map(to -> Arguments.of(from, to)));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("everyPair")
    void allowsExactlyTheSpecifiedTransitions(OrderStatus from, OrderStatus to) {
        assertThat(from.canTransitionTo(to)).isEqualTo(SPEC.get(from).contains(to));
    }

    @Test
    void terminalStatesHaveNoWayOut() {
        assertThat(SHIPPED.isTerminal()).isTrue();
        assertThat(CANCELLED.isTerminal()).isTrue();
        assertThat(EnumSet.complementOf(EnumSet.of(SHIPPED, CANCELLED))).noneMatch(OrderStatus::isTerminal);
    }

    @Test
    void noStatusTransitionsToItself() {
        assertThat(OrderStatus.values()).noneMatch(s -> s.canTransitionTo(s));
    }

    @Test
    void reservationIsHeldExactlyBetweenReservedAndShipment() {
        assertThat(EnumSet.allOf(OrderStatus.class).stream().filter(OrderStatus::holdsReservation))
                .containsExactlyInAnyOrder(RESERVED, PICKING, PACKED);
    }
}
