package store.order;

import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
public class OrderResource implements OrderController {

    private final OrderService orderService;

    @Override
    public ResponseEntity<OrderOut> create(String idAccount, OrderIn in) {
        final OrderOut order = orderService.create(idAccount, in);
        return ResponseEntity.created(
            ServletUriComponentsBuilder
                .fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(order.id())
                .toUri()
        ).body(order);
    }

    @Override
    public ResponseEntity<List<OrderSummaryOut>> findAll(String idAccount) {
        return ResponseEntity.ok(orderService.findAll(idAccount));
    }

    @Override
    public ResponseEntity<OrderOut> findById(String idAccount, UUID id, String currency) {
        return ResponseEntity.ok(orderService.findById(idAccount, id, currency));
    }

    @Override
    public ResponseEntity<Void> healthCheck() {
        return ResponseEntity
            .ok()
            .build();
    }

}
