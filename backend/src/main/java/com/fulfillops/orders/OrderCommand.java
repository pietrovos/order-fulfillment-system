package com.fulfillops.orders;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public record OrderCommand(
        @NotBlank @Size(max = 200) String customerName,
        @Email @Size(max = 200) String customerEmail,
        @NotBlank @Size(max = 1000) String shippingAddress,
        @Size(max = 2000) String notes,
        @NotEmpty @Size(max = 100) List<@Valid @NotNull Line> lines) {

    public record Line(@NotNull Long productId, @NotNull @Min(1) @Max(100_000) Integer quantity) {
    }
}
