package com.pabonaki.ai.reco;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * One browsing signal (view, search, wishlist add, cart add). Cart and wishlist live in the browser in PaboNaki, so the storefront
 * reports those actions here. Rows expire automatically after 180 days. userId comes only from the verified JWT, never from the request body.
 */
@Document("user_activity")
@CompoundIndexes({
        @CompoundIndex(name = "user_time", def = "{'userId': 1, 'createdAt': -1}"),
        @CompoundIndex(name = "session_time", def = "{'sessionId': 1, 'createdAt': -1}"),
        @CompoundIndex(name = "product_type_time", def = "{'productId': 1, 'type': 1, 'createdAt': -1}")
})
public class UserActivity {
    @Id public String id;
    public String userId;       // null for guests
    public String sessionId;    // random browser id
    public String type;         // VIEW | SEARCH | WISHLIST_ADD | CART_ADD
    public String productId;
    public String category;
    public String query;
    @Indexed(expireAfterSeconds = 15_552_000) public Instant createdAt = Instant.now();
}
