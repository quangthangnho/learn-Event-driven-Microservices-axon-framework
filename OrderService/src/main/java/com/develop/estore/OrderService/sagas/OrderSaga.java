package com.develop.estore.OrderService.sagas;

import java.io.Serializable;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.axonframework.commandhandling.gateway.CommandGateway;
import org.axonframework.deadline.DeadlineManager;
import org.axonframework.deadline.annotation.DeadlineHandler;
import org.axonframework.messaging.responsetypes.ResponseTypes;
import org.axonframework.modelling.saga.EndSaga;
import org.axonframework.modelling.saga.SagaEventHandler;
import org.axonframework.modelling.saga.StartSaga;
import org.axonframework.queryhandling.QueryGateway;
import org.axonframework.queryhandling.QueryUpdateEmitter;
import org.axonframework.spring.stereotype.Saga;
import org.springframework.beans.factory.annotation.Autowired;

import com.develop.estore.OrderService.command.ApproveOrderCommand;
import com.develop.estore.OrderService.command.RejectOrderCommand;
import com.develop.estore.OrderService.core.dto.response.OrderSumaryDto;
import com.develop.estore.OrderService.core.event.OrderApprovedEvent;
import com.develop.estore.OrderService.core.event.OrderCreatedEvent;
import com.develop.estore.OrderService.core.event.OrderRejectEvent;
import com.develop.estore.OrderService.query.FindOrderQuery;

import core.command.CancelProductReservationCommand;
import core.command.ProgressPaymentCommand;
import core.command.ReserveProductCommand;
import core.dto.User;
import core.event.PaymentProcessedEvent;
import core.event.ProductReservationCancelledEvent;
import core.event.ProductReservedEvent;
import core.query.FetchUserPaymentDetailsQuery;
import lombok.extern.slf4j.Slf4j;

@Saga
@Slf4j
public class OrderSaga implements Serializable {

    @Autowired
    private transient CommandGateway commandGateway;

    @Autowired
    private transient QueryGateway queryGateway;

    @Autowired
    private transient DeadlineManager deadlineManager;

    @Autowired
    private transient QueryUpdateEmitter queryUpdateEmitter;

    private final String PAYMENT_PROCESSING_DEADLINE = "payment-processing-deadline";

    private String scheduleId = null;

    @StartSaga
    @SagaEventHandler(associationProperty = "orderId")
    public void handle(OrderCreatedEvent orderCreatedEvent) {
        ReserveProductCommand reserveProductCommand = ReserveProductCommand.builder()
                .orderId(orderCreatedEvent.getOrderId())
                .productId(orderCreatedEvent.getProductId())
                .quantity(orderCreatedEvent.getQuantity())
                .userId(orderCreatedEvent.getUserId())
                .build();

        log.info(
                "OrderCreatedEvent handled for orderId: {}",
                orderCreatedEvent.getOrderId() + " and productId: " + orderCreatedEvent.getProductId());

        commandGateway.send(reserveProductCommand, (commandMessage, commandResultMessage) -> {
            if (commandResultMessage.isExceptional()) {
                // handle compensating transaction
                RejectOrderCommand rejectOrderCommand = RejectOrderCommand.builder()
                        .orderId(orderCreatedEvent.getOrderId())
                        .reason(commandResultMessage.exceptionResult().getMessage())
                        .build();
                commandGateway.send(rejectOrderCommand);
            }
        });
    }

