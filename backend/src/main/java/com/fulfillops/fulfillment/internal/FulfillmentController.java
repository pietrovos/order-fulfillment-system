package com.fulfillops.fulfillment.internal;

import com.fulfillops.orders.OrderSummary;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
class FulfillmentController {

    private final FulfillmentService fulfillment;

    FulfillmentController(FulfillmentService fulfillment) {
        this.fulfillment = fulfillment;
    }

    @GetMapping("/fulfillment/pick-queue")
    List<OrderSummary> pickQueue() {
        return fulfillment.pickQueue();
    }

    @GetMapping("/fulfillment/pick-lists")
    List<PickListView> openPickLists() {
        return fulfillment.openPickLists();
    }

    @GetMapping("/fulfillment/pack-queue")
    List<PickListView> packQueue() {
        return fulfillment.packQueue();
    }

    @PostMapping("/fulfillment/orders/{orderId}/pick-list")
    @ResponseStatus(HttpStatus.CREATED)
    PickListView startPicking(@PathVariable long orderId) {
        return fulfillment.startPicking(orderId);
    }

    @GetMapping("/fulfillment/pick-lists/{id}")
    PickListView pickList(@PathVariable long id) {
        return fulfillment.pickList(id);
    }

    @PostMapping("/fulfillment/pick-lists/{id}/lines/{lineNo}")
    PickListView confirm(@PathVariable long id, @PathVariable int lineNo, @RequestBody Confirm body) {
        return fulfillment.confirmLine(id, lineNo, body.picked());
    }

    @PostMapping("/fulfillment/pick-lists/{id}/complete")
    PickListView complete(@PathVariable long id) {
        return fulfillment.completePick(id);
    }

    @PostMapping("/fulfillment/pick-lists/{id}/pack")
    @ResponseStatus(HttpStatus.CREATED)
    ShipmentView pack(@PathVariable long id, @Valid @RequestBody Pack body) {
        return fulfillment.pack(id, body.parcels(), body.weightKg());
    }

    @GetMapping("/fulfillment/exceptions")
    List<FulfillmentService.ExceptionView> exceptions() {
        return fulfillment.exceptionQueue();
    }

    @GetMapping("/fulfillment/orders/{orderId}/shipment")
    ShipmentView shipmentForOrder(@PathVariable long orderId) {
        return fulfillment.shipmentForOrder(orderId);
    }

    @GetMapping("/shipments")
    List<ShipmentView> shipments(@RequestParam(required = false) String status,
                                 @RequestParam(required = false) String q) {
        return fulfillment.shipments(status, q);
    }

    @GetMapping("/shipments/{id}")
    ShipmentView shipment(@PathVariable UUID id) {
        return fulfillment.shipment(id);
    }

    record Confirm(boolean picked) {
    }

    record Pack(@NotNull @Min(1) @Max(99) Integer parcels,
                @NotNull @DecimalMin("0.01") @DecimalMax("5000") BigDecimal weightKg) {
    }
}
