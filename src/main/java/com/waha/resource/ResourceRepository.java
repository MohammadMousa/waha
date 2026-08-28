package com.waha.resource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;

@Repository
public class ResourceRepository {

    private final JdbcTemplate jdbcTemplate;

    public ResourceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public record ResourceMeta(long id, String filename, String mimeType, long sizeBytes, String sha256) {}
    public record ResourceWithData(long id, String filename, String mimeType, long sizeBytes, byte[] data) {}

    // Deduplication check: same SHA-256 = same bytes, no need to store again.
    public Optional<Long> findIdBySha256(String sha256) {
        List<Long> results = jdbcTemplate.query(
            "SELECT id FROM resources WHERE sha256 = ?",
            (rs, i) -> rs.getLong("id"),
            sha256
        );
        return results.stream().findFirst();
    }

    // Stores metadata + blob in one transaction. Returns the new resource id.
    public long store(String filename, String mimeType, long sizeBytes, String sha256, byte[] data) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                "INSERT INTO resources (filename, mime_type, size_bytes, sha256) VALUES (?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS
            );
            ps.setString(1, filename);
            ps.setString(2, mimeType);
            ps.setLong(3, sizeBytes);
            ps.setString(4, sha256);
            return ps;
        }, keyHolder);

        long id = keyHolder.getKey().longValue();
        jdbcTemplate.update(
            "INSERT INTO resource_data (resource_id, data) VALUES (?, ?)",
            id, data
        );
        return id;
    }

    public Optional<ResourceWithData> findById(long id) {
        List<ResourceWithData> results = jdbcTemplate.query(
            "SELECT r.id, r.filename, r.mime_type, r.size_bytes, d.data " +
            "FROM resources r JOIN resource_data d ON d.resource_id = r.id WHERE r.id = ?",
            (rs, i) -> new ResourceWithData(
                rs.getLong("id"), rs.getString("filename"),
                rs.getString("mime_type"), rs.getLong("size_bytes"),
                rs.getBytes("data")
            ),
            id
        );
        return results.stream().findFirst();
    }

    public Optional<ResourceMeta> findMetaById(long id) {
        List<ResourceMeta> results = jdbcTemplate.query(
            "SELECT id, filename, mime_type, size_bytes, sha256 FROM resources WHERE id = ?",
            (rs, i) -> new ResourceMeta(
                rs.getLong("id"), rs.getString("filename"),
                rs.getString("mime_type"), rs.getLong("size_bytes"), rs.getString("sha256")
            ),
            id
        );
        return results.stream().findFirst();
    }

    // ── Named resource library ─────────────────────────────────────────────────

    public record DirectoryView(long id, String name) {}
    public record AssetView(long id, String name, String mimeType, long sizeBytes, String sha256) {}

    public Optional<String> getSystemProperty(String key) {
        List<String> r = jdbcTemplate.query(
            "SELECT value FROM system_properties WHERE `key` = ? LIMIT 1",
            (rs, i) -> rs.getString("value"), key);
        return r.stream().findFirst();
    }

    public Optional<Long> findStoreIdByName(String storeName) {
        List<Long> r = jdbcTemplate.query(
            "SELECT id FROM stores WHERE name = ? LIMIT 1",
            (rs, i) -> rs.getLong("id"), storeName);
        return r.stream().findFirst();
    }

    public Optional<Long> findDirectoryId(long storeId, String dirName) {
        List<Long> r = jdbcTemplate.query(
            "SELECT id FROM resource_directories WHERE store_id = ? AND name = ? LIMIT 1",
            (rs, i) -> rs.getLong("id"), storeId, dirName);
        return r.stream().findFirst();
    }

    public Optional<Long> findAssetResourceId(long storeId, long directoryId, String assetName) {
        List<Long> r = jdbcTemplate.query(
            "SELECT resource_id FROM resource_assets WHERE store_id = ? AND directory_id = ? AND name = ? LIMIT 1",
            (rs, i) -> rs.getLong("resource_id"), storeId, directoryId, assetName);
        return r.stream().findFirst();
    }

    public List<DirectoryView> listDirectories(long storeId) {
        return jdbcTemplate.query(
            "SELECT id, name FROM resource_directories WHERE store_id = ? ORDER BY name",
            (rs, i) -> new DirectoryView(rs.getLong("id"), rs.getString("name")),
            storeId);
    }

    public long createDirectory(long storeId, String name) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                "INSERT INTO resource_directories (store_id, name) VALUES (?, ?)",
                Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, storeId);
            ps.setString(2, name);
            return ps;
        }, kh);
        return kh.getKey().longValue();
    }

    public List<AssetView> listAssets(long storeId, long directoryId) {
        return jdbcTemplate.query(
            "SELECT a.id, a.name, r.mime_type, r.size_bytes, r.sha256 " +
            "FROM resource_assets a JOIN resources r ON r.id = a.resource_id " +
            "WHERE a.store_id = ? AND a.directory_id = ? ORDER BY a.name",
            (rs, i) -> new AssetView(rs.getLong("id"), rs.getString("name"),
                rs.getString("mime_type"), rs.getLong("size_bytes"), rs.getString("sha256")),
            storeId, directoryId);
    }

    public void upsertAsset(long storeId, long directoryId, String name, long resourceId) {
        jdbcTemplate.update(
            "INSERT INTO resource_assets (store_id, directory_id, name, resource_id) VALUES (?, ?, ?, ?) " +
            "ON DUPLICATE KEY UPDATE resource_id = VALUES(resource_id)",
            storeId, directoryId, name, resourceId);
    }

    public boolean deleteAsset(long storeId, long directoryId, String name) {
        int rows = jdbcTemplate.update(
            "DELETE FROM resource_assets WHERE store_id = ? AND directory_id = ? AND name = ?",
            storeId, directoryId, name);
        return rows > 0;
    }

    // ── Gallery ────────────────────────────────────────────────────────────────

    // Gallery images for a product, in sort_order. The avatar (product.image_resource_id)
    // is NOT included here - the caller decides whether to prepend it.
    public record GalleryItem(long resourceId, String mimeType, int sortOrder) {}

    public List<GalleryItem> findGalleryByProduct(long productId) {
        return jdbcTemplate.query(
            "SELECT pi.resource_id, r.mime_type, pi.sort_order " +
            "FROM product_images pi JOIN resources r ON r.id = pi.resource_id " +
            "WHERE pi.product_id = ? ORDER BY pi.sort_order, pi.id",
            (rs, i) -> new GalleryItem(
                rs.getLong("resource_id"), rs.getString("mime_type"), rs.getInt("sort_order")
            ),
            productId
        );
    }
}
