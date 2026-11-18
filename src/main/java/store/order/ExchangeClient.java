package store.order;

import java.math.BigDecimal;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;

@FeignClient(
    name = "exchange",
    url = "${store.exchange.url}"
)
public interface ExchangeClient {

    @GetMapping("/exchanges/{from}/{to}")
    Quote quote(
        @RequestHeader("id-account") String idAccount,
        @PathVariable("from") String from,
        @PathVariable("to") String to
    );

    record Quote(BigDecimal sell) {

    }

}
