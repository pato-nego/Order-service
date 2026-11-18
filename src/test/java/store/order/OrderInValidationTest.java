package store.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import jakarta.validation.Validation;
import jakarta.validation.Validator;

class OrderInValidationTest {

    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();
    private static final UUID TOMATO = UUID.fromString("0195abfb-7074-73a9-9d26-b4b9fbaab0a8");

    @Test
    void aCompleteOrderIsValid() {
        assertThat(invalidFields(new OrderIn(List.of(new ItemIn(TOMATO, 2))))).isEmpty();
    }

    @Test
    void anOrderWithoutItemsIsInvalid() {
        assertThat(invalidFields(new OrderIn(List.of()))).containsExactly("items");
        assertThat(invalidFields(new OrderIn(null))).containsExactly("items");
    }

    @Test
    void aNullItemIsInvalid() {
        assertThat(invalidFields(new OrderIn(Arrays.asList((ItemIn) null)))).containsExactly("items[0].<list element>");
    }

    @Test
    void anItemNeedsTheProductAndAPositiveQuantity() {
        assertThat(invalidFields(new OrderIn(List.of(new ItemIn(null, 1))))).containsExactly("items[0].idProduct");
        assertThat(invalidFields(new OrderIn(List.of(new ItemIn(TOMATO, null))))).containsExactly("items[0].quantity");
        assertThat(invalidFields(new OrderIn(List.of(new ItemIn(TOMATO, 0))))).containsExactly("items[0].quantity");
        assertThat(invalidFields(new OrderIn(List.of(new ItemIn(TOMATO, -3))))).containsExactly("items[0].quantity");
    }

    private static List<String> invalidFields(OrderIn order) {
        return VALIDATOR.validate(order).stream()
            .map(violation -> violation.getPropertyPath().toString())
            .sorted()
            .toList();
    }

}
