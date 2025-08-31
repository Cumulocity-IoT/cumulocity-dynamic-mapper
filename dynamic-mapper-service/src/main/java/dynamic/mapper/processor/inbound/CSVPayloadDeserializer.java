package dynamic.mapper.processor.inbound;


import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import dynamic.mapper.connector.core.callback.ConnectorMessage;
import dynamic.mapper.model.Mapping;

public class CSVPayloadDeserializer implements PayloadDeserializer<List<Map<String, String>>> {
    
    private static final String DEFAULT_DELIMITER = ",";
    
    @Override
    public List<Map<String, String>> deserializePayload(Mapping mapping, ConnectorMessage message) throws IOException {
        if (message.getPayload() == null || message.getPayload().length == 0) {
            throw new IOException("CSV payload is null or empty");
        }
        
        try {
            String csvData = new String(message.getPayload(), StandardCharsets.UTF_8);
            return parseCsv(csvData, getDelimiter(mapping));
        } catch (Exception e) {
            throw new IOException("Failed to deserialize CSV payload: " + e.getMessage(), e);
        }
    }
    
    private String getDelimiter(Mapping mapping) {
        // Get delimiter from mapping configuration if available
        // Otherwise use default comma
        return DEFAULT_DELIMITER;
    }
    
    private List<Map<String, String>> parseCsv(String csvData, String delimiter) {
        List<Map<String, String>> result = new ArrayList<>();
        String[] lines = csvData.split("\n");
        
        if (lines.length == 0) {
            return result;
        }
        
        // First line contains headers
        String[] headers = lines[0].split(delimiter);
        
        // Process data lines
        for (int i = 1; i < lines.length; i++) {
            String[] values = lines[i].split(delimiter);
            Map<String, String> row = new HashMap<>();
            
            for (int j = 0; j < Math.min(headers.length, values.length); j++) {
                row.put(headers[j].trim(), values[j].trim());
            }
            
            result.add(row);
        }
        
        return result;
    }
}
