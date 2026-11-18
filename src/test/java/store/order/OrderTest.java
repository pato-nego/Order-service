package store.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class OrderTest {

    private static final UUID TOMATO = UUID.fromString("0195abfb-7074-73a9-9d26-b4b9fbaab0a8");
    private static final UUID CHEESE = UUID.fromString("0195abfe-e416-7052-be3b-27cdaf12a984");

    @Test
    void itemTotalIsUnitPriceTimesQuantityWithTwoDecimals() {
        final OrderItem item = new OrderItem(TOMATO, 2, new BigDecimal("10.12"));

        assertThat(item.getUnitPrice()).isEqualTo(new BigDecimal("10.12"));
        assertThat(item.getTotal()).isEqualTo(new BigDecimal("20.24"));
    }

    @Test
    void itemRoundsTheUnitPriceHalfUp() {
        final OrderItem item = new OrderItem(TOMATO, 3, new BigDecimal("0.125"));

        assertThat(item.getUnitPrice()).isEqualTo(new BigDecimal("0.13"));
        assertThat(item.getTotal()).isEqualTo(new BigDecimal("0.39"));
    }

    @Test
    void orderTotalIsTheSumOfTheItemsAndEachItemPointsBackToTheOrder() {
        final Order order = new Order("acc-1", List.of(
            new OrderItem(TOMATO, 2, new BigDecimal("10.12")),
            new OrderItem(CHEESE, 10, new BigDecimal("0.62"))
        ));

        assertThat(order.getTotal()).isEqualByComparingTo("26.44");
        assertThat(order.getIdAccount()).isEqualTo("acc-1");
        assertThat(order.getDate()).isNotNull();
        assertThat(order.getItems()).hasSize(2).allSatisfy(item -> assertThat(item.getOrder()).isSameAs(order));
    }

}
