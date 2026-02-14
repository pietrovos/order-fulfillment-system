package com.fulfillops.catalog;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fulfillops.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class CatalogIntegrationTest extends IntegrationTest {

    @Test
    void creatingAProductAlsoCreatesAnEmptyStockRow() throws Exception {
        long id = createProduct("Pallet jack", "349.00");
        mvc.perform(get("/api/inventory/stock/{id}", id).header("Authorization", bearer("sales")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.onHand").value(0))
                .andExpect(jsonPath("$.reserved").value(0))
                .andExpect(jsonPath("$.available").value(0));
    }

    @Test
    void onlySupervisorsCanCreateProducts() throws Exception {
        for (String user : new String[]{"sales", "warehouse"}) {
            mvc.perform(post("/api/products").header("Authorization", bearer(user))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"sku\":\"NOPE-1\",\"name\":\"x\",\"unitPrice\":1}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }
    }

    @Test
    void duplicateSkuIsAConflict() throws Exception {
        String body = "{\"sku\":\"DUP-SKU-1\",\"name\":\"x\",\"unitPrice\":1}";
        mvc.perform(post("/api/products").header("Authorization", bearer("supervisor"))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated());
        mvc.perform(post("/api/products").header("Authorization", bearer("supervisor"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_SKU"));
    }

    @Test
    void validatesInput() throws Exception {
        mvc.perform(post("/api/products").header("Authorization", bearer("supervisor"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"lower case\",\"name\":\"\",\"unitPrice\":-1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.sku").exists())
                .andExpect(jsonPath("$.fieldErrors.name").exists())
                .andExpect(jsonPath("$.fieldErrors.unitPrice").exists());
    }

    @Test
    void searchesAndUpdates() throws Exception {
        long id = createProduct("Stretch wrap film 500mm", "24.50");
        mvc.perform(get("/api/products").param("q", "stretch wrap").header("Authorization", bearer("sales")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == %d)]".formatted(id)).exists());
        String sku = read(mvc.perform(get("/api/products/{id}", id).header("Authorization", bearer("sales")))
                .andReturn().getResponse().getContentAsString()).get("sku").asText();
        mvc.perform(put("/api/products/{id}", id).header("Authorization", bearer("supervisor"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"%s\",\"name\":\"Stretch wrap 500mm\",\"unitPrice\":25.00,\"active\":false}".formatted(sku)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.unitPrice").value(25.0));
    }
}
