package com.fulfillops.orders;

import com.fulfillops.shared.web.DomainException;
import org.springframework.http.HttpStatus;

public class InvalidTransitionException extends DomainException {

    public InvalidTransitionException(String orderNumber, OrderStatus from, OrderStatus to) {
        super(HttpStatus.CONFLICT, "INVALID_TRANSITION",
                "Order " + orderNumber + " cannot move from " + from + " to " + to);
    }
}
