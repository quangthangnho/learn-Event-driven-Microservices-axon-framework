package com.develop.estore.ProductService.query;

import org.axonframework.config.ProcessingGroup;
import org.axonframework.eventhandling.EventHandler;
import org.springframework.stereotype.Component;

import com.develop.estore.ProductService.core.data.entity.ProductEntity;
import com.develop.estore.ProductService.core.data.repository.ProductRepository;
import com.develop.estore.ProductService.core.events.ProductCreatedEvent;

import core.event.ProductReservationCancelledEvent;
import core.event.ProductReservedEvent;
import lombok.extern.slf4j.Slf4j;

@Component
@ProcessingGroup("product-group")
@Slf4j
public class ProductEventsHandler {

    private final ProductRepository productRepository;

    public ProductEventsHandler(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    @EventHandler
    public void on(ProductCreatedEvent productCreatedEvent) {
        ProductEntity productEntity = new ProductEntity(
                productCreatedEvent.getProductId(),
                productCreatedEvent.getTitle(),
                productCreatedEvent.getQuantity(),
                productCreatedEvent.getPrice());
        productRepository.save(productEntity);
    }

    @EventHandler
    public void on(ProductReservedEvent productReservedEvent) {
        ProductEntity productEntity = productRepository.findByProductId(productReservedEvent.getProductId());
        productEntity.setQuantity(productEntity.getQuantity() - productReservedEvent.getQuantity());
        productRepository.save(productEntity);
        log.info(
                "save ENTITY: ProductReservedEvent is called for productId: {}",
                productReservedEvent.getProductId() + " and orderId: " + productReservedEvent.getOrderId());
    }

    @EventHandler
    public void on(ProductReservationCancelledEvent productReservationCancelledEvent) {
        ProductEntity productEntity =
                productRepository.findByProductId(productReservationCancelledEvent.getProductId());
        productEntity.setQuantity(productEntity.getQuantity() + productReservationCancelledEvent.getQuantity());
        productRepository.save(productEntity);
        log.info(
                "save ENTITY: ProductReservationCancelledEvent is called for productId: {}",
                productReservationCancelledEvent.getProductId() + " and orderId: "
                        + productReservationCancelledEvent.getOrderId());
    }
}
