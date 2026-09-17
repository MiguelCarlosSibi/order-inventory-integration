package edu.cit.sibi.shop.repository;

import edu.cit.sibi.shop.model.OrderItem;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {
}
