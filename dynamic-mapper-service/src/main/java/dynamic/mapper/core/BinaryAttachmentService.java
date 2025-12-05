/*
 * Copyright (c) 2022-2025 Cumulocity GmbH.
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 *  @authors Christof Strack, Stefan Witschel
 *
 */

package dynamic.mapper.core;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.concurrent.Semaphore;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import com.cumulocity.microservice.api.CumulocityClientProperties;
import com.cumulocity.microservice.context.ContextService;
import com.cumulocity.microservice.context.credentials.MicroserviceCredentials;

import dynamic.mapper.model.BinaryInfo;
import dynamic.mapper.model.EventBinary;
import dynamic.mapper.processor.ProcessingException;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class BinaryAttachmentService {

    @Autowired
    private ContextService<MicroserviceCredentials> contextService;

    @Autowired
    private CumulocityClientProperties clientProperties;

   
    public int uploadEventAttachment(final BinaryInfo binaryInfo, final String eventId, boolean overwrites, Semaphore c8ySemaphore)
            throws ProcessingException {
        try {
            HttpHeaders headers = createHeaders();
            String tenant = contextService.getContext().toCumulocityCredentials().getTenantId();
            String serverUrl = clientProperties.getBaseURL() + "/event/events/" + eventId + "/binaries";

            byte[] attDataBytes = processBase64Data(binaryInfo);
            setDefaultMediaType(binaryInfo);
            setDefaultFileName(binaryInfo);

            log.info("{} - Uploading attachment with name {} and type {} to event {}", tenant,
                    binaryInfo.getName(), binaryInfo.getType(), eventId);

            RestTemplate restTemplate = new RestTemplate();
            ResponseEntity<EventBinary> response;

            if (overwrites) {
                response = uploadWithPut(restTemplate, serverUrl, headers, binaryInfo, attDataBytes);
            } else {
                response = uploadWithPost(restTemplate, serverUrl, headers, binaryInfo, attDataBytes, tenant, eventId, c8ySemaphore);
            }

            if (response.getStatusCode().value() >= 300) {
                throw new ProcessingException("Failed to create binary: " + response.toString(),
                        response.getStatusCode().value());
            }
            return response.getStatusCode().value();
        } catch (Exception e) {
            log.error("{} - Failed to upload attachment to event {}: ", 
                    contextService.getContext().getTenant(), eventId, e);
            throw new ProcessingException("Failed to upload attachment to event: " + e.getMessage(), e);
        }
    }

    private HttpHeaders createHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization",
                contextService.getContext().toCumulocityCredentials().getAuthenticationString());
        headers.setAccept(Arrays.asList(MediaType.APPLICATION_JSON));
        return headers;
    }

    private byte[] processBase64Data(BinaryInfo binaryInfo) {
        if (binaryInfo.getData().isEmpty()) {
            return new byte[0];
        }

        if (binaryInfo.getData().startsWith("data:") && 
                (binaryInfo.getType() == null || binaryInfo.getType().isEmpty())) {
            int pos = binaryInfo.getData().indexOf(";");
            String type = binaryInfo.getData().substring(5, pos - 1);
            binaryInfo.setType(type);
            return Base64.getDecoder()
                    .decode(binaryInfo.getData().substring(pos + 8).getBytes(StandardCharsets.UTF_8));
        } else {
            return Base64.getDecoder().decode(binaryInfo.getData().getBytes(StandardCharsets.UTF_8));
        }
    }

    private void setDefaultMediaType(BinaryInfo binaryInfo) {
        if (binaryInfo.getType() == null || binaryInfo.getType().isEmpty()) {
            binaryInfo.setType(MediaType.APPLICATION_OCTET_STREAM_VALUE);
        }
    }

    private void setDefaultFileName(BinaryInfo binaryInfo) {
        if (binaryInfo.getName() == null || binaryInfo.getName().isEmpty()) {
            if (binaryInfo.getType() != null && !binaryInfo.getType().isEmpty()) {
                binaryInfo.setName(getFileNameByType(binaryInfo.getType()));
            } else {
                binaryInfo.setName("file");
            }
        }
    }

    private String getFileNameByType(String type) {
        if (type.contains("image/")) return "file.png";
        if (type.contains("text/")) return "file.txt";
        if (type.contains("application/pdf")) return "file.pdf";
        if (type.contains("application/json")) return "file.json";
        if (type.contains("application/xml")) return "file.xml";
        return "file.bin";
    }

    private ResponseEntity<EventBinary> uploadWithPut(RestTemplate restTemplate, String serverUrl,
            HttpHeaders headers, BinaryInfo binaryInfo, byte[] attDataBytes) {
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        headers.setContentDisposition(
                ContentDisposition.builder("attachment").filename(binaryInfo.getName()).build());
        HttpEntity<byte[]> requestEntity = new HttpEntity<>(attDataBytes, headers);
        return restTemplate.exchange(serverUrl, HttpMethod.PUT, requestEntity, EventBinary.class);
    }

    private ResponseEntity<EventBinary> uploadWithPost(RestTemplate restTemplate, String serverUrl,
            HttpHeaders headers, BinaryInfo binaryInfo, byte[] attDataBytes, String tenant, String eventId, Semaphore c8ySemaphore) {
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        MultipartBodyBuilder multipartBodyBuilder = new MultipartBodyBuilder();
        multipartBodyBuilder.part("object", binaryInfo, MediaType.APPLICATION_JSON);
        multipartBodyBuilder.part("file", attDataBytes, MediaType.valueOf(binaryInfo.getType()))
                .filename(binaryInfo.getName());
        MultiValueMap<String, HttpEntity<?>> body = multipartBodyBuilder.build();
        HttpEntity<MultiValueMap<String, HttpEntity<?>>> requestEntity = new HttpEntity<>(body, headers);

        try {
            c8ySemaphore.acquire();
            return restTemplate.postForEntity(serverUrl, requestEntity, EventBinary.class);
        } catch (InterruptedException e) {
            log.error("{} - Failed to acquire semaphore for uploading attachment to event {}: ", 
                    tenant, eventId, e);
            throw new RuntimeException(e);
        } finally {
            c8ySemaphore.release();
        }
    }
}