package edu.cit.sibi;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Parent application class. Living in edu.cit.sibi means component scanning
 * automatically covers both edu.cit.sibi.shop (Order module) and
 * edu.cit.sibi.inventory (Inventory module) without any extra configuration.
 */
@SpringBootApplication
public class OrderInventoryApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderInventoryApplication.class, args);
    }
}
