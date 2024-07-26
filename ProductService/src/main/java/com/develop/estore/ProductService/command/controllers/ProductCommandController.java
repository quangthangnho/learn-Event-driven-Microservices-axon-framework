package com.develop.estore.ProductService.command.controllers;

import java.util.UUID;

import org.axonframework.commandhandling.gateway.CommandGateway;
import org.springframework.web.bind.annotation.*;

import com.develop.estore.ProductService.command.CreateProductCommand;
import com.develop.estore.ProductService.command.dto.request.CreateProductReq;

/**
 * @author admin
 */
@RestController
@RequestMapping("products")
public class ProductCommandController {

    private final CommandGateway commandGateway;

    public ProductCommandController(CommandGateway commandGateway) {
        this.commandGateway = commandGateway;
    }

    @PostMapping("/create")
    public String createProduct(@RequestBody CreateProductReq productReq) {
        CreateProductCommand createProductCommand = CreateProductCommand.builder()
                .title(productReq.getTitle())
                .quantity(productReq.getQuantity())
                .price(productReq.getPrice())
                .productId(UUID.randomUUID().toString())
                .build();
        String response;
        try {
            response = commandGateway.sendAndWait(createProductCommand);
        } catch (Exception e) {
            response = e.getLocalizedMessage();
        }
        return response;
    }
}