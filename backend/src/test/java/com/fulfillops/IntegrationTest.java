package com.fulfillops;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** Full application context against Testcontainers Postgres, driven over HTTP with real JWTs. */
@SpringBootTest(properties = "fulfillops.jobs.poll-enabled=false")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
public abstract class IntegrationTest {

    public static final String PASSWORD = "fulfill123";
    private static final Map<String, String> TOKENS = new ConcurrentHashMap<>();

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected ObjectMapper json;

    protected String bearer(String username) {
        return "Bearer " + TOKENS.computeIfAbsent(username, this::login);
    }

    private String login(String username) {
        try {
            String body = mvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of("username", username, "password", PASSWORD))))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            return json.readTree(body).get("token").asText();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static final java.util.concurrent.atomic.AtomicInteger SEQ = new java.util.concurrent.atomic.AtomicInteger();

    /** Creates a product with a unique SKU (tests never share rows, so no cleanup is needed). */
    protected long createProduct(String name, String price) throws Exception {
        String sku = "T" + System.nanoTime() % 1_000_000_000L + "-" + SEQ.incrementAndGet();
        String body = mvc.perform(post("/api/products").header("Authorization", bearer("supervisor"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("sku", sku, "name", name, "unitPrice", price))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return read(body).get("id").asLong();
    }

    protected long createProductWithStock(int onHand) throws Exception {
        long id = createProduct("Stocked item", "9.99");
        if (onHand > 0) {
            mvc.perform(post("/api/inventory/stock/{id}/receipts", id).header("Authorization", bearer("warehouse"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"quantity\":" + onHand + "}"))
                    .andExpect(status().isOk());
        }
        return id;
    }

    protected static String orderJson(long productId, int qty) {
        return orderJson("Acme Hardware", productId, qty);
    }

    protected static String orderJson(String customer, long productId, int qty) {
        return """
                {"customerName":"%s","shippingAddress":"12 Dock Rd, Halifax NS","lines":[{"productId":%d,"quantity":%d}]}
                """.formatted(customer, productId, qty);
    }

    protected org.springframework.test.web.servlet.ResultActions postOrder(String user, String key, boolean submit,
                                                                          String body) throws Exception {
        var req = post("/api/orders").param("submit", String.valueOf(submit))
                .header("Authorization", bearer(user))
                .contentType(MediaType.APPLICATION_JSON).content(body);
        if (key != null) {
            req = req.header("Idempotency-Key", key);
        }
        return mvc.perform(req);
    }

    protected JsonNode submitOrder(long productId, int qty) throws Exception {
        return read(postOrder("sales", java.util.UUID.randomUUID().toString(), true, orderJson(productId, qty))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    protected JsonNode read(String body) {
        try {
            return json.readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
