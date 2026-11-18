package store.order;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

public final class OrderParser {

    public static OrderOut to(Order order) {
        return to(order, Order.BASE_CURRENCY, BigDecimal.ONE);
    }

    public static OrderOut to(Order order, String currency, BigDecimal rate) {
        final List<ItemOut> items = order.getItems().stream()
            .map(item -> new ItemOut(
                item.getId(),
                new ItemOut.Product(item.getIdProduct()),
                item.getQuantity(),
                convert(item.getTotal(), rate)))
            .toList();

        final BigDecimal total = items.stream()
            .map(ItemOut::total)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        return new OrderOut(order.getId(), order.getDate(), currency, items, total);
    }

    public static List<OrderSummaryOut> toSummary(List<Order> orders) {
        return orders.stream()
            .map(order -> new OrderSummaryOut(order.getId(), order.getDate(), order.getTotal()))
            .toList();
    }

    private static BigDecimal convert(BigDecimal value, BigDecimal rate) {
        return value.multiply(rate).setScale(2, RoundingMode.HALF_UP);
    }

}
