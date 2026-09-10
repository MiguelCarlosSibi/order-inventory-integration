package edu.cit.sibi.shop.repository;

import edu.cit.sibi.shop.model.Order;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderRepository extends JpaRepository<Order, Long> {
}
