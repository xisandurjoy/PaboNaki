package com.pabonaki.ai;

import com.pabonaki.model.Product;
import org.springframework.data.mongodb.core.mapping.event.AbstractMongoEventListener;
import org.springframework.data.mongodb.core.mapping.event.AfterSaveEvent;
import org.springframework.stereotype.Component;

/** Notices every Product save (new product, edit, deactivate) without touching the existing controllers. */
@Component
public class ProductIndexListener extends AbstractMongoEventListener<Product> {
    private final ProductIndexer indexer;

    public ProductIndexListener(ProductIndexer indexer) { this.indexer = indexer; }

    @Override
    public void onAfterSave(AfterSaveEvent<Product> event) {
        Product p = event.getSource();
        if (p != null) indexer.enqueue(p.getId());
    }
}
