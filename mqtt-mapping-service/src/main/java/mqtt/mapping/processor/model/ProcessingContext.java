package mqtt.mapping.processor.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import lombok.Data;
import lombok.NoArgsConstructor;
import mqtt.mapping.model.Mapping;
import mqtt.mapping.model.MappingSubstitution.SubstituteValue;

@Data
@NoArgsConstructor
/*
 * The class <code>ProcessingContext</code> collects all relevant information:
 * <code>mapping</code>, <code>topic</code>, <code>payload</code>,
 * <code>requests</code>, <code>error</code>, <code>processingType</code>,
 * <code>cardinality</code>, <code>needsRepair</code>
 * when a <code>mapping</code> is applied to an incoming <code>payload</code>
 */
public class ProcessingContext<O> {
    private Mapping mapping;

    private String topic;

    private O payload;

    private byte[] payloadRaw;

    private ArrayList<C8YRequest> requests = new ArrayList<C8YRequest>();

    private Exception error;

    private ProcessingType processingType = ProcessingType.UNDEFINED;

    private Map<String, Integer> cardinality = new HashMap<String, Integer>();

    private MappingType mappingType;

    private Map<String, ArrayList<SubstituteValue>> postProcessingCache = new HashMap<String, ArrayList<SubstituteValue>>();
    
    private boolean sendPayload = false;

    private boolean needsRepair = false;

    public static String SOURCE_ID = "source.id";

    public boolean hasError() {
        return error != null;
    }

    public int addRequest(C8YRequest c8yRequest) {
        requests.add(c8yRequest);
        return requests.size() - 1;
    }

    /*
     * Keep track of the extracted size of every extracted values for a
     * <code>pathTarget</code>
     * 
     * @param pathTarget jsonPath of target in a substitution
     * 
     * @param card cardinality of this <code>pathTarget</code> found when extracting
     * values from the payload
     * 
     * @return true if all added cardinalities are the same, fals if at least two
     * different cardinalities exist.
     */
    public void addCardinality(String pathTarget, Integer card) {
        cardinality.put(pathTarget, card);
        Set<Map.Entry<String, Integer>> entries = cardinality.entrySet();
        Stream<Entry<String, Integer>> stream1 = entries.stream()
                .filter(e -> !ProcessingContext.SOURCE_ID.equals(e.getKey()));
        Map<Integer, Long> collect = stream1.collect(Collectors.groupingBy(Map.Entry::getValue, Collectors.counting()));
        needsRepair = (collect.size() != 1);
    }

    public C8YRequest getCurrentRequest() {
        return requests.get(requests.size()-1);
    }
}