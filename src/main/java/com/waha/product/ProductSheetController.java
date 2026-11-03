package com.waha.product;

import com.waha.auth.Permission;
import com.waha.auth.SessionService;
import com.waha.auth.UserSession;
import com.waha.common.ErrorResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

// Bulk export and import of products (CSV or XLSX). Needs the same permission as editing a product.
@RestController
@RequestMapping("/api/admin/products")
public class ProductSheetController {

    private final SessionService sessionService;
    private final ProductSheetService sheets;

    public ProductSheetController(SessionService sessionService, ProductSheetService sheets) {
        this.sessionService = sessionService;
        this.sheets = sheets;
    }

    private static ResponseEntity<?> orgRequired() {
        return ResponseEntity.status(409).body(Map.of("error", "ORGANIZATION_REQUIRED", "message", "Pick an organization first"));
    }

    @GetMapping("/export")
    public ResponseEntity<?> export(@RequestHeader(value = "Authorization", required = false) String auth,
                                    @RequestParam(defaultValue = "csv") String format,
                                    @RequestParam(required = false) Long categoryId,
                                    @RequestParam(required = false) Boolean active) {
        UserSession session = sessionService.requireSession(auth);
        sessionService.requirePermissionForOrg(session, Permission.EDIT_PRODUCTS, session.organizationId());
        if (session.organizationId() <= 0) return orgRequired();
        String f = format.toLowerCase();
        if (!f.equals("csv") && !f.equals("xlsx")) {
            return ResponseEntity.badRequest().body(new ErrorResponse("format must be csv or xlsx"));
        }
        byte[] file = sheets.export(session.organizationId(), f, categoryId, active);
        MediaType type = f.equals("xlsx")
            ? MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
            : MediaType.parseMediaType("text/csv;charset=UTF-8");
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"products." + f + "\"")
            .contentType(type).contentLength(file.length).body(file);
    }

    @PostMapping("/import")
    public ResponseEntity<?> importFile(@RequestHeader(value = "Authorization", required = false) String auth,
                                        @RequestParam(defaultValue = "validate") String mode,
                                        @RequestParam("file") MultipartFile file) {
        UserSession session = sessionService.requireSession(auth);
        sessionService.requirePermissionForOrg(session, Permission.EDIT_PRODUCTS, session.organizationId());
        if (session.organizationId() <= 0) return orgRequired();
        if (!mode.equals("validate") && !mode.equals("apply")) {
            return ResponseEntity.badRequest().body(new ErrorResponse("mode must be validate or apply"));
        }
        try {
            return ResponseEntity.ok(sheets.importFile(session.organizationId(), file.getBytes(),
                file.getOriginalFilename(), mode.equals("apply")));
        } catch (ProductSheetService.SheetException e) {
            return ResponseEntity.status(e.status).body(new ErrorResponse(e.getMessage()));
        } catch (java.io.IOException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse("The file could not be read"));
        }
    }
}
