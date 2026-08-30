-- Product tags: free-form labels for filtering and search.
CREATE TABLE product_tags (
    product_id BIGINT NOT NULL,
    tag        VARCHAR(100) NOT NULL,
    PRIMARY KEY (product_id, tag),
    CONSTRAINT fk_product_tags_product FOREIGN KEY (product_id) REFERENCES products(id) ON DELETE CASCADE
);
