package edu.cit.sibi.shop.repository;

import edu.cit.sibi.shop.model.Order;
import edu.cit.sibi.shop.model.OrderStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OrderRepository extends JpaRepository<Order, Long> {
    List<Order> findByStatusOrderByCreatedAtAsc(OrderStatus status);
}