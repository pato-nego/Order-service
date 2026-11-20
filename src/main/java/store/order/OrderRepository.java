package store.order;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderRepository extends JpaRepository<Order, UUID> {

    @Query("select new store.order.OrderSummaryOut(o.id, o.date, o.total) from store.order.Order o "
        + "where o.idAccount = :idAccount order by o.date asc")
    List<OrderSummaryOut> findSummaryByIdAccount(@Param("idAccount") String idAccount);

    @EntityGraph(attributePaths = "items")
    Optional<Order> findByIdAndIdAccount(UUID id, String idAccount);
}