    @SagaEventHandler(associationProperty = "orderId")
    public void handle(ProductReservedEvent productReservedEvent) {
        // handle success
        log.info(
                "ProductReservedEvent is called for productId: {}",
                productReservedEvent.getProductId() + " and orderId: " + productReservedEvent.getOrderId());

        FetchUserPaymentDetailsQuery fetchUserPaymentDetailsQuery = FetchUserPaymentDetailsQuery.builder()
                .userId(productReservedEvent.getUserId())
                .orderId(productReservedEvent.getOrderId())
                .build();

        User userPaymentDetails = null;
        try {
            userPaymentDetails = queryGateway
                    .query(fetchUserPaymentDetailsQuery, ResponseTypes.instanceOf(User.class))
                    .join();
        } catch (Exception e) {
            log.error(e.getMessage());
            // handle compensating transaction
            cancelProductReservation(productReservedEvent, e.getMessage());
            return;
        }

        if (userPaymentDetails == null) {
            // handle compensating transaction
            cancelProductReservation(productReservedEvent, "Could not fetch user payment details");
            return;
        }
        log.info("User payment details are successfully fetched for userId: {}", userPaymentDetails.toString());

        scheduleId = deadlineManager.schedule(
                Duration.of(10, ChronoUnit.SECONDS), PAYMENT_PROCESSING_DEADLINE, productReservedEvent);

        ProgressPaymentCommand progressPaymentCommand = ProgressPaymentCommand.builder()
                .orderId(productReservedEvent.getOrderId())
                .paymentDetails(userPaymentDetails.getPaymentDetails())
                .paymentId(UUID.randomUUID().toString())
                .build();
        String result = null;
        try {
            result = commandGateway.sendAndWait(progressPaymentCommand, 10, TimeUnit.SECONDS);
        } catch (Exception e) {
            // handle compensating transaction
            cancelProductReservation(productReservedEvent, e.getMessage());
            return;
        }
        if (result == null) {
            // handle compensating transaction
            cancelProductReservation(productReservedEvent, "Could not process payment");
        }
    }

    private void cancelProductReservation(ProductReservedEvent productReservedEvent, String reason) {
        handlePaymentDeadline();
        // handle compensating transaction
        CancelProductReservationCommand cancelProductReservationCommand = CancelProductReservationCommand.builder()
                .orderId(productReservedEvent.getOrderId())
                .productId(productReservedEvent.getProductId())
                .quantity(productReservedEvent.getQuantity())
                .userId(productReservedEvent.getUserId())
                .reason(reason)
                .build();
        commandGateway.send(cancelProductReservationCommand);
    }

    @SagaEventHandler(associationProperty = "orderId")
    public void handle(PaymentProcessedEvent paymentProcessedEvent) {
        handlePaymentDeadline();
        ApproveOrderCommand approveOrderCommand = new ApproveOrderCommand(paymentProcessedEvent.getOrderId());
        commandGateway.send(approveOrderCommand);
        log.info("PaymentProcessedEvent:  Payment is processed for orderId: {}", paymentProcessedEvent.getOrderId());
    }

    private void handlePaymentDeadline() {
        if (scheduleId != null) {
            deadlineManager.cancelSchedule(PAYMENT_PROCESSING_DEADLINE, scheduleId);
            scheduleId = null;
        }
    }

    @EndSaga
    @SagaEventHandler(associationProperty = "orderId")
    public void handle(OrderApprovedEvent orderApprovedEvent) {
        // handle success
        log.info("OrderApprovedEvent: Order is approved for orderId: {}", orderApprovedEvent.getOrderId());

        queryUpdateEmitter.emit(
                FindOrderQuery.class,
                query -> true,
                new OrderSumaryDto(orderApprovedEvent.getOrderId(), orderApprovedEvent.getOrderStatus()));
    }

    @SagaEventHandler(associationProperty = "orderId")
    public void handle(ProductReservationCancelledEvent productReservationCancelledEvent) {
        // create and send RejectOrderCommand
        RejectOrderCommand rejectOrderCommand = RejectOrderCommand.builder()
                .orderId(productReservationCancelledEvent.getOrderId())
                .reason(productReservationCancelledEvent.getReason())
                .build();
        commandGateway.send(rejectOrderCommand);
    }

    @EndSaga
    @SagaEventHandler(associationProperty = "orderId")
    public void handle(OrderRejectEvent orderRejectEvent) {
        // handle compensating transaction
        log.info("OrderRejectEvent: Order is rejected for orderId: {}", orderRejectEvent.getOrderId());

        queryUpdateEmitter.emit(
                FindOrderQuery.class,
                query -> true,
                new OrderSumaryDto(
                        orderRejectEvent.getOrderId(),
                        orderRejectEvent.getOrderStatus(),
                        orderRejectEvent.getReason()));
    }

    @DeadlineHandler(deadlineName = PAYMENT_PROCESSING_DEADLINE)
    public void handlePaymentDeadline(ProductReservedEvent productReservedEvent) {
        // handle compensating transaction
        log.info("Payment deadline is reached for orderId: {}", productReservedEvent.getOrderId());
        cancelProductReservation(productReservedEvent, "Payment is not processed within the deadline");
    }
}
