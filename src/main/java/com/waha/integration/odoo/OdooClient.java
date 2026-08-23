package com.waha.integration.odoo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

// XML-RPC wrapper for Odoo's /xmlrpc/2/common and /xmlrpc/2/object endpoints.
// JSON-RPC /web/dataset/call_kw requires session cookies (not supported for SaaS API keys).
// XML-RPC supports API key auth directly: authenticate(db, login, apiKey) → uid.
@Component
public class OdooClient {

    private final RestTemplate restTemplate;

    public OdooClient(RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
    }

    // Derives the Odoo DB name from the base URL subdomain.
    // https://wa7a.odoo.com → "wa7a", https://myco.odoo.com → "myco"
    private String dbFromUrl(String baseUrl) {
        try {
            String host = java.net.URI.create(baseUrl).getHost();
            return host.split("\\.")[0];
        } catch (Exception e) {
            throw new OdooException("Cannot derive DB name from URL: " + baseUrl);
        }
    }

    // Authenticates and returns uid. Throws OdooException on failure.
    private int authenticate(String baseUrl, String username, String apiKey) {
        String db  = dbFromUrl(baseUrl);
        String url = base(baseUrl) + "/xmlrpc/2/common";
        String xml = "<?xml version='1.0'?>"
            + "<methodCall><methodName>authenticate</methodName><params>"
            + param(str(db)) + param(str(username)) + param(str(apiKey)) + param("<struct/>")
            + "</params></methodCall>";

        String resp = post(url, xml);
        // Response is <value><int>UID</int></value> or <value><boolean>0</boolean></value>
        String val = extractFirstValue(resp);
        if (val == null || val.equals("0") || val.isBlank()) {
            throw new OdooException("Authentication failed — check username and API key");
        }
        try {
            return Integer.parseInt(val.trim());
        } catch (NumberFormatException e) {
            throw new OdooException("Unexpected authenticate response: " + val);
        }
    }

    // Calls execute_kw and returns the raw XML response string.
    public String executeKwRaw(String baseUrl, String apiKey, String username,
                                String model, String method,
                                String argsXml, String kwargsXml) {
        int uid = authenticate(baseUrl, username, apiKey);
        String db  = dbFromUrl(baseUrl);
        String url = base(baseUrl) + "/xmlrpc/2/object";
        String xml = "<?xml version='1.0'?>"
            + "<methodCall><methodName>execute_kw</methodName><params>"
            + param(str(db))
            + param("<int>" + uid + "</int>")
            + param(str(apiKey))
            + param(str(model))
            + param(str(method))
            + param(argsXml)
            + param(kwargsXml)
            + "</params></methodCall>";
        return post(url, xml);
    }

    // search_read: returns list of records as JsonNode array.
    public List<JsonNode> searchRead(String baseUrl, String apiKey, String username,
                                      String model, List<?> domain,
                                      List<String> fields, int limit, int offset) {
        String domainXml = domainToXml(domain);
        String argsXml   = "<array><data><value>" + domainXml + "</value></data></array>";
        String kwargsXml = "<struct>"
            + member("fields",  fieldsToXml(fields))
            + member("limit",   "<int>" + limit  + "</int>")
            + member("offset",  "<int>" + offset + "</int>")
            + "</struct>";

        String resp = executeKwRaw(baseUrl, apiKey, username, model, "search_read",
                                   argsXml, kwargsXml);
        return parseRecordList(resp);
    }

    // search: returns list of matching ids.
    public List<Long> search(String baseUrl, String apiKey, String username,
                             String model, List<?> domain) {
        String domainXml = domainToXml(domain);
        String argsXml   = "<array><data><value>" + domainXml + "</value></data></array>";
        String resp = executeKwRaw(baseUrl, apiKey, username, model, "search",
                                   argsXml, "<struct/>");
        return parseIdList(resp);
    }

