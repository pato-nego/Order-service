package store.order;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderRepository extends JpaRepository<Order, UUID> {

    @EntityGraph(attributePaths = "items")
    List<Order> findByIdAccountOrderByDateAsc(String idAccount);

    @EntityGraph(attributePaths = "items")
    Optional<Order> findByIdAndIdAccount(UUID id, String idAccount);
}
