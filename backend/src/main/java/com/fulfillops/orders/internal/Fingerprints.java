package com.fulfillops.orders.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fulfillops.orders.OrderCommand;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/** Hash of a submission's meaningful content, to detect an idempotency key reused for a different request. */
final class Fingerprints {

    private Fingerprints() {
    }

    static String of(ObjectMapper json, OrderCommand cmd, boolean submit) {
        try {
            List<OrderCommand.Line> lines = cmd.lines().stream()
                    .sorted(Comparator.comparing(OrderCommand.Line::productId)).toList();
            OrderCommand canonical = new OrderCommand(cmd.customerName().trim(),
                    cmd.customerEmail() == null ? null : cmd.customerEmail().trim(), cmd.shippingAddress().trim(),
                    cmd.notes() == null ? null : cmd.notes().trim(), lines);
            byte[] bytes = json.writeValueAsBytes(new Object[]{canonical, submit});
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
