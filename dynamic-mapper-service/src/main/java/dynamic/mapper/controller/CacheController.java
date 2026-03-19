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

import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestController
@RequestMapping("/cache")
public class CacheController {

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private ContextService<UserCredentials> contextService;

    @GetMapping
    public ResponseEntity<Integer> getCacheSize(@RequestParam("cacheId") String cacheId) {
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
