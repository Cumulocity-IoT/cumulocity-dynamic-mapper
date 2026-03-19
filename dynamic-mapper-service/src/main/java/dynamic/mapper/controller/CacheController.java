package dynamic.mapper.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.cumulocity.microservice.context.ContextService;
import com.cumulocity.microservice.context.credentials.UserCredentials;

import dynamic.mapper.core.CacheManager;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestController
@RequestMapping("/cache")
@Tag(name = "Cache Controller", description = "Endpoints for querying cache sizes")
public class CacheController {

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private ContextService<UserCredentials> contextService;

    @Operation(summary = "Get cache size", description = "Returns the current number of entries in the specified cache. Supported values for cacheId: INVENTORY_CACHE, INBOUND_ID_CACHE.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Cache size returned successfully",
                    content = @Content(mediaType = "application/json", schema = @Schema(type = "integer", example = "42"))),
            @ApiResponse(responseCode = "400", description = "Unknown cacheId")
    })
    @GetMapping
    public ResponseEntity<Integer> getCacheSize(
            @Parameter(description = "Identifier of the cache to query. Allowed values: INVENTORY_CACHE, INBOUND_ID_CACHE", required = true, example = "INVENTORY_CACHE")
            @RequestParam("cacheId") String cacheId) {
        String tenant = contextService.getContext().getTenant();
        log.info("{} - Get cache size for {}", tenant, cacheId);

        if ("INVENTORY_CACHE".equals(cacheId)) {
            int size = cacheManager.getSizeInventoryCache(tenant);
            return new ResponseEntity<>(size, HttpStatus.OK);
        } else if ("INBOUND_ID_CACHE".equals(cacheId)) {
            int size = cacheManager.getSizeInboundExternalIdCache(tenant);
            return new ResponseEntity<>(size, HttpStatus.OK);
        }

        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown cacheId: " + cacheId);
    }
}
