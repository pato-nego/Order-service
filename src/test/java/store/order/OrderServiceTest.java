package store.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import feign.FeignException;
import feign.Request;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    private static final String ACCOUNT = "acc-1";
    private static final UUID TOMATO = UUID.fromString("0195abfb-7074-73a9-9d26-b4b9fbaab0a8");
    private static final UUID CHEESE = UUID.fromString("0195abfe-e416-7052-be3b-27cdaf12a984");
    private static final UUID ORDER_ID = UUID.fromString("0195ac33-73e5-7cb3-90ca-7b5e7e549569");

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private ProductClient productClient;

    @Mock
    private ExchangeClient exchangeClient;

    @InjectMocks
    private OrderService orderService;

    @Test
    void createSumsTheItemsAndStoresInUsd() {
        when(productClient.findById(TOMATO)).thenReturn(new ProductClient.Product(TOMATO, new BigDecimal("10.12")));
        when(productClient.findById(CHEESE)).thenReturn(new ProductClient.Product(CHEESE, new BigDecimal("0.62")));
        when(orderRepository.save(any(Order.class))).thenAnswer(call -> call.getArgument(0));

        final OrderOut created = orderService.create(ACCOUNT, request(item(TOMATO, 2), item(CHEESE, 10)));

        assertThat(created.currency()).isEqualTo("USD");
        assertThat(created.items()).extracting(ItemOut::total)
            .containsExactly(new BigDecimal("20.24"), new BigDecimal("6.20"));
        assertThat(created.total()).isEqualByComparingTo("26.44");
        assertThat(created.date()).isNotNull();

        final ArgumentCaptor<Order> saved = ArgumentCaptor.forClass(Order.class);
        verify(orderRepository).save(saved.capture());
        assertThat(saved.getValue().getIdAccount()).isEqualTo(ACCOUNT);
        assertThat(saved.getValue().getItems()).extracting(OrderItem::getUnitPrice)
            .containsExactly(new BigDecimal("10.12"), new BigDecimal("0.62"));
    }

    @Test
    void createAsksTheProductServiceOnceForRepeatedProducts() {
        when(productClient.findById(TOMATO)).thenReturn(new ProductClient.Product(TOMATO, new BigDecimal("10.12")));
        when(orderRepository.save(any(Order.class))).thenAnswer(call -> call.getArgument(0));

        final OrderOut created = orderService.create(ACCOUNT, request(item(TOMATO, 1), item(TOMATO, 2)));

        assertThat(created.total()).isEqualByComparingTo("30.36");
        verify(productClient).findById(TOMATO);
    }

    @Test
    void createWithUnknownProductIsBadRequest() {
        when(productClient.findById(TOMATO)).thenThrow(feignError(404));

        assertStatus(() -> orderService.create(ACCOUNT, request(item(TOMATO, 1))), HttpStatus.BAD_REQUEST);
        verify(orderRepository, never()).save(any());
    }

    @Test
    void createWhenProductServiceIsDownIsServiceUnavailable() {
        when(productClient.findById(TOMATO)).thenThrow(feignError(500));

        assertStatus(() -> orderService.create(ACCOUNT, request(item(TOMATO, 1))), HttpStatus.SERVICE_UNAVAILABLE);
        verify(orderRepository, never()).save(any());
    }

    @Test
    void withoutAccountIsUnauthorized() {
        assertStatus(() -> orderService.create(null, request(item(TOMATO, 1))), HttpStatus.UNAUTHORIZED);
        assertStatus(() -> orderService.findAll(" "), HttpStatus.UNAUTHORIZED);
        assertStatus(() -> orderService.findById(null, ORDER_ID, null), HttpStatus.UNAUTHORIZED);
        verifyNoInteractions(productClient, orderRepository, exchangeClient);
    }

    @Test
    void findAllReturnsTheSummaryOfTheOrdersOfTheAccount() {
        final OrderSummaryOut summary = new OrderSummaryOut(ORDER_ID, LocalDateTime.of(2026, 11, 20, 10, 0), new BigDecimal("26.44"));
        when(orderRepository.findSummaryByIdAccount(ACCOUNT)).thenReturn(List.of(summary));

        final List<OrderSummaryOut> orders = orderService.findAll(ACCOUNT);

        assertThat(orders).containsExactly(summary);
    }

    @Test
    void someoneElsesOrderIsNotFound() {
        when(orderRepository.findByIdAndIdAccount(ORDER_ID, ACCOUNT)).thenReturn(Optional.empty());

        assertStatus(() -> orderService.findById(ACCOUNT, ORDER_ID, null), HttpStatus.NOT_FOUND);
    }

    @Test
    void findByIdWithoutCurrencyStaysInUsdAndSkipsTheExchange() {
        when(orderRepository.findByIdAndIdAccount(ORDER_ID, ACCOUNT)).thenReturn(Optional.of(storedOrder()));

        final OrderOut usd = orderService.findById(ACCOUNT, ORDER_ID, null);
        final OrderOut usdAgain = orderService.findById(ACCOUNT, ORDER_ID, "usd");

        assertThat(usd.currency()).isEqualTo("USD");
        assertThat(usd.total()).isEqualByComparingTo("26.44");
        assertThat(usdAgain.currency()).isEqualTo("USD");
        verifyNoInteractions(exchangeClient);
    }

    @Test
    void findByIdConvertsEachItemWithTheSellRate() {
        when(orderRepository.findByIdAndIdAccount(ORDER_ID, ACCOUNT)).thenReturn(Optional.of(storedOrder()));
        when(exchangeClient.quote(ACCOUNT, "USD", "BRL")).thenReturn(new ExchangeClient.Quote(new BigDecimal("6.00")));

        final OrderOut brl = orderService.findById(ACCOUNT, ORDER_ID, "brl");

        assertThat(brl.currency()).isEqualTo("BRL");
        assertThat(brl.items()).extracting(ItemOut::total)
            .containsExactly(new BigDecimal("121.44"), new BigDecimal("37.20"));
        assertThat(brl.total()).isEqualByComparingTo("158.64");
    }

    @Test
    void findByIdWithUnsupportedCurrencyIsUnprocessable() {
        when(orderRepository.findByIdAndIdAccount(ORDER_ID, ACCOUNT)).thenReturn(Optional.of(storedOrder()));
        when(exchangeClient.quote(ACCOUNT, "USD", "XYZ")).thenThrow(feignError(404));

        assertStatus(() -> orderService.findById(ACCOUNT, ORDER_ID, "XYZ"), HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void findByIdWithMalformedCurrencyIsUnprocessableWithoutCallingTheExchange() {
        when(orderRepository.findByIdAndIdAccount(ORDER_ID, ACCOUNT)).thenReturn(Optional.of(storedOrder()));

        assertStatus(() -> orderService.findById(ACCOUNT, ORDER_ID, "REAL"), HttpStatus.UNPROCESSABLE_ENTITY);
        verifyNoInteractions(exchangeClient);
    }

    @Test
    void findByIdWhenExchangeIsDownIsServiceUnavailable() {
        when(orderRepository.findByIdAndIdAccount(ORDER_ID, ACCOUNT)).thenReturn(Optional.of(storedOrder()));
        when(exchangeClient.quote(ACCOUNT, "USD", "BRL")).thenThrow(feignError(502));

        assertStatus(() -> orderService.findById(ACCOUNT, ORDER_ID, "BRL"), HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void createReusesTheCachedProductAcrossOrders() {
        when(productClient.findById(TOMATO)).thenReturn(new ProductClient.Product(TOMATO, new BigDecimal("10.12")));
        when(orderRepository.save(any(Order.class))).thenAnswer(call -> call.getArgument(0));

        orderService.create(ACCOUNT, request(item(TOMATO, 1)));
        orderService.create("acc-2", request(item(TOMATO, 3)));

        verify(productClient, times(1)).findById(TOMATO);
    }

    @Test
    void createDoesNotCacheAProductFailure() {
        when(productClient.findById(TOMATO))
            .thenThrow(feignError(500))
            .thenReturn(new ProductClient.Product(TOMATO, new BigDecimal("10.12")));
        when(orderRepository.save(any(Order.class))).thenAnswer(call -> call.getArgument(0));

        assertStatus(() -> orderService.create(ACCOUNT, request(item(TOMATO, 1))), HttpStatus.SERVICE_UNAVAILABLE);
        final OrderOut created = orderService.create(ACCOUNT, request(item(TOMATO, 1)));

        assertThat(created.total()).isEqualByComparingTo("10.12");
        verify(productClient, times(2)).findById(TOMATO);
    }

    @Test
    void findByIdReusesTheCachedRateAcrossRequests() {
        when(orderRepository.findByIdAndIdAccount(ORDER_ID, ACCOUNT)).thenReturn(Optional.of(storedOrder()));
        when(exchangeClient.quote(ACCOUNT, "USD", "BRL")).thenReturn(new ExchangeClient.Quote(new BigDecimal("6.00")));

        final OrderOut first = orderService.findById(ACCOUNT, ORDER_ID, "BRL");
        final OrderOut second = orderService.findById(ACCOUNT, ORDER_ID, "brl");

        assertThat(second.total()).isEqualByComparingTo(first.total());
        verify(exchangeClient, times(1)).quote(ACCOUNT, "USD", "BRL");
    }

    @Test
    void findByIdDoesNotCacheAnExchangeFailure() {
        when(orderRepository.findByIdAndIdAccount(ORDER_ID, ACCOUNT)).thenReturn(Optional.of(storedOrder()));
        when(exchangeClient.quote(ACCOUNT, "USD", "BRL"))
            .thenThrow(feignError(502))
            .thenReturn(new ExchangeClient.Quote(new BigDecimal("6.00")));

        assertStatus(() -> orderService.findById(ACCOUNT, ORDER_ID, "BRL"), HttpStatus.SERVICE_UNAVAILABLE);
        final OrderOut brl = orderService.findById(ACCOUNT, ORDER_ID, "BRL");

        assertThat(brl.total()).isEqualByComparingTo("158.64");
        verify(exchangeClient, times(2)).quote(ACCOUNT, "USD", "BRL");
    }

    private static OrderIn request(ItemIn... items) {
        return new OrderIn(List.of(items));
    }

    private static ItemIn item(UUID idProduct, Integer quantity) {
        return new ItemIn(idProduct, quantity);
    }

    private static Order storedOrder() {
        return new Order(ACCOUNT, List.of(
            new OrderItem(TOMATO, 2, new BigDecimal("10.12")),
            new OrderItem(CHEESE, 10, new BigDecimal("0.62"))
        ));
    }

    private static FeignException feignError(int status) {
        final Request request = Request.create(
            Request.HttpMethod.GET, "http://service/path", Map.of(), null, StandardCharsets.UTF_8, null
        );
        return FeignException.errorStatus("call", feign.Response.builder()
            .status(status)
            .reason("error")
            .request(request)
            .headers(Map.of())
            .build());
    }

    private static void assertStatus(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, HttpStatus expected) {
        assertThatThrownBy(call)
            .isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode().value()).isEqualTo(expected.value()));
    }

}
