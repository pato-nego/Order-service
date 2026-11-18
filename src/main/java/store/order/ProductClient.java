package store.order;

import java.math.BigDecimal;
import java.util.UUID;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@FeignClient(
    name = "product",
    url = "${store.product.url}"
)
public interface ProductClient {

    @GetMapping("/products/{id}")
    Product findById(
        @PathVariable("id") UUID id
    );

    record Product(UUID id, BigDecimal price) {

    }

}