    // create: creates a record and returns its new id.
    public long create(String baseUrl, String apiKey, String username,
                       String model, Map<String, Object> values) {
        String argsXml = "<array><data><value>" + mapToXml(values) + "</value></data></array>";
        String resp = executeKwRaw(baseUrl, apiKey, username, model, "create",
                                   argsXml, "<struct/>");
        String val = extractFirstValue(resp);
        try {
            return Long.parseLong(val.trim());
        } catch (Exception e) {
            throw new OdooException("create returned unexpected value: " + val);
        }
    }

    // ── XML helpers ───────────────────────────────────────────────────────────

    private String post(String url, String xml) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_XML);
        try {
            String result = restTemplate.postForObject(url,
                new HttpEntity<>(xml, headers), String.class);
            if (result != null && result.contains("<fault>")) {
                String msg = extractTagContent(result, "string");
                throw new OdooException("Odoo fault: " + (msg != null ? msg : result));
            }
            return result != null ? result : "";
        } catch (OdooException e) {
            throw e;
        } catch (Exception e) {
            throw new OdooException("HTTP error calling Odoo: " + e.getMessage(), e);
        }
    }

    private String base(String url) {
        return url.replaceAll("/+$", "");
    }

    private String param(String inner) {
        return "<param><value>" + inner + "</value></param>";
    }

    private String member(String name, String inner) {
        return "<member><name>" + name + "</name><value>" + inner + "</value></member>";
    }

    private String str(String s) {
        return "<string>" + xmlEscape(s) + "</string>";
    }

    private String xmlEscape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }

    // Converts a domain list (e.g. [["write_date",">","2024-01-01"]]) to XML-RPC array.
    @SuppressWarnings("unchecked")
    private String domainToXml(List<?> domain) {
        if (domain == null || domain.isEmpty()) {
            return "<array><data></data></array>";
        }
        StringBuilder sb = new StringBuilder("<array><data>");
        for (Object item : domain) {
            if (item instanceof List<?> triple) {
                sb.append("<value><array><data>");
                for (Object part : triple) {
                    sb.append("<value>").append(scalarToXml(part)).append("</value>");
                }
                sb.append("</data></array></value>");
            }
        }
        sb.append("</data></array>");
        return sb.toString();
    }

    private String fieldsToXml(List<String> fields) {
        StringBuilder sb = new StringBuilder("<array><data>");
        for (String f : fields) sb.append("<value>").append(str(f)).append("</value>");
        sb.append("</data></array>");
        return sb.toString();
    }

    private String mapToXml(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder("<struct>");
        for (Map.Entry<String, Object> e : map.entrySet()) {
            sb.append(member(e.getKey(), scalarToXml(e.getValue())));
        }
        sb.append("</struct>");
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private String scalarToXml(Object v) {
        if (v == null)              return "<boolean>0</boolean>";
        if (v instanceof Boolean b) return "<boolean>" + (b ? 1 : 0) + "</boolean>";
        if (v instanceof Integer i) return "<int>" + i + "</int>";
        if (v instanceof Long l)    return "<int>" + l + "</int>";
        if (v instanceof Double d)  return "<double>" + d + "</double>";
        if (v instanceof Float f)   return "<double>" + f + "</double>";
        if (v instanceof List<?> list) {
            StringBuilder sb = new StringBuilder("<array><data>");
            for (Object item : list) sb.append("<value>").append(scalarToXml(item)).append("</value>");
            sb.append("</data></array>");
            return sb.toString();
        }
        if (v instanceof Map<?,?> map) {
            StringBuilder sb = new StringBuilder("<struct>");
            for (Map.Entry<?,?> e : map.entrySet()) {
                sb.append(member(e.getKey().toString(), scalarToXml(e.getValue())));
            }
            sb.append("</struct>");
            return sb.toString();
        }
        return str(v.toString());
    }

    // Calls a method on a list of record ids (e.g. action_confirm on a sale.order).
    public void callMethod(String baseUrl, String apiKey, String username,
                           String model, String method, List<Long> ids) {
        StringBuilder idsXml = new StringBuilder("<array><data>");
        for (Long id : ids) idsXml.append("<value><int>").append(id).append("</int></value>");
        idsXml.append("</data></array>");
        String argsXml = "<array><data><value>" + idsXml + "</value></data></array>";
        executeKwRaw(baseUrl, apiKey, username, model, method, argsXml, "<struct/>");
    }

    // Looks up the Odoo partner_id for the given login (for use as sale.order partner).
    public long getPartnerIdForLogin(String baseUrl, String apiKey, String username) {
        List<JsonNode> users = searchRead(baseUrl, apiKey, username, "res.users",
            List.of(List.of("login", "=", username)),
            List.of("partner_id"), 1, 0);
        if (users.isEmpty()) throw new OdooException("Could not find user in Odoo: " + username);
        long partnerId = users.get(0).path("partner_id").asLong(0);
        if (partnerId == 0) throw new OdooException("User has no partner_id in Odoo: " + username);
        return partnerId;
    }

    // Parses XML-RPC response into list of record maps as JsonNode objects.
    private List<JsonNode> parseRecordList(String xml) {
        ObjectMapper mapper = new ObjectMapper();
        java.util.List<JsonNode> result = new java.util.ArrayList<>();
        // Extract each <struct> block (one per record)
        int pos = 0;
        while (true) {
            int start = xml.indexOf("<struct>", pos);
            if (start < 0) break;
            int end = xml.indexOf("</struct>", start);
            if (end < 0) break;
            String block = xml.substring(start, end + 9);
            try {
                result.add(parseStruct(block, mapper));
            } catch (Exception ignored) {}
            pos = end + 9;
        }
        return result;
    }

    private JsonNode parseStruct(String block, ObjectMapper mapper) {
        com.fasterxml.jackson.databind.node.ObjectNode node = mapper.createObjectNode();
        int pos = 0;
        while (true) {
            int mi = block.indexOf("<member>", pos);
            if (mi < 0) break;
            int me = block.indexOf("</member>", mi);
            if (me < 0) break;
            String member = block.substring(mi + 8, me);
            String name  = extractTagContent(member, "name");
            String intV  = extractTagContent(member, "int");
            String strV  = extractTagContent(member, "string");
            String boolV = extractTagContent(member, "boolean");
            String dblV  = extractTagContent(member, "double");
            if (name != null) {
                if (intV  != null) node.put(name, Long.parseLong(intV.trim()));
                else if (strV  != null) node.put(name, strV);
                else if (boolV != null) node.put(name, !boolV.trim().equals("0"));
                else if (dblV  != null) node.put(name, Double.parseDouble(dblV.trim()));
                else node.putNull(name);
            }
            pos = me + 9;
        }
        return node;
    }

    private List<Long> parseIdList(String xml) {
        java.util.List<Long> ids = new java.util.ArrayList<>();
        int pos = 0;
        while (true) {
            int si = xml.indexOf("<int>", pos);
            if (si < 0) break;
            int ei = xml.indexOf("</int>", si);
            if (ei < 0) break;
            try { ids.add(Long.parseLong(xml.substring(si + 5, ei).trim())); }
            catch (NumberFormatException ignored) {}
            pos = ei + 6;
        }
        return ids;
    }

    // Extracts the first value from an XML-RPC response (e.g. <int>42</int>).
    private String extractFirstValue(String xml) {
        for (String tag : List.of("int", "string", "boolean", "double")) {
            String v = extractTagContent(xml, tag);
            if (v != null) return v;
        }
        return null;
    }

    private String extractTagContent(String xml, String tag) {
        String open = "<" + tag + ">";
        String close = "</" + tag + ">";
        int s = xml.indexOf(open);
        if (s < 0) return null;
        int e = xml.indexOf(close, s);
        if (e < 0) return null;
        return xml.substring(s + open.length(), e);
    }
}
