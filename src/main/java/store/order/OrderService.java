package store.order;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import feign.FeignException;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class OrderService {

    private static final Pattern CURRENCY_CODE = Pattern.compile("^[A-Z]{3}$");

    private final OrderRepository orderRepository;
    private final ProductClient productClient;
    private final ExchangeClient exchangeClient;

    public OrderOut create(String idAccount, OrderIn request) {
        requireAccount(idAccount);

        final Map<UUID, ProductClient.Product> products = new HashMap<>();
        final List<OrderItem> items = request.items().stream()
            .map(item -> {
                final ProductClient.Product product = products.computeIfAbsent(item.idProduct(), this::findProduct);
                return new OrderItem(item.idProduct(), item.quantity(), product.price());
            })
            .toList();

        return OrderParser.to(orderRepository.save(new Order(idAccount, items)));
    }

    public List<OrderSummaryOut> findAll(String idAccount) {
        requireAccount(idAccount);
        return OrderParser.toSummary(orderRepository.findByIdAccountOrderByDateAsc(idAccount));
    }

    public OrderOut findById(String idAccount, UUID id, String currency) {
        requireAccount(idAccount);

        final Order order = orderRepository.findByIdAndIdAccount(id, idAccount)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found"));

        if (currency == null || currency.isBlank() || Order.BASE_CURRENCY.equalsIgnoreCase(currency.trim())) {
            return OrderParser.to(order);
        }

        final String code = currency.trim().toUpperCase();
        return OrderParser.to(order, code, findRate(idAccount, code));
    }

    private ProductClient.Product findProduct(UUID idProduct) {
        try {
            final ProductClient.Product product = productClient.findById(idProduct);
            if (product == null || product.price() == null) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Product service unavailable");
            }
            return product;
        } catch (FeignException.NotFound | FeignException.BadRequest e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Product not found: " + idProduct, e);
        } catch (FeignException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Product service unavailable", e);
        }
    }

    private BigDecimal findRate(String idAccount, String currency) {
        if (!CURRENCY_CODE.matcher(currency).matches()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Unsupported currency");
        }

        try {
            final ExchangeClient.Quote quote = exchangeClient.quote(idAccount, Order.BASE_CURRENCY, currency);
            if (quote == null || quote.sell() == null || quote.sell().signum() <= 0) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Exchange service unavailable");
            }
            return quote.sell();
        } catch (FeignException.NotFound | FeignException.BadRequest | FeignException.UnprocessableEntity e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Unsupported currency", e);
        } catch (FeignException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Exchange service unavailable", e);
        }
    }

    private void requireAccount(String idAccount) {
        if (idAccount == null || idAccount.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
    }

}
